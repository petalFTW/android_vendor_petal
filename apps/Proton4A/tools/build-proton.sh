#!/usr/bin/env bash
# Build the pinned upstream Proton redist that Proton4A runs through Box64.
#
# Proton ships an x86-64 Linux userspace (Wine, DXVK, vkd3d-proton and the
# `proton` launcher). Proton4A does not fork it: this builds a tagged upstream
# release unchanged, inside Valve's Steam Runtime SDK container, and records
# the exact source revision in <redist>/version.
set -euo pipefail

tag=proton-11.0-2
build_name=proton4a
# Build artifacts live outside the ROM tree so the Android build never scans
# vendored sources for Android.mk files.
workspace="${PROTON4A_WORKSPACE:-$HOME/proton4a-build}"
src="${PROTON4A_PROTON_SRC:-$workspace/upstream/proton}"
build_dir="$src/build/build-$build_name"
redist="$workspace/upstream/$tag-redist"

if [[ ! -d "$src/.git" ]]; then
    git clone --filter=blob:none --branch proton_11.0 \
        https://github.com/ValveSoftware/Proton.git "$src"
fi
git -C "$src" fetch --depth 1 origin "refs/tags/$tag:refs/tags/$tag"
git -C "$src" checkout --detach "refs/tags/$tag"
git -C "$src" submodule update --init --recursive --depth 1 --jobs 8

if [[ ! -e "$build_dir/Makefile" ]]; then
    mkdir -p "$build_dir"
    ( cd "$build_dir" && ../../configure.sh --build-name="$build_name" )
fi

# CCACHE_DISABLE avoids ccache permission errors from the bind-mounted home.
CCACHE_DISABLE=1 make -C "$build_dir" redist -j30

mkdir -p "$redist"
cp -a "$build_dir/redist/." "$redist/"
printf 'Proton staged at %s (%s)\n' "$redist" "$(cat "$redist/version")"
