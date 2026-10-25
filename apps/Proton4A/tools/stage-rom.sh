#!/usr/bin/env bash
# Stage the Proton4A runtime host APK for the ROM build.
#
#   tools/stage-rom.sh [Proton4A.apk]
#
# Defaults to $PROTON4A_WORKSPACE/prebuilt/Proton4A.apk (see
# tools/build-runtime-app.sh). Copies the APK into rom/prebuilt and enables the
# Proton4ARuntime Soong module, which the petal product makefiles pick up.
set -euo pipefail

module_root="$(cd "$(dirname "$0")/.." && pwd)"
workspace="${PROTON4A_WORKSPACE:-$HOME/proton4a-build}"
apk="${1:-$workspace/prebuilt/Proton4A.apk}"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
aapt="$sdk_root/build-tools/36.0.0/aapt2"

[[ -f "$apk" ]] || { printf 'missing runtime host APK: %s\n' "$apk" >&2; exit 1; }

badging="$("$aapt" dump badging "$apk")"
if [[ "$badging" != *"package: name='org.petalos.proton4a'"* ]]; then
    printf 'APK is not the Proton4A runtime host: %s\n' "$apk" >&2
    exit 1
fi
if [[ "$badging" == *application-debuggable* ]]; then
    printf '%s\n' 'Refusing to stage a debuggable APK for the ROM.' >&2
    exit 1
fi

mkdir -p "$module_root/rom/prebuilt"
cp "$apk" "$module_root/rom/prebuilt/Proton4A.apk"
cp "$module_root/rom/Android.bp.template" "$module_root/rom/Android.bp"
printf '%s\n' 'Staged the Proton4A runtime host (org.petalos.proton4a) for the ROM build.'
