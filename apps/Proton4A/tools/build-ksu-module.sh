#!/usr/bin/env bash
# Package the Proton4A KernelSU/Magisk test module.
#
# By default the module ships only scripts and the `proton4a` helper; push the
# runtime payload separately. Set PROTON4A_KSU_WITH_RUNTIME=1 to bundle
# prebuilt/proton4a-runtime.tar.gz inside the zip.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
workspace="${PROTON4A_WORKSPACE:-$HOME/proton4a-build}"
if [[ "${PROTON4A_KSU_WITH_RUNTIME:-0}" == "1" ]]; then
    default_name=proton4a-ksu-full.zip
else
    default_name=proton4a-ksu.zip
fi
out="${PROTON4A_KSU_OUT:-$workspace/prebuilt/$default_name}"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT

cp -a "$root/ksu/." "$stage/"
chmod 0755 "$stage/system/bin/proton4a" "$stage/customize.sh" \
    "$stage/post-fs-data.sh" "$stage/service.sh"

# Ship a platform-signed PetalHub update as an ordinary APK update under
# /data/app. The module does not overlay the ROM's privileged APK.
if [[ -n "${PROTON4A_KSU_HUB_APK:-}" ]]; then
    [[ -f "$PROTON4A_KSU_HUB_APK" ]] || { printf 'missing Hub APK: %s\n' "$PROTON4A_KSU_HUB_APK" >&2; exit 1; }
    mkdir -p "$stage/hub"
    cp "$PROTON4A_KSU_HUB_APK" "$stage/hub/PetalHub.apk"
fi

if [[ "${PROTON4A_KSU_WITH_RUNTIME:-0}" == "1" ]]; then
    payload="$workspace/prebuilt/proton4a-runtime.tar.gz"
    [[ -f "$payload" ]] || { printf 'missing payload: %s\n' "$payload" >&2; exit 1; }
    mkdir -p "$stage/runtime"
    cp "$payload" "$stage/runtime/proton4a-runtime.tar.gz"
fi

mkdir -p "$(dirname "$out")"
rm -f "$out"
if command -v zip >/dev/null 2>&1; then
    ( cd "$stage" && zip -qr -1 "$out" . )
else
    python3 - "$stage" "$out" <<'PY'
import os, sys, zipfile
stage, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
    for base, _, files in os.walk(stage):
        for name in files:
            path = os.path.join(base, name)
            archive.write(path, os.path.relpath(path, stage))
PY
fi

printf 'KernelSU module: %s (%s bytes)\n' "$out" "$(stat -c %s "$out")"
