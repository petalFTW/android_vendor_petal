#!/usr/bin/env bash
# Build an ordinary signed Android launcher APK containing one Windows EXE.
# Usage: package-exe-apk.sh program.exe [output.apk]
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
tools="$sdk/build-tools/36.0.0"
platform="$sdk/platforms/android-36/android.jar"
workspace="${PROTON4A_WORKSPACE:-$HOME/proton4a-build}"
exe="${1:?usage: package-exe-apk.sh program.exe [output.apk] | --template output.apk}"
template=false
if [[ "$exe" == "--template" ]]; then
    template=true
    package="org.petalos.proton4a.program.p0000000000000000"
    name="Program placeholder"
    out="${2:?template output path required}"
else
    [[ -f "$exe" ]] || { printf 'missing executable: %s\n' "$exe" >&2; exit 1; }
    hash="$(sha256sum "$exe" | cut -d ' ' -f 1)"
    package="org.petalos.proton4a.program.p${hash:0:16}"
    name="$(basename "$exe")"
    name="${name%.[Ee][Xx][Ee]}"
    out="${2:-$workspace/prebuilt/$name.apk}"
fi
mkdir -p "$(dirname "$out")" "$workspace/keys"
out="$(realpath -m "$out")"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/assets" "$stage/classes" "$stage/res/drawable" "$stage/compiled"
if $template; then
    printf 'MZ' > "$stage/assets/program.exe"
else
    cp "$exe" "$stage/assets/program.exe"
fi
cp "$root/../PetalHub/res/drawable/ic_hub_proton4a.xml" \
    "$stage/res/drawable/ic_program.xml"
python3 - "$stage/AndroidManifest.xml" "$package" "$name" <<'PY'
import sys
from pathlib import Path
from xml.sax.saxutils import escape

target, package, name = sys.argv[1:]
Path(target).write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="{package}" android:versionCode="1" android:versionName="1.0">
    <application android:label="{escape(name, {'"': '&quot;'})}"
        android:icon="@drawable/ic_program" android:allowBackup="false">
        <activity android:name="org.petalos.proton4a.launcher.ProgramActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>''')
PY

javac -source 8 -target 8 -classpath "$platform" -d "$stage/classes" \
    "$root/launcher/src/org/petalos/proton4a/launcher/ProgramActivity.java"
"$tools/d8" --min-api 26 --lib "$platform" --output "$stage" \
    "$stage/classes/org/petalos/proton4a/launcher/ProgramActivity.class"
"$tools/aapt2" compile --dir "$stage/res" -o "$stage/compiled"
"$tools/aapt2" link -o "$stage/unsigned.apk" -I "$platform" \
    --manifest "$stage/AndroidManifest.xml" -A "$stage/assets" \
    -R "$stage/compiled/drawable_ic_program.xml.flat" \
    --min-sdk-version 26 --target-sdk-version 35
( cd "$stage" && zip -q unsigned.apk classes.dex )
"$tools/zipalign" -f 4 "$stage/unsigned.apk" "$stage/aligned.apk"
if $template; then
    cp "$stage/aligned.apk" "$out"
    printf 'Unsigned program template: %s\n' "$out"
    exit 0
fi

# This key signs ordinary launcher APKs only; it is never used for PetalHub or
# other privileged system packages. Keep it stable for APK updates on this PC.
keystore="$workspace/keys/program-launcher.jks"
if [[ ! -f "$keystore" ]]; then
    keytool -genkeypair -keystore "$keystore" -storepass changeit \
        -keypass changeit -alias proton4a-program -keyalg RSA -keysize 3072 \
        -validity 36500 -dname 'CN=Proton4A Program Launcher' -noprompt
    chmod 0600 "$keystore"
fi
"$tools/apksigner" sign --ks "$keystore" --ks-key-alias proton4a-program \
    --ks-pass pass:changeit --key-pass pass:changeit --out "$out" "$stage/aligned.apk"
"$tools/apksigner" verify "$out"
printf 'Program APK: %s (%s)\n' "$out" "$package"
