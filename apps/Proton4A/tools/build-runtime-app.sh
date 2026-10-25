#!/usr/bin/env bash
# Build the rootless Proton4A runtime host app.
#
# A normal (untrusted) app with a fixed package name. It ships the ARM64
# executables in the APK's native library directory and depends on the fixed
# app-storage interpreter path baked into those binaries, so Box64 can re-exec
# itself for Wine child processes without root or mounts.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
tools="$sdk/build-tools/36.0.0"
platform="$sdk/platforms/android-36/android.jar"
workspace="${PROTON4A_WORKSPACE:-$HOME/proton4a-build}"

package="org.petalos.proton4a"
interp="/data/user/0/$package/f/ld.so"
out="${PROTON4A_APP_OUT:-$workspace/prebuilt/Proton4A.apk}"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/classes" "$stage/lib/arm64-v8a" "$stage/res/drawable" "$stage/compiled" "$stage/assets"
cp -a "$workspace/prebuilt/rootfs/usr/share/X11/xkb" "$stage/assets/xkb"
python3 "$here/generate-loading-gif.py" "$stage/assets/loading.gif"
bash "$here/package-exe-apk.sh" --template "$stage/assets/program-template.apk"
# Ordinary, non-privileged program APKs have their own signing identity. Keep
# the runtime APK signing key out of the on-device packager.
signing="$workspace/keys/program-apk-signing"
if [[ ! -f "$signing.pem" || ! -f "$signing.crt" ]]; then
    mkdir -p "$workspace/keys"
    openssl req -x509 -newkey rsa:3072 -nodes -days 36500 \
        -keyout "$signing.pem" -out "$signing.crt" \
        -subj '/CN=Proton4A Program APKs' >/dev/null 2>&1
    chmod 600 "$signing.pem"
fi
openssl pkcs8 -topk8 -nocrypt -in "$signing.pem" -outform DER \
    -out "$stage/assets/program-signing-key.pk8"
openssl x509 -in "$signing.crt" -outform DER \
    -out "$stage/assets/program-signing-cert.der"

native="$stage/lib/arm64-v8a"
loader="$native/libp4aldr.so"
box64="$native/libbox64.so"

# 1. Patched glibc loader (skips Android-trapped startup syscalls).
cp "$workspace/prebuilt/rootfs/lib/ld-linux-aarch64.so.1" "$loader"
python3 "$here/patch-loader.py" "$loader"

# 2. Box64 with its interpreter pinned to the fixed app-storage path.
cp "$workspace/prebuilt/box64" "$box64"
python3 - "$box64" "$interp" <<'PY'
import sys
path, new = sys.argv[1], sys.argv[2]
data = bytearray(open(path, "rb").read())
old = b"/data/adb/proton4a/runtime/rootfs/lib/ld-linux-aarch64.so.1\x00"
i = data.find(old)
assert i != -1, "old Box64 interpreter not found; rebuild Box64 first"
assert len(new) + 1 <= len(old), "new interpreter path is too long"
data[i:i + len(old)] = new.encode() + b"\x00" * (len(old) - len(new))
open(path, "wb").write(data)
print("Box64 interpreter ->", new)
PY

# 3. Xvfb, xdotool and the glibc/X11 shared libraries, flattened by soname.
copy_rootfs_libs() {
    local dir="$1"
    [ -d "$dir" ] || return 0
    find "$dir" -maxdepth 1 \( -type f -o -type l \) -name '*.so*' | while read -r f; do
        local base; base="$(basename "$f")"
        [ -e "$native/$base" ] && continue
        cp -aL "$f" "$native/$base"
    done
}
cp "$workspace/prebuilt/rootfs/usr/bin/Xvfb" "$native/libXvfb.so"
cp "$workspace/prebuilt/rootfs/usr/bin/xdotool" "$native/libxdotool.so"
cp "$workspace/prebuilt/rootfs/usr/bin/xkbcomp" "$native/libxkbcomp.so" 2>/dev/null || \
    cp "$workspace/prebuilt/rootfs/usr/bin/xkbcomp.real" "$native/libxkbcomp.so"
cp "$workspace/prebuilt/uid-shim/libuidshim.so" "$native/libuidshim.so"
cp "$workspace/prebuilt/uid-shim/libsoften.so" "$native/libsoften.so"
cp "$workspace/prebuilt/uid-shim/libtimeprobe.so" "$native/libtimeprobe.so"
# Launcher symlinked as xkbcomp inside app storage: a script there cannot be
# exec'd, so this native ELF re-execs the bundled xkbcomp through the loader.
cp "$workspace/prebuilt/uid-shim/libxkb-launch.so" "$native/libxkb-launch.so"
# Diagnostic: verify Unix-socket + credential passing works in the app sandbox.
"$sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang" \
    -O2 -static -o "$native/libsocktest.so" "$here/runtime-app/socktest.c"
# Diagnostic: minimal syscall tracer for app-vs-root differential debugging.
"$sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang" \
    -O2 -static -o "$native/libsystrace.so" "$here/runtime-app/systrace.c"
