#!/usr/bin/env python3
"""Neutralise Android-trapped glibc syscalls in bundled aarch64 libraries.

Android's app seccomp policy terminates the process on `set_robust_list` (99)
and `rseq` (293), which glibc issues during startup and thread creation. The
bundled loader is patched by patch-loader.py; this does the same for libc (and
any other glibc object that inlines the calls) by turning the trailing `svc`
into an immediate return:

    set_robust_list -> return 0 (success)
    rseq            -> return -ENOSYS (glibc disables rseq)

Usage: patch-glibc.py <file-or-directory>...
"""
import os
import stat
import sys
from pathlib import Path

MOV_X8_ROBUST = bytes.fromhex("680c80d2")   # mov x8, #99
MOV_X8_RSEQ = bytes.fromhex("a82480d2")     # mov x8, #293
SVC = bytes.fromhex("010000d4")             # svc #0
RET_OK = bytes.fromhex("000080d2")          # mov x0, #0
RET_ENOSYS = bytes.fromhex("a0048092")      # mov x0, #-38

# glibc's accept() issues the raw accept syscall (202), which Android's app
# seccomp policy traps, while accept4 (242) is allowed. Rewrite both the
# fast and the cancellable path to accept4(..., flags=0).
ACCEPT_FAST = bytes.fromhex("63010034e00313aa481980d2010000d4")
ACCEPT_FAST_NEW = bytes.fromhex("63010034030080d2481e80d2010000d4")
ACCEPT_SLOW = bytes.fromhex("e303002ae20314aae00313aa481980d2010000d4")
ACCEPT_SLOW_NEW = bytes.fromhex("030080d2e20314aae00313aa481e80d2010000d4")


def patch_accept(data: bytearray) -> int:
    count = 0
    for old, new, label in ((ACCEPT_FAST, ACCEPT_FAST_NEW, "fast"),
                            (ACCEPT_SLOW, ACCEPT_SLOW_NEW, "slow")):
        if data.count(old) == 1:
            data[data.find(old):data.find(old) + len(old)] = new
            count += 1
    return count

# The svc follows the x8 load within a short straight-line window.
WINDOW = 64


def patch(data: bytearray, marker: bytes, replacement: bytes) -> int:
    patched = 0
    start = 0
    while True:
        at = data.find(marker, start)
        if at < 0:
            return patched
        start = at + len(marker)
        end = min(len(data) - 4, at + WINDOW)
        for offset in range(at + 4, end + 1, 4):
            if data[offset:offset + 4] == SVC:
                if data[offset:offset + 4] != replacement:
                    data[offset:offset + 4] = replacement
                    patched += 1
                break


def patch_file(path: Path) -> int:
    data = bytearray(path.read_bytes())
    if not data.startswith(b"\x7fELF"):
        return 0
    count = patch(data, MOV_X8_ROBUST, RET_OK)
    count += patch(data, MOV_X8_RSEQ, RET_ENOSYS)
    if path.name == "libc.so.6":
        count += patch_accept(data)
    if count:
        mode = path.stat().st_mode
        path.write_bytes(data)
        os.chmod(path, stat.S_IMODE(mode))
    return count


def main(argv):
    total = 0
    for argument in argv:
        path = Path(argument)
        targets = sorted(p for p in path.rglob("*") if p.is_file()) if path.is_dir() else [path]
        for target in targets:
            try:
                count = patch_file(target)
            except OSError:
                continue
            if count:
                print(f"patched {count} syscall site(s) in {target}")
                total += count
    print(f"total patched sites: {total}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    sys.exit(main(sys.argv[1:]))
