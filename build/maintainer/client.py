#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0

import argparse
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time

ATOM = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,63}\Z")
FIELDS = ("build_type", "authorized", "maintainer_id", "device", "reason")
MAX_CERT_SIZE = 32768
ALGORITHM = "rsa-sha256"


class AuthorizationError(ValueError):
    pass


def fallback(device="unknown", reason="invalid_certificate"):
    return dict(zip(FIELDS, ("UNOFFICIAL", "false", "unknown", device, reason)))


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise AuthorizationError("invalid_certificate")
        result[key] = value
    return result


def load_json(data):
    return json.loads(data, object_pairs_hook=unique_object)


def atom(value):
    return isinstance(value, str) and ATOM.fullmatch(value) is not None


def read_device(top, product):
    if not atom(product) or not product.startswith("petal_"):
        raise AuthorizationError("invalid_product")
    products = list((top / "device").glob(f"*/*/{product}.mk"))
    if len(products) != 1:
        raise AuthorizationError("device_config_missing")
    values = {}
    for line in (products[0].parent / "petal-maintainer.mk").read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        match = re.fullmatch(
            r"(PETAL_AUTH_DEVICE|PETAL_MAINTAINER_CERT|PETAL_MAINTAINER_KEY)\s*:=\s*([^\s#]*)",
            line)
        if not match or match[1] in values:
            raise AuthorizationError("invalid_device_config")
        values[match[1]] = match[2]
    device = values.get("PETAL_AUTH_DEVICE")
    if not atom(device):
        raise AuthorizationError("invalid_device_config")
    return device, values


def read_certificate(path):
    with path.open("rb") as stream:
        data = stream.read(MAX_CERT_SIZE + 1)
    if len(data) > MAX_CERT_SIZE:
        raise AuthorizationError("invalid_certificate")
    envelope = load_json(data)
    if not isinstance(envelope, dict) or set(envelope) != {"payload", "signature"}:
        raise AuthorizationError("invalid_certificate")
    payload = base64.b64decode(envelope["payload"], validate=True)
    signature = base64.b64decode(envelope["signature"], validate=True)
    return load_json(payload), payload, signature


def verify_signature(key_path, payload, signature):
    # openssl wants the signature in a file, annoying little shit
    with tempfile.TemporaryDirectory(prefix="petal-verify-") as directory:
        signature_path = Path(directory) / "signature"
        signature_path.write_bytes(signature)
        try:
            result = subprocess.run(
                ["openssl", "dgst", "-sha256", "-sigopt", "rsa_padding_mode:pkcs1",
                 "-verify", str(key_path), "-signature", str(signature_path)], input=payload,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=8)
        except FileNotFoundError:
            raise AuthorizationError("verifier_unavailable") from None
    if result.returncode != 0:
        raise AuthorizationError("bad_signature")


def validate_certificate(cert, device, product, variant, release, now):
    expected = {"schema_version", "algorithm", "key_id", "serial", "maintainer_id",
                "official", "grants", "variants", "releases", "issued_at", "expires_at"}
    if not isinstance(cert, dict) or set(cert) != expected:
        raise AuthorizationError("invalid_certificate")
    if type(cert["schema_version"]) is not int or cert["schema_version"] != 1:
        raise AuthorizationError("invalid_certificate")
    if cert["algorithm"] != ALGORITHM:
        raise AuthorizationError("invalid_certificate")
    if not all(atom(cert[key]) for key in ("key_id", "serial", "maintainer_id")):
        raise AuthorizationError("invalid_certificate")
    if type(cert["official"]) is not bool:
        raise AuthorizationError("invalid_certificate")
    issued, expires = cert["issued_at"], cert["expires_at"]
    if type(issued) is not int or issued < 0:
        raise AuthorizationError("invalid_certificate")
    if expires is not None and (type(expires) is not int or expires <= issued):
        raise AuthorizationError("invalid_certificate")
    if issued > now:
        raise AuthorizationError("not_yet_valid")
    if expires is not None and now >= expires:
        raise AuthorizationError("expired_certificate")
    grants = cert["grants"]
    if not isinstance(grants, list) or not grants or any(
            not isinstance(grant, dict) or set(grant) != {"device", "product"}
            or not atom(grant["device"]) or not atom(grant["product"])
            or not grant["product"].startswith("petal_") for grant in grants):
        raise AuthorizationError("invalid_certificate")
    for key in ("variants", "releases"):
        values = cert[key]
        if not isinstance(values, list) or not values or not all(atom(v) for v in values):
            raise AuthorizationError("invalid_certificate")
    if any(v not in ("eng", "user", "userdebug") for v in cert["variants"]):
        raise AuthorizationError("invalid_certificate")
    if {"device": device, "product": product} not in grants:
        raise AuthorizationError("device_not_allowed")
    if variant not in cert["variants"]:
        raise AuthorizationError("variant_not_allowed")
    if release not in cert["releases"]:
        raise AuthorizationError("release_not_allowed")


