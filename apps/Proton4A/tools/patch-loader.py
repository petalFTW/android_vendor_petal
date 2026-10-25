#!/usr/bin/env python3
"""Diagnostic only: skip glibc rtld's set_robust_list under Android seccomp.

Modify only the copied loader inside the probe APK. Never patch the bundled
rootfs or the device's glibc. The offset is pinned to the test rootfs build.
"""
import hashlib
import sys
from pathlib import Path


path = Path(sys.argv[1])
data = bytearray(path.read_bytes())
expected = "17538b8f9889a470c061f69a8fea8124da89627311cd16546c133a89f09056df"
if hashlib.sha256(data).hexdigest() != expected:
    raise SystemExit("glibc loader build changed; revalidate the syscall offset")
# aarch64: mov x8, #99 at 0x10720, svc #0 at 0x1073c.
if data[0x10720:0x10724] != bytes.fromhex("680c80d2"):
    raise SystemExit("set_robust_list syscall instruction not found")
if data[0x1073c:0x10740] != bytes.fromhex("010000d4"):
    raise SystemExit("expected svc #0 instruction not found")
# mov x0, #0 instead of svc: register robust-list as unsupported in this
# diagnostic and let subsequent startup syscalls reveal further restrictions.
data[0x1073c:0x10740] = bytes.fromhex("000080d2")
# rseq(293) is trapped as well. Return -ENOSYS so glibc takes its existing
# unsupported-kernel fallback instead of assuming that rseq was registered.
if data[0x10784:0x10788] != bytes.fromhex("a82480d2"):
    raise SystemExit("rseq syscall instruction not found")
if data[0x10790:0x10794] != bytes.fromhex("010000d4"):
    raise SystemExit("expected rseq svc #0 instruction not found")
data[0x10790:0x10794] = bytes.fromhex("a0048092")
path.write_bytes(data)
