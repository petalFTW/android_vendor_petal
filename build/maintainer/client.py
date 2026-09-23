#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0

import argparse
import base64
import json
import os
from pathlib import Path
import re
import secrets
import signal
import sys
import time
import urllib.parse
import urllib.request

ATOM = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,63}\Z")
FIELDS = ("build_type", "authorized", "maintainer_id", "device", "reason")


def fallback(device="unknown", reason="unavailable"):
    return dict(zip(FIELDS, ("UNOFFICIAL", "false", "unknown", device, reason)))


def read_device(top, product):
    if not ATOM.fullmatch(product) or not product.startswith("petal_"):
        raise ValueError("invalid product")
    products = list((top / "device").glob(f"*/*/{product}.mk"))
    if len(products) != 1:
        raise ValueError("ambiguous or missing device tree")
    values = {}
    for line in (products[0].parent / "petal-maintainer.mk").read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        match = re.fullmatch(r"(PETAL_AUTH_DEVICE|PETAL_MAINTAINER_KEY)\s*:=\s*([^\s#]*)", line)
        if not match or match[1] in values:
            raise ValueError("credential file must contain literal assignments")
        values[match[1]] = match[2]
    device = values["PETAL_AUTH_DEVICE"]
    if not ATOM.fullmatch(device):
        raise ValueError("invalid device")
    credential = values["PETAL_MAINTAINER_KEY"]
    if not credential or len(credential) > 8192:
        raise ValueError("missing credential")
    base64.b64decode(credential, validate=True)
    return device, credential


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("authorization endpoint redirected")


def validate_response(result, request, now):
    if not isinstance(result, dict) or result.get("schema_version") != 1:
        raise ValueError("invalid response")
    for key in ("nonce", "device", "product", "variant", "release"):
        if result.get(key) != request[key]:
            raise ValueError("response binding mismatch")
    issued = result.get("issued_at")
    expires = result.get("expires_at")
    if type(issued) is not int or type(expires) is not int:
        raise ValueError("invalid validity window")
    if not now - 60 <= issued <= now + 30 or not now < expires <= issued + 300:
        raise ValueError("expired response")
    authorized = result.get("authorized")
    official = result.get("official")
    if type(authorized) is not bool or type(official) is not bool:
        raise ValueError("invalid authorization flags")
    expected = "OFFICIAL" if authorized and official else "UNOFFICIAL"
    if result.get("build_type") != expected:
        raise ValueError("inconsistent build type")
    maintainer = result.get("maintainer_id") if authorized else "unknown"
    reason = result.get("reason")
    if not isinstance(maintainer, str) or not ATOM.fullmatch(maintainer):
        raise ValueError("invalid maintainer")
    if not isinstance(reason, str) or not ATOM.fullmatch(reason):
        raise ValueError("invalid reason")
    return dict(zip(FIELDS, (expected, str(authorized).lower(), maintainer,
                            request["device"], reason)))


def authorize(top, product, variant, release, opener=None):
    device = "unknown"
    try:
        device, credential = read_device(top, product)
        if not all(ATOM.fullmatch(value) for value in (variant, release)):
            raise ValueError("missing build selection")
        endpoint = json.loads((top / "vendor/petal/build/maintainer/api.json").read_text())["endpoint"]
        url = urllib.parse.urlsplit(endpoint)
        if url.scheme != "https" or not url.hostname or url.username or url.password or url.fragment or url.query:
            raise ValueError("invalid endpoint")
        payload = {"schema_version": 1, "credential": credential, "device": device,
                   "product": product, "variant": variant, "release": release,
                   "nonce": secrets.token_hex(16)}
        request = urllib.request.Request(endpoint, data=json.dumps(payload).encode(),
                                         headers={"Content-Type": "application/json"}, method="POST")
        open_request = opener or urllib.request.build_opener(NoRedirect()).open
        with open_request(request, timeout=8) as response:
            if response.status != 200:
                raise ValueError("API failed")
            body = response.read(16385)
        if len(body) > 16384:
            raise ValueError("oversized response")
        return validate_response(json.loads(body), payload, int(time.time()))
    except Exception:
        # Never print credentials or server error bodies.
        return fallback(device)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--top", type=Path, required=True)
    args = parser.parse_args()

    def deadline(signum, frame):
        raise TimeoutError("authorization deadline")

    signal.signal(signal.SIGALRM, deadline)
    signal.alarm(12)
    try:
        result = authorize(args.top, os.environ.get("TARGET_PRODUCT", ""),
                           os.environ.get("TARGET_BUILD_VARIANT", ""),
                           os.environ.get("TARGET_RELEASE", ""))
    finally:
        signal.alarm(0)
    print("|".join(result[key] for key in FIELDS))
    print(f"petalOS: {result['build_type']} ({result['reason']}), maintainer={result['maintainer_id']}",
          file=sys.stderr)


if __name__ == "__main__":
    main()