def check_revocations(directory, cert):
    revoked = load_json((directory / "revocations.json").read_bytes())
    if not isinstance(revoked, dict) or set(revoked) != {"schema_version", "serials", "key_ids"}:
        raise AuthorizationError("invalid_revocations")
    if type(revoked["schema_version"]) is not int or revoked["schema_version"] != 1:
        raise AuthorizationError("invalid_revocations")
    for key in ("serials", "key_ids"):
        if not isinstance(revoked[key], list) or not all(atom(v) for v in revoked[key]):
            raise AuthorizationError("invalid_revocations")
    if cert["serial"] in revoked["serials"] or cert["key_id"] in revoked["key_ids"]:
        raise AuthorizationError("revoked_certificate")


def authorize(top, product, variant, release):
    device = "unknown"
    try:
        top = top.resolve()
        device, config = read_device(top, product)
        if not atom(variant) or not atom(release):
            raise AuthorizationError("invalid_build_selection")
        certificate = config.get("PETAL_MAINTAINER_CERT")
        if not certificate:
            raise AuthorizationError("legacy_credential" if config.get("PETAL_MAINTAINER_KEY")
                                     else "certificate_missing")
        path = Path(certificate)
        if not path.is_absolute():
            path = top / path
        if not path.is_file():
            raise AuthorizationError("certificate_missing")
        cert, payload, signature = read_certificate(path)
        if not isinstance(cert, dict) or not atom(cert.get("key_id")):
            raise AuthorizationError("invalid_certificate")
        directory = top / "vendor/petal/build/maintainer"
        key_path = directory / "keys" / (cert["key_id"] + ".pem")
        if not key_path.is_file():
            raise AuthorizationError("unknown_signing_key")
        verify_signature(key_path, payload, signature)
        validate_certificate(cert, device, product, variant, release, int(time.time()))
        check_revocations(directory, cert)
        build_type = "OFFICIAL" if cert["official"] else "UNOFFICIAL"
        return dict(zip(FIELDS, (build_type, "true", cert["maintainer_id"], device,
                                "offline_verified")))
    except AuthorizationError as error:
        return fallback(device, str(error))
    except FileNotFoundError:
        return fallback(device, "offline_file_missing")
    except (subprocess.TimeoutExpired, OSError):
        return fallback(device, "verifier_unavailable")
    except Exception:
        # don't cough credentials into the build log
        return fallback(device)


def main():
    parser = argparse.ArgumentParser(description="Verify a petalOS maintainer certificate offline")
    parser.add_argument("--top", type=Path, required=True)
    args = parser.parse_args()
    result = authorize(args.top, os.environ.get("TARGET_PRODUCT", ""),
                       os.environ.get("TARGET_BUILD_VARIANT", ""),
                       os.environ.get("TARGET_RELEASE", ""))
    print("|".join(result[key] for key in FIELDS))
    print(f"petalOS: {result['build_type']} ({result['reason']}), maintainer={result['maintainer_id']}",
          file=sys.stderr)


if __name__ == "__main__":
    main()
