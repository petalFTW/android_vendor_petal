#!/usr/bin/env bash
# A rootless, unprivileged syscall trace APK for the pinned Box64 build.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
tools="$sdk/build-tools/36.0.0"
platform="$sdk/platforms/android-36/android.jar"
workspace="${PROTON4A_WORKSPACE:-$HOME/proton4a-build}"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/classes" "$stage/lib/arm64-v8a" "$stage/assets"
cat > "$stage/hello.c" <<'EOF'
#include <stdio.h>
int main(void) { printf("hello-from-x86-64-elf\n"); return 0; }
EOF
gcc -static -O2 -o "$stage/assets/x64-hello" "$stage/hello.c"

"$sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang" \
    -O2 "$here/trace-sigsys.c" -o "$stage/lib/arm64-v8a/libtraceprobe.so"
cp "$workspace/prebuilt/rootfs/lib/ld-linux-aarch64.so.1" \
    "$stage/lib/arm64-v8a/libglibcprobe.so"
python3 "$here/patch-loader.py" "$stage/lib/arm64-v8a/libglibcprobe.so"
cp "$workspace/prebuilt/box64" "$stage/lib/arm64-v8a/libbox64probe.so"
# Point Box64's ELF interpreter at a fixed app-storage path so its self
# re-exec (wineserver, services) works for an unprivileged app without root.
python3 - "$stage/lib/arm64-v8a/libbox64probe.so" \
    "/data/user/0/org.petalos.proton4a.trace/f/ld.so" <<'PY'
import sys
path, new = sys.argv[1], sys.argv[2]
data = bytearray(open(path, "rb").read())
old = b"/data/adb/proton4a/runtime/rootfs/lib/ld-linux-aarch64.so.1\x00"
i = data.find(old)
assert i != -1, "old interpreter not found"
assert len(new) + 1 <= len(old), "new interpreter longer than the original"
data[i:i + len(old)] = new.encode() + b"\x00" * (len(old) - len(new))
open(path, "wb").write(data)
print("interpreter set to", new)
PY
for entry in 'libc.so.6 libp4alibc.so' 'libm.so.6 libp4alibm.so' 'libresolv.so.2 libp4aresolv.so'; do
    read -r source target <<< "$entry"
    cp "$workspace/prebuilt/rootfs/lib/aarch64-linux-gnu/$source" \
        "$stage/lib/arm64-v8a/$target"
done

javac -source 8 -target 8 -classpath "$platform" -d "$stage/classes" \
    "$here/TraceActivity.java"
"$tools/d8" --min-api 26 --lib "$platform" --output "$stage" \
    "$stage/classes/org/petalos/proton4a/trace/TraceActivity.class"
"$tools/aapt2" link -o "$stage/unsigned.apk" -I "$platform" \
    --manifest "$here/AndroidManifest.xml" -A "$stage/assets" \
    --min-sdk-version 26 --target-sdk-version 35
( cd "$stage" && zip -q unsigned.apk classes.dex && \
    zip -q -0 unsigned.apk lib/arm64-v8a/*.so )
"$tools/zipalign" -f 4 "$stage/unsigned.apk" "$stage/aligned.apk"
keystore="$workspace/keys/program-launcher.jks"
[[ -f "$keystore" ]] || { printf 'missing test APK key: %s\n' "$keystore" >&2; exit 1; }
output="$workspace/prebuilt/proton4a-sigsys-probe.apk"
"$tools/apksigner" sign --ks "$keystore" --ks-key-alias proton4a-program \
    --ks-pass pass:changeit --key-pass pass:changeit \
    --out "$output" "$stage/aligned.apk"
"$tools/apksigner" verify "$output"
printf 'Rootless trace APK: %s\n' "$output"