# Xvfb invokes xkbcomp through the shell as "<xkb_bindir>/xkbcomp". Point that
# at the current directory so a wrapper can run the bundled, non-Bionic
# xkbcomp through the loader.
python3 - "$native/libXvfb.so" <<'PY'
import sys
path = sys.argv[1]
data = bytearray(open(path, "rb").read())
old = b"/usr/bin\x00"
assert data.count(old) == 1, "unexpected Xvfb xkb bindir"
i = data.find(old)
data[i:i + len(old)] = b".\x00" + b"\x00" * (len(old) - 2)
# Android has no writable /tmp; point the server lock directory at "." so it
# is created in the app's writable working directory. The literal is NUL
# padded, so shortening it in place is safe.
# Android has no writable /tmp, /var/tmp or /run/shm. Redirect each literal
# directory to "." so the lock and temp files land in the app's writable
# working directory. Every literal is NUL terminated, so shortening it in
# place (padding with NULs to the same length) is safe.
for literal in (b"/run/shm", b"/var/tmp", b"/tmp/", b"/tmp"):
    while True:
        i = data.find(literal + b"\x00")
        if i < 0:
            break
        data[i:i + len(literal)] = b"." + b"\x00" * (len(literal) - 1)
open(path, "wb").write(data)
print("Xvfb bindir/lock/temp dirs redirected to '.'")
PY
copy_rootfs_libs "$workspace/prebuilt/rootfs/lib/aarch64-linux-gnu"
copy_rootfs_libs "$workspace/prebuilt/rootfs/usr/lib/aarch64-linux-gnu"

# This ROM's app sandbox denies creating AF_INET sockets, so the display runs
# over a Unix socket. Make the X11 socket directory relative to the working
# directory (which the app sets) instead of the unwritable /tmp.
python3 - "$native" <<'PY'
import sys
from pathlib import Path
native = Path(sys.argv[1])
for path in (*native.glob("libxcb.so*"), native / "libXvfb.so"):
    if not path.is_file():
        continue
    data = bytearray(path.read_bytes())
    for old, new in ((b"/tmp/.X11-unix/X", b".X11-unix/X"),
                     (b"/tmp/.X11-unix", b".X11-unix")):
        while True:
            i = data.find(old + b"\x00")
            if i < 0:
                break
            data[i:i + len(old)] = new + b"\x00" * (len(old) - len(new))
    path.write_bytes(data)
    print("relative X11 socket dir in", path.name)
PY

# Turnip driver (loaded by Box64's Vulkan wrapper).
cp "$workspace/prebuilt/turnip/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so" \
    "$native/libvulkan_freedreno.so" 2>/dev/null || true

# Neutralise set_robust_list/rseq in the bundled glibc (libc.so.6, ...), which
# Android's app seccomp terminates on and cannot be caught.
python3 "$here/patch-glibc.py" "$native" | tail -1

# 4. Java + manifest.
cp "$here/runtime-app/RuntimeActivity.java" "$here/runtime-app/XwdFramebuffer.java" \
    "$here/runtime-app/DisplayView.java" "$here/runtime-app/ProgramApk.java" \
    "$here/runtime-app/ImportActivity.java" "$here/runtime-app/InstallResultActivity.java" \
    "$stage/"
# Reuse the core tar reader in this app's package.
sed 's/^package org.petalos.proton4a;/package org.petalos.proton4a.runtime;/' \
    "$root/src/org/petalos/proton4a/TarArchive.java" > "$stage/TarArchive.java"
sed -e "s/@PACKAGE@/$package/g" -e "s#@INTERP@#$interp#g" \
    "$here/runtime-app/AndroidManifest.xml" > "$stage/AndroidManifest.xml"
sed -e "s#@INTERP@#$interp#g" -i "$stage/RuntimeActivity.java"
javac -source 8 -target 8 -classpath "$platform:$tools/lib/apksigner.jar" -d "$stage/classes" \
    "$stage/RuntimeActivity.java" "$stage/XwdFramebuffer.java" \
    "$stage/DisplayView.java" "$stage/TarArchive.java" "$stage/ProgramApk.java" \
    "$stage/ImportActivity.java" "$stage/InstallResultActivity.java"
"$tools/d8" --min-api 26 --lib "$platform" --output "$stage" \
    "$stage/classes/org/petalos/proton4a/runtime/"*.class \
    "$tools/lib/apksigner.jar"

cp "$root/../PetalHub/res/drawable/ic_hub_proton4a.xml" "$stage/res/drawable/ic_program.xml"
"$tools/aapt2" compile --dir "$stage/res" -o "$stage/compiled"
"$tools/aapt2" link -o "$stage/unsigned.apk" -I "$platform" \
    --manifest "$stage/AndroidManifest.xml" -A "$stage/assets" \
    -R "$stage/compiled/drawable_ic_program.xml.flat" \
    --min-sdk-version 26 --target-sdk-version 35
( cd "$stage" && zip -q unsigned.apk classes.dex && zip -q -0 unsigned.apk lib/arm64-v8a/* )
"$tools/zipalign" -f 4 "$stage/unsigned.apk" "$stage/aligned.apk"

keystore="$workspace/keys/program-launcher.jks"
[[ -f "$keystore" ]] || { printf 'missing key: %s\n' "$keystore" >&2; exit 1; }
"$tools/apksigner" sign --ks "$keystore" --ks-key-alias proton4a-program \
    --ks-pass pass:changeit --key-pass pass:changeit \
    --out "$out" "$stage/aligned.apk"
"$tools/apksigner" verify "$out"
printf 'Runtime app: %s (%s)\n' "$out" "$package"
