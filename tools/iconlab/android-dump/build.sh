#!/usr/bin/env bash
# Build the icon dump APK on Linux/macOS. On Windows use Git Bash/WSL or the
# prebuilt icondump.apk in this folder.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
BT="$SDK/build-tools/36.0.0"
AJ="$SDK/platforms/android-36/android.jar"
JAVAC="${JAVAC:-javac}"
mkdir -p build/classes build/dex
rm -rf build/classes/* build/dex/*
"$JAVAC" -source 17 -target 17 -classpath "$AJ" -d build/classes $(find src -name '*.java')
"$BT/d8" --lib "$AJ" --output build/dex $(find build/classes -name '*.class')
"$BT/aapt2" link -o build/unsigned.apk -I "$AJ" --manifest AndroidManifest.xml
(cd build && zip -uj unsigned.apk dex/classes.dex >/dev/null)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
  --key-pass pass:android --ks-key-alias androiddebugkey \
  --out build/icondump.apk build/aligned.apk
echo "built $(pwd)/build/icondump.apk"
