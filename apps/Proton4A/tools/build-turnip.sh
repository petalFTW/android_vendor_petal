#!/usr/bin/env bash
# Cross-build Mesa's freedreno Vulkan driver (Turnip) for aarch64 glibc, with
# X11 and Wayland window-system integration so DXVK/vkd3d can present.
#
# Turnip talks to the Adreno GPU through Android's KGSL kernel interface, so a
# glibc Wine process can use it with no Bionic dependency. The KGSL patches are
# vendored from WinNative-Emu/WinNative (tools/linuxfs/turnip) and remain
# under Mesa's MIT license; see tools/turnip/NOTICE.
set -euo pipefail

version=26.2.2
sha256=eeb29ca7e56cfaa8e8a79538dcf834e3b18e501c31bef5145e959ea437cc4216
workspace="${PROTON4A_WORKSPACE:-$HOME/proton4a-build}"
work="$workspace/upstream"
src="$work/mesa-$version"
out="$workspace/prebuilt/turnip"
sysroot_in_container=/work/upstream/turnip-sysroot
cross_file="$work/aarch64-meson-cross.txt"
patches="$(cd "$(dirname "$0")" && pwd)/turnip"

# Mesa dev/runtime packages used to build the window-system integration.
dev_packages="
libx11-dev libx11-xcb-dev libxcb1-dev libxau-dev libxdmcp-dev x11proto-dev
libxcb-shm0-dev libxcb-dri3-dev libxcb-present-dev libxcb-sync-dev
libxcb-xfixes0-dev libxcb-randr0-dev libxcb-render0-dev libxcb-shape0-dev libxshmfence-dev
libwayland-dev wayland-protocols libffi-dev
"
rt_packages="
libx11-6 libx11-xcb1 libxcb1 libxau6 libxdmcp6 libxcb-shm0
libxcb-dri3-0 libxcb-present0 libxcb-sync1 libxcb-xfixes0 libxcb-randr0
libxcb-render0 libxcb-shape0 libxshmfence1 libwayland-client0 libffi8
"

mkdir -p "$work" "$out"
if [[ ! -f "$src/.patched" ]]; then
    if [[ ! -s "$work/mesa-$version.tar.xz" ]]; then
        curl -fsSLo "$work/mesa-$version.tar.xz" \
            "https://archive.mesa3d.org/mesa-$version.tar.xz"
    fi
    echo "$sha256  $work/mesa-$version.tar.xz" | sha256sum -c --quiet
    rm -rf "$src"
    tar -xJf "$work/mesa-$version.tar.xz" -C "$work"
    for patch_file in "$patches"/*.patch; do
        patch -d "$src" -p1 < "$patch_file"
    done
    touch "$src/.patched"
fi

# Written on the host so Meson gets real single quotes; paths are the
# container's view of the mounted workspace.
cat > "$cross_file" <<EOF
[binaries]
c = 'aarch64-linux-gnu-gcc'
cpp = 'aarch64-linux-gnu-g++'
ar = 'aarch64-linux-gnu-ar'
strip = 'aarch64-linux-gnu-strip'
pkg-config = 'pkg-config'

[properties]
sys_root = '$sysroot_in_container'
pkg_config_libdir = '$sysroot_in_container/usr/lib/aarch64-linux-gnu/pkgconfig:$sysroot_in_container/usr/share/pkgconfig'

[built-in options]
c_args = ['-I$sysroot_in_container/usr/include']
cpp_args = ['-I$sysroot_in_container/usr/include']
c_link_args = ['-L$sysroot_in_container/usr/lib/aarch64-linux-gnu']
cpp_link_args = ['-L$sysroot_in_container/usr/lib/aarch64-linux-gnu']

[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF

docker run --rm -v "$workspace:/work" \
    -e USERID="$(id -u)" -e GROUPID="$(id -g)" \
    -e DEV_PACKAGES="$dev_packages" -e RT_PACKAGES="$rt_packages" \
    -e MESA_VERSION="$version" \
    debian:trixie bash -lc '
    set -e
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
        meson ninja-build gcc-aarch64-linux-gnu g++-aarch64-linux-gnu \
        libc6-dev-arm64-cross libstdc++-14-dev-arm64-cross \
        binutils-aarch64-linux-gnu python3 python3-mako \
        python3-yaml bison flex glslang-tools patchelf pkg-config \
        libwayland-bin >/dev/null

    # Overlay only the X11/Wayland packages; libc and libstdc++ come from the
    # cross toolchain sysroot.
    sysroot=/work/upstream/turnip-sysroot
    rm -rf "$sysroot" /debs
    mkdir -p "$sysroot/usr/include" \
        "$sysroot/usr/lib/aarch64-linux-gnu" "$sysroot/usr/share/pkgconfig" /debs

    dpkg --add-architecture arm64
    echo "deb [arch=arm64] http://deb.debian.org/debian trixie main" \
        > /etc/apt/sources.list.d/arm64.list
    apt-get update -qq
    cd /debs
    missing=""
    for p in $DEV_PACKAGES $RT_PACKAGES; do
        apt-get download -qq "$p:arm64" 2>/dev/null \
            || apt-get download -qq "$p" 2>/dev/null \
            || missing="$missing $p"
    done
    if [ -n "$missing" ]; then
        echo "unavailable arm64 packages:$missing" >&2
        exit 1
    fi
    for deb in /debs/*.deb; do
        dpkg-deb -x "$deb" "$sysroot"
    done

    rm -rf /work/prebuilt/turnip /work/upstream/mesa-build
    mkdir -p /work/prebuilt/turnip
    meson setup /work/upstream/mesa-build "/work/upstream/mesa-$MESA_VERSION" \
        --cross-file /work/upstream/aarch64-meson-cross.txt \
        --buildtype=release \
        -Dvulkan-drivers=freedreno \
        -Dfreedreno-kmds=kgsl \
        -Dgallium-drivers= \
        -Dplatforms=x11,wayland \
        -Dopengl=false \
        -Dglx=disabled \
        -Degl=disabled \
        -Dgbm=disabled \
        -Dllvm=disabled \
        -Dshared-glapi=disabled \
        -Dbuild-tests=false \
        -Dvalgrind=disabled \
        -Dvulkan-beta=true
    ninja -C /work/upstream/mesa-build -j30 \
        src/freedreno/vulkan/libvulkan_freedreno.so
    aarch64-linux-gnu-strip -o /work/prebuilt/turnip/libvulkan_freedreno.so \
        /work/upstream/mesa-build/src/freedreno/vulkan/libvulkan_freedreno.so
    chown -R $USERID:$GROUPID /work/prebuilt/turnip
'

driver="$out/libvulkan_freedreno.so"
test -f "$driver" || {
    printf 'Turnip driver was not produced\n' >&2
    exit 1
}
mkdir -p "$out/usr/lib/aarch64-linux-gnu" "$out/usr/share/vulkan/icd.d"
mv "$driver" "$out/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so"
cat > "$out/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json" <<'JSON'
{
    "file_format_version": "1.0.1",
    "ICD": {
        "library_arch": "64",
        "library_path": "libvulkan_freedreno.so",
        "api_version": "1.4.354"
    }
}
JSON

printf 'Turnip (X11/Wayland WSI) staged:\n'
find "$out" -type f | sort
