#!/usr/bin/env python3
"""Assemble the Proton4A runtime payload from the built Box64 and Proton trees.

The payload is a gzip tar archive containing:

    box64            ARM64 glibc translator (aarch64 ELF)
    proton/          upstream x86-64 Proton redist
    manifest.json    provenance and hashes

Nothing is downloaded here; build-box64.sh and build-proton.sh must have run.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile


WORKSPACE = Path(os.environ.get(
    "PROTON4A_WORKSPACE",
    os.path.expanduser("~/proton4a-build"),
))
STAGE = WORKSPACE / "prebuilt" / "runtime"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def describe(path: Path) -> str:
    return subprocess.run(
        ["file", "-b", str(path)], check=True, text=True, capture_output=True
    ).stdout.strip()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--box64", type=Path, default=WORKSPACE / "prebuilt" / "box64")
    parser.add_argument(
        "--redist",
        type=Path,
        default=WORKSPACE / "upstream" / "proton-11.0-2-redist",
    )
    parser.add_argument("--rootfs", type=Path, default=WORKSPACE / "prebuilt" / "rootfs")
    parser.add_argument("--turnip", type=Path, default=WORKSPACE / "prebuilt" / "turnip")
    parser.add_argument("--x86-64-libs", type=Path,
                        default=WORKSPACE / "prebuilt" / "x86_64-libs")
    parser.add_argument("--output", type=Path,
                        default=WORKSPACE / "prebuilt" / "proton4a-runtime.tar.gz")
    args = parser.parse_args()

    box64 = args.box64
    redist = args.redist
    rootfs = args.rootfs
    turnip = args.turnip
    turnip_driver = turnip / "usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so"
    if not box64.is_file():
        parser.error(f"missing Box64 binary: {box64} (run tools/build-box64.sh)")
    if not (redist / "files" / "bin" / "wine").is_file():
        parser.error(f"missing Proton redist: {redist} (run tools/build-proton.sh)")
    loader = rootfs / "lib" / "ld-linux-aarch64.so.1"
    if not loader.exists():
        parser.error(f"missing glibc rootfs: {rootfs} (run tools/build-rootfs.sh)")

    if "aarch64" not in describe(box64):
        parser.error(f"Box64 is not an aarch64 ELF: {box64}")
    wine = redist / "files" / "bin" / "wine"
    if "x86-64" not in describe(wine):
        parser.error(f"Proton Wine is not x86-64: {wine}")

    version_file = redist / "version"
    proton_version = version_file.read_text().strip().split(" ", 1)[-1] if version_file.exists() else "unknown"
    manifest = {
        "format": 1,
        "box64": {
            "revision": "50069bcf69f62a780074cf8458c5339f00579a5a",
            "sha256": sha256(box64),
        },
        "proton": {
            "version": proton_version,
            "wine_sha256": sha256(wine),
        },
        "rootfs": {
            "suite": "bookworm-arm64",
            "loader_sha256": sha256(loader),
        },
        "turnip": {
            "mesa": "26.2.2",
            "backend": "kgsl",
            "sha256": sha256(turnip_driver) if turnip_driver.is_file() else None,
        },
    }

    if STAGE.exists():
        for child in sorted(STAGE.rglob("*"), reverse=True):
            if child.is_file() or child.is_symlink():
                child.unlink()
            else:
                child.rmdir()
    STAGE.mkdir(parents=True, exist_ok=True)

    print("copying Box64...")
    box64_target = STAGE / "box64"
    subprocess.run(["cp", "-a", str(box64), str(box64_target)], check=True)

    print("copying Proton redist...")
    subprocess.run(["cp", "-a", str(redist) + "/.", str(STAGE / "proton")], check=True)

    print("copying glibc rootfs...")
    subprocess.run(["cp", "-a", str(rootfs) + "/.", str(STAGE / "rootfs")], check=True)
    # Debian fontconfig has absolute /usr/share links, which Android's tar
    # rejects when unpacking below /data/adb. Make them rootfs-relative.
    for link in (STAGE / "rootfs/etc/fonts/conf.d").glob("*"):
        if link.is_symlink() and os.readlink(link).startswith("/"):
            target = STAGE / "rootfs" / os.readlink(link).lstrip("/")
            link.unlink()
            link.symlink_to(os.path.relpath(target, link.parent))

    if turnip_driver.is_file():
        print("merging Turnip ICD into rootfs...")
        subprocess.run(["cp", "-a", str(turnip) + "/.", str(STAGE / "rootfs")], check=True)

    # Neutralise Android-trapped glibc syscalls in the shipped libraries.
    for libdir in ("lib/aarch64-linux-gnu", "usr/lib/aarch64-linux-gnu"):
        directory = STAGE / "rootfs" / libdir
        if directory.is_dir():
            subprocess.run(["python3", str(Path(__file__).parent / "patch-glibc.py"),
                            str(directory)], check=True)

    # Android has no writable /tmp. Wine's server socket directory is
    # "/tmp/.wine-%u" in wineserver and in ntdll.so (the client). Make it
    # relative to the working directory, which the launcher sets to a writable
    # path that contains a "wine" subdirectory.
    for path in (STAGE / "proton").rglob("*"):
        if not path.is_file() or path.stat().st_size > 8 * 1024 * 1024:
            continue
        contents = path.read_bytes()
        if b"/tmp/.wine-%u" in contents:
            mode = path.stat().st_mode
            os.chmod(path, mode | 0o200)
            path.write_bytes(contents.replace(b"/tmp/.wine-%u", b"wine/.wine-%u"))
            os.chmod(path, mode)
            print(f"patched Wine server directory in {path.relative_to(STAGE)}")

    # Proton expects libgcc_s/libstdc++ from the Steam Runtime; Box64 does not
    # wrap them, so ship the x86-64 versions inside the Proton tree.
    if args.x86_64_libs.is_dir():
        print("merging x86-64 support libraries...")
        target = STAGE / "proton/files/lib/x86_64-linux-gnu"
        target.mkdir(parents=True, exist_ok=True)
        subprocess.run(["cp", "-a", str(args.x86_64_libs) + "/.", str(target)], check=True)

    # Debian Xvfb hard-codes /usr/bin/xkbcomp, which does not exist on Android.
    # Redirect that single embedded directory to the current directory and run
    # Xvfb from its bundled usr/bin. The wrapper loads ARM64 xkbcomp with our
    # glibc loader (it cannot use the Android ELF interpreter directly).
    bin_dir = STAGE / "rootfs/usr/bin"
    xvfb = bin_dir / "Xvfb"
    original = xvfb.read_bytes()
    old_bin_dir = b"/usr/bin\0"
    if original.count(old_bin_dir) != 1:
        parser.error("expected exactly one Xvfb XKB binary directory")
    xvfb.write_bytes(original.replace(old_bin_dir, b".\0" + b"\0" * 7, 1))
    compiler = bin_dir / "xkbcomp"
    compiler.rename(bin_dir / "xkbcomp.real")
    compiler.write_text("""#!/system/bin/sh
bin_dir=$(pwd)
rootfs=$bin_dir/../..
exec "$rootfs/lib/ld-linux-aarch64.so.1" --library-path "$rootfs/usr/lib/aarch64-linux-gnu:$rootfs/lib/aarch64-linux-gnu" "$bin_dir/xkbcomp.real" "$@"
""")
    compiler.chmod(0o755)

    (STAGE / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    print(f"writing {args.output}...")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # The app's TarArchive reader understands GNU long names, not PAX records.
    with tarfile.open(args.output, "w:gz", format=tarfile.GNU_FORMAT) as archive:
        archive.add(STAGE, arcname="runtime", filter=_normalize)

    print(f"payload: {args.output} ({args.output.stat().st_size} bytes)")
    print(f"manifest: {json.dumps(manifest)}")
    return 0


def _normalize(info: tarfile.TarInfo) -> tarfile.TarInfo:
    # Normalise ownership and timestamps so the payload is reproducible.
    info.uid = 0
    info.gid = 0
    info.uname = "root"
    info.gname = "root"
    info.mtime = 0
    return info


if __name__ == "__main__":
    sys.exit(main())
