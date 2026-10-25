#!/usr/bin/env bash
# Build the tiny glibc uid shim loaded into Xvfb (see uid-shim.c).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
workspace="${PROTON4A_WORKSPACE:-$HOME/proton4a-build}"
out="$workspace/prebuilt/uid-shim"
mkdir -p "$out"
cp "$here/uid-shim.c" "$here/soften.c" "$here/time-probe.c" \
    "$here/runtime-app/xkb-launch.c" "$out/"
docker run --rm -v "$workspace/prebuilt/uid-shim:/work" debian:bookworm bash -lc '
    set -e
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq gcc-aarch64-linux-gnu >/dev/null
    aarch64-linux-gnu-gcc -shared -fPIC -O2 -o /work/libuidshim.so /work/uid-shim.c
    aarch64-linux-gnu-gcc -shared -fPIC -O2 -o /work/libsoften.so /work/soften.c
    aarch64-linux-gnu-gcc -shared -fPIC -O2 -o /work/libtimeprobe.so /work/time-probe.c
    aarch64-linux-gnu-gcc -O2 -Wl,--dynamic-linker=/data/user/0/org.petalos.proton4a/f/ld.so \
        -o /work/libxkb-launch.so /work/xkb-launch.c
'
printf 'shims: %s/libuidshim.so %s/libsoften.so\n' "$out" "$out"
