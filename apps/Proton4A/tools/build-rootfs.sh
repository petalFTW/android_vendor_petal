#!/usr/bin/env bash
# Build a minimal aarch64 glibc userspace for the Proton4A runtime.
#
# Android is Bionic and has no /lib/ld-linux-aarch64.so.1, so Box64 and the
# Proton Wine host libraries need their own glibc root. This extracts arm64
# Debian packages with dpkg-deb, which needs no arm64 execution.
set -euo pipefail

suite=bookworm
workspace="${PROTON4A_WORKSPACE:-$HOME/proton4a-build}"
out="$workspace/prebuilt/rootfs"
staging="$workspace/prebuilt/rootfs.next"

packages="
libc6 libgcc-s1 libstdc++6 zlib1g libtinfo6
libfreetype6 libfontconfig1 libexpat1 libpng16-16 libbz2-1.0 libbrotli1
libx11-6 libxext6 libxrender1 libxcb1 libxau6 libxdmcp6 libxrandr2
libxfixes3 libxi6 libxcursor1 libxinerama1 libxcomposite1 libxdamage1
libxshmfence1 libwayland-client0 libwayland-cursor0 libwayland-egl1
libasound2 libpulse0 libvulkan1 libdrm2
libselinux1 libpcre2-8-0 libcap2 libacl1 libattr1 libbsd0 libmd0
libuuid1 libcom-err2 libkeyutils1 libkrb5-3 libgssapi-krb5-2
libk5crypto3 libkrb5support0 libnettle8 libhogweed6 libidn2-0
libunistring2 libtasn1-6 libp11-kit0 libffi8 libgmp10 libgnutls30
libdbus-1-3 libglib2.0-0 libsndfile1 libflac12 libogg0 libvorbis0a
libvorbisenc2 libopus0 libmpg123-0 libudev1 libxml2 libsystemd0
xvfb xauth libx11-xcb1 libxcb-dri3-0 libxcb-present0 libxcb-sync1
libxcb-xfixes0 libxcb-randr0 libxcb-shm0 libxcb-render0 libxcb-shape0
libxkbcommon0 libwayland-server0 libffi8
libpixman-1-0 libfontenc1 libxfont2
libgcrypt20 libgpg-error0 libusb-1.0-0 fontconfig-config fonts-dejavu-core
libunwind8 liblzma5
libgl1 libglvnd0 libglx0 libglapi-mesa libglx-mesa0 libxcb-glx0 libxcb-dri2-0
libaudit1 libcap-ng0 libxkbfile1 libsensors5 libzstd1 libelf1 liblz4-1
x11-xkb-utils xkb-data
xdotool libxdo3 libxtst6
"

docker run --rm -v "$workspace:/work" \
    -e PACKAGES="$packages" -e USERID="$(id -u)" -e GROUPID="$(id -g)" \
    debian:$suite bash -lc '
    set -e
    dpkg --add-architecture arm64
    echo "deb [arch=arm64] http://deb.debian.org/debian bookworm main" \
        > /etc/apt/sources.list.d/arm64.list
    apt-get update -qq >/dev/null 2>&1
    rm -rf /debs /work/prebuilt/rootfs.next
    mkdir -p /debs /work/prebuilt/rootfs.next
    cd /debs
    # Fetch each arm64 .deb directly; the list above is the resolved closure.
    missing=""
    for p in $PACKAGES; do
        # arch:all packages only exist without a qualifier.
        apt-get download -qq "$p:arm64" 2>/dev/null \
            || apt-get download -qq "$p" 2>/dev/null \
            || missing="$missing $p"
    done
    if [ -n "$missing" ]; then
        echo "unavailable arm64 packages:$missing" >&2
        exit 1
    fi
    for deb in /debs/*.deb; do
        dpkg-deb -x "$deb" /work/prebuilt/rootfs.next
    done
    rm -rf /work/prebuilt/rootfs.next/var/lib/dpkg /work/prebuilt/rootfs.next/var/cache /work/prebuilt/rootfs.next/usr/share/doc
    # ldconfig cannot run here; scripts export LD_LIBRARY_PATH instead.
    rm -f /work/prebuilt/rootfs.next/etc/ld.so.cache
    test -e /work/prebuilt/rootfs.next/lib/ld-linux-aarch64.so.1
    test -x /work/prebuilt/rootfs.next/usr/bin/Xvfb
    chown -R $USERID:$GROUPID /work/prebuilt/rootfs.next
 '

# Debian's fontconfig-config ships absolute links to /usr/share. Rewrite them
# within the staged tree so Android's tar can safely unpack the runtime under
# /data/adb without trying to create host-absolute symlinks.
for link in "$staging/etc/fonts/conf.d/"*; do
    [[ -L "$link" ]] || continue
    target="$(readlink "$link")"
    [[ "$target" == /* ]] || continue
    relative="$(realpath --relative-to="$(dirname "$link")" "$staging$target")"
    ln -sfn "$relative" "$link"
done

rm -rf "$workspace/prebuilt/rootfs.previous"
if [ -d "$out" ]; then mv "$out" "$workspace/prebuilt/rootfs.previous"; fi
mv "$staging" "$out"
rm -rf "$workspace/prebuilt/rootfs.previous"
du -sh "$out"
printf 'Rootfs staged at %s\n' "$out"
