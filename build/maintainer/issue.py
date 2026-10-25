#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0

import argparse
import base64
import json
from pathlib import Path
import secrets
import subprocess
import time

from client import ALGORITHM, MAX_CERT_SIZE, atom, validate_certificate


def main():
    parser = argparse.ArgumentParser(description="Issue a signed offline maintainer certificate")
    parser.add_argument("--private-key", type=Path, required=True)
    parser.add_argument("--key-id", required=True)
    parser.add_argument("--maintainer", required=True)
    parser.add_argument("--grant", action="append", required=True, metavar="DEVICE:PRODUCT")
    parser.add_argument("--variant", action="append", required=True,
                        choices=("eng", "user", "userdebug"))
    parser.add_argument("--release", action="append", required=True)
    status = parser.add_mutually_exclusive_group(required=True)
    status.add_argument("--official", action="store_true")
    status.add_argument("--unofficial", action="store_true")
    expiry = parser.add_mutually_exclusive_group(required=True)
    expiry.add_argument("--days", type=int)
    expiry.add_argument("--no-expiry", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not all(atom(v) for v in (args.key_id, args.maintainer, *args.release)):
        parser.error("IDs and releases must be 1-64 letters, numbers, dots, underscores or hyphens")
    if args.days is not None and args.days <= 0:
        parser.error("--days must be positive")
    grants = []
    for value in args.grant:
        parts = value.split(":")
        if len(parts) != 2 or not all(atom(v) for v in parts) or not parts[1].startswith("petal_"):
            parser.error("each grant must be DEVICE:petal_PRODUCT")
        grant = dict(zip(("device", "product"), parts))
        if grant not in grants:
            grants.append(grant)
    now = int(time.time())
    cert = {"schema_version": 1, "algorithm": ALGORITHM, "key_id": args.key_id,
            "serial": secrets.token_hex(16), "maintainer_id": args.maintainer,
            "official": args.official, "grants": grants,
            "variants": sorted(set(args.variant)), "releases": sorted(set(args.release)),
            "issued_at": now, "expires_at": None if args.no_expiry else now + args.days * 86400}
    validate_certificate(cert, grants[0]["device"], grants[0]["product"],
                         cert["variants"][0], cert["releases"][0], now)
    payload = json.dumps(cert, sort_keys=True, separators=(",", ":")).encode()
    try:
        # let openssl ask for the password, no secrets in argv
        signature = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sigopt", "rsa_padding_mode:pkcs1",
             "-sign", str(args.private_key)],
            input=payload, stdout=subprocess.PIPE, check=True).stdout
        envelope = {"payload": base64.b64encode(payload).decode(),
                    "signature": base64.b64encode(signature).decode()}
        data = json.dumps(envelope, indent=2) + "\n"
        if len(data.encode()) > MAX_CERT_SIZE:
            parser.error("certificate exceeds the verifier size limit")
        with args.output.open("x") as stream:
            stream.write(data)
    except (OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Certificate issuance failed: {error}\n")
    print(f"Certificate: {args.output}")
    print(json.dumps(cert, indent=2))


if __name__ == "__main__":
    main()
