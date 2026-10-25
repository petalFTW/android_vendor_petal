#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0

import base64
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

import client

HERE = Path(__file__).resolve().parent


class OfflineAuthorizationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.storage = tempfile.TemporaryDirectory(prefix="petal-auth-test-", dir="/tmp/opencode")
        cls.addClassCleanup(cls.storage.cleanup)
        cls.root = Path(cls.storage.name)
        cls.private = cls.root / "test-private.pem"
        cls.public = cls.root / "test-public.pem"
        subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt",
                        "rsa_keygen_bits:3072", "-out", str(cls.private)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(["openssl", "pkey", "-in", str(cls.private), "-pubout",
                        "-out", str(cls.public)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def setUp(self):
        self.storage_case = tempfile.TemporaryDirectory(dir=self.root)
        self.addCleanup(self.storage_case.cleanup)
        self.top = Path(self.storage_case.name)
        self.directory = self.top / "vendor/petal/build/maintainer"
        (self.directory / "keys").mkdir(parents=True)
        (self.directory / "keys/test-owner.pem").write_bytes(self.public.read_bytes())
        self.revocations = self.directory / "revocations.json"
        self.revocations.write_text(json.dumps({"schema_version": 1, "serials": [], "key_ids": []}))
        self.device_directory = self.top / "device/oneplus/macanc"
        self.device_directory.mkdir(parents=True)
        (self.device_directory / "petal_macanc.mk").touch()
        self.config = self.device_directory / "petal-maintainer.mk"
        self.certificate = self.top / "maintainer.json"
        self.config.write_text("PETAL_AUTH_DEVICE := macanc\n"
                               "PETAL_MAINTAINER_CERT := maintainer.json\n")
        now = int(time.time())
        self.payload = {"schema_version": 1, "algorithm": "rsa-sha256", "key_id": "test-owner",
                        "serial": "test-serial", "maintainer_id": "test-maintainer",
                        "official": True, "grants": [{"device": "macanc", "product": "petal_macanc"}],
                        "variants": ["eng", "user"], "releases": ["bp4a"],
                        "issued_at": now - 10, "expires_at": now + 3600}
        self.sign()

    def sign(self, raw=None):
        raw = raw or json.dumps(self.payload, sort_keys=True, separators=(",", ":")).encode()
        signature = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sigopt", "rsa_padding_mode:pkcs1",
             "-sign", str(self.private)], input=raw, stdout=subprocess.PIPE, check=True).stdout
        self.certificate.write_text(json.dumps({"payload": base64.b64encode(raw).decode(),
                                               "signature": base64.b64encode(signature).decode()}))

    def authorize(self, variant="eng", release="bp4a"):
        return client.authorize(self.top, "petal_macanc", variant, release)

    def assert_rejected(self, reason):
        result = self.authorize()
        self.assertEqual(result["authorized"], "false")
        self.assertEqual(result["build_type"], "UNOFFICIAL")
        self.assertEqual(result["maintainer_id"], "unknown")
        self.assertEqual(result["reason"], reason)

    def test_official_and_user_selection(self):
        for variant in ("eng", "user"):
            with self.subTest(variant=variant):
                result = self.authorize(variant=variant)
                self.assertEqual(result["build_type"], "OFFICIAL")
                self.assertEqual(result["authorized"], "true")
                self.assertEqual(result["maintainer_id"], "test-maintainer")
                self.assertEqual(result["reason"], "offline_verified")

    def test_valid_unofficial(self):
        self.payload["official"] = False
        self.sign()
        result = self.authorize()
        self.assertEqual(result["authorized"], "true")
        self.assertEqual(result["build_type"], "UNOFFICIAL")

    def test_absolute_certificate_path(self):
        self.config.write_text(f"PETAL_AUTH_DEVICE := macanc\n"
                               f"PETAL_MAINTAINER_CERT := {self.certificate}\n")
        self.assertEqual(self.authorize()["authorized"], "true")

    def test_no_expiry_and_multiple_grants(self):
        self.payload["expires_at"] = None
        self.payload["grants"].append({"device": "macan", "product": "petal_macan"})
        self.sign()
        self.assertEqual(self.authorize()["authorized"], "true")

    def test_build_selection_is_bound(self):
        self.assertEqual(self.authorize(variant="userdebug")["reason"], "variant_not_allowed")
        self.assertEqual(self.authorize(release="other")["reason"], "release_not_allowed")
        self.payload["grants"] = [{"device": "macan", "product": "petal_macan"}]
        self.sign()
        self.assert_rejected("device_not_allowed")

    def test_expiry_and_future_issue(self):
        self.payload["issued_at"] = int(time.time()) - 100
        self.payload["expires_at"] = int(time.time()) - 1
        self.sign()
        self.assert_rejected("expired_certificate")
        self.payload["issued_at"] = int(time.time()) + 300
        self.payload["expires_at"] = None
        self.sign()
        self.assert_rejected("not_yet_valid")

    def test_modified_payload_and_signature(self):
        envelope = json.loads(self.certificate.read_text())
        changed = dict(self.payload, maintainer_id="imposter")
        envelope["payload"] = base64.b64encode(json.dumps(changed).encode()).decode()
        self.certificate.write_text(json.dumps(envelope))
        self.assert_rejected("bad_signature")
        self.sign()
        envelope = json.loads(self.certificate.read_text())
        signature = bytearray(base64.b64decode(envelope["signature"]))
        signature[0] ^= 1
        envelope["signature"] = base64.b64encode(signature).decode()
        self.certificate.write_text(json.dumps(envelope))
        self.assert_rejected("bad_signature")

    def test_unknown_and_path_traversal_key_ids(self):
        self.payload["key_id"] = "unknown"
        self.sign()
        self.assert_rejected("unknown_signing_key")
        self.payload["key_id"] = "../../test-owner"
        self.sign()
        self.assert_rejected("invalid_certificate")

    def test_serial_and_authority_revocation(self):
        for field, value in (("serials", "test-serial"), ("key_ids", "test-owner")):
            with self.subTest(field=field):
                revoked = {"schema_version": 1, "serials": [], "key_ids": []}
                revoked[field] = [value]
                self.revocations.write_text(json.dumps(revoked))
                self.assert_rejected("revoked_certificate")

    def test_missing_or_malformed_revocations(self):
        self.revocations.unlink()
        self.assert_rejected("offline_file_missing")
        self.revocations.write_text('{"schema_version": 1, "serials": "oops", "key_ids": []}')
        self.assert_rejected("invalid_revocations")

    def test_missing_legacy_and_duplicate_configuration(self):
        self.certificate.unlink()
        self.assert_rejected("certificate_missing")
        self.config.write_text("PETAL_AUTH_DEVICE := macanc\nPETAL_MAINTAINER_KEY := old-token\n")
        self.assert_rejected("legacy_credential")
        self.config.write_text("PETAL_AUTH_DEVICE := macanc\nPETAL_AUTH_DEVICE := macan\n")
        self.assert_rejected("invalid_device_config")

    def test_invalid_json_base64_size_and_signed_fields(self):
        for data in ("not json", '{"payload":"!", "signature":"!"}',
                     "x" * (client.MAX_CERT_SIZE + 1)):
            with self.subTest(data=data[:30]):
                self.certificate.write_text(data)
                self.assert_rejected("invalid_certificate")
        self.sign(b'{"key_id":"test-owner","key_id":"test-owner"}')
        self.assert_rejected("invalid_certificate")
        self.payload["official"] = "true"
        self.sign()
        self.assert_rejected("invalid_certificate")

    def test_missing_openssl(self):
        with patch("client.subprocess.run", side_effect=FileNotFoundError()):
            self.assert_rejected("verifier_unavailable")

    def test_issuer_and_hook_end_to_end(self):
        self.certificate.unlink()
        command = [sys.executable, str(HERE / "issue.py"), "--private-key", str(self.private),
                   "--key-id", "test-owner", "--maintainer", "issued-maintainer",
                   "--grant", "macanc:petal_macanc", "--variant", "eng", "--variant", "user",
                   "--release", "bp4a", "--official", "--days", "365",
                   "--output", str(self.certificate)]
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.authorize()["maintainer_id"], "issued-maintainer")
        original = self.certificate.read_bytes()
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.certificate.read_bytes(), original)
        (self.directory / "client.py").symlink_to(HERE / "client.py")
        environment = dict(os.environ, TOP=str(self.top), TARGET_PRODUCT="petal_macanc",
                           TARGET_BUILD_VARIANT="eng", TARGET_RELEASE="bp4a")
        shell = 'source "$1" --build-mode bacon; test "$PETAL_AUTH_BUILD_TYPE" = OFFICIAL && test "$PETAL_AUTH_MAINTAINER" = issued-maintainer'
        result = subprocess.run(["bash", "-eu", "-c", shell, "test", str(HERE / "hook.sh")],
                                env=environment, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        shell = 'source "$1" --build-mode Settings; test "$PETAL_AUTH_BUILD_TYPE" = UNOFFICIAL && test "$PETAL_AUTH_REASON" = not_checked'
        result = subprocess.run(["bash", "-eu", "-c", shell, "test", str(HERE / "hook.sh")],
                                env=environment, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
