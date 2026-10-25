#!/usr/bin/env bash
# Cross-build the glibc ARM64 Box64 + Box32 translator for Proton4A.
#
# Box64 translates the x86-64 Linux userspace (Proton's Wine, DXVK and
# vkd3d-proton) to ARM64. It is built for a glibc Linux userspace, which
# Proton4A provides on-device; it is not a Bionic library.
set -euo pipefail

revision=50069bcf69f62a780074cf8458c5339f00579a5a
# Build artifacts live outside the ROM tree so the Android build never scans
# vendored sources for Android.mk files.
workspace="${PROTON4A_WORKSPACE:-$HOME/proton4a-build}"
workdir="${PROTON4A_BOX64_DIR:-$workspace/upstream/box64-build}"
source_dir="$workdir/source"
build_dir="$workdir/build"
out_dir="$workspace/prebuilt"

patches_dir="$(cd "$(dirname "$0")" && pwd)/box64"

mkdir -p "$workdir" "$out_dir"
if [[ ! -d "$source_dir/.git" ]]; then
    git clone --filter=blob:none https://github.com/ptitSeb/box64.git "$source_dir"
fi
git -C "$source_dir" fetch --depth 1 origin "$revision"
git -C "$source_dir" checkout --detach --force "$revision"
if [[ "$(git -C "$source_dir" rev-parse HEAD)" != "$revision" ]]; then
    printf 'Box64 source is not at the pinned revision %s\n' "$revision" >&2
    exit 1
fi
# Proton4A carries a small Wine compatibility patch on top of the pin.
for patch_file in "$patches_dir"/*.patch; do
    [[ -e "$patch_file" ]] || continue
    patch -p1 -d "$source_dir" < "$patch_file"
done

docker run --rm -v "$workdir:/work" debian:bookworm bash -lc '
    set -e
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
        cmake make gcc-aarch64-linux-gnu python3 git >/dev/null
    # Android has no /lib/ld-linux-aarch64.so.1, and Box64 re-execs itself to
    # run child processes (wineserver, wine). Point the ELF interpreter at the
    # on-device glibc loader and add an ORIGIN-relative rpath so Box64 can run
    # directly without a chroot.
    DEVICE_LOADER=/data/adb/proton4a/runtime/rootfs/lib/ld-linux-aarch64.so.1
    cmake -S /work/source -B /work/build \
        -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc \
        -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
        -DARM64=ON -DWINLATOR_GLIBC=ON -DARM_DYNAREC=ON \
        -DBAD_SIGNAL=ON -DBOX32=ON -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_EXE_LINKER_FLAGS="-Wl,--dynamic-linker=$DEVICE_LOADER -Wl,-rpath,\$ORIGIN/rootfs/lib/aarch64-linux-gnu:\$ORIGIN/rootfs/usr/lib/aarch64-linux-gnu:\$ORIGIN/rootfs/lib:\$ORIGIN/rootfs/usr/lib"
    cmake --build /work/build -j30 --target box64
    echo "=== interp / runpath ==="
    readelf -l /work/build/box64 | grep -A1 INTERP
    readelf -d /work/build/box64 | grep -iE "runpath|rpath" || true
'

install -m 0755 "$build_dir/box64" "$out_dir/box64"
printf 'Box64 staged at %s/box64 (source %s)\n' "$out_dir" "$revision"
