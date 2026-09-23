import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

source = Path(__file__).resolve().parent
root = source.parents[3]
sdk = Path(os.environ.get("ANDROID_SDK_ROOT", "/home/rab1d/Android/Sdk"))
if not (sdk / "build-tools/36.0.0/aapt2").is_file():
    sdk = Path.home() / "Android/Sdk"
build_tools = sdk / "build-tools/36.0.0"
android_jar = sdk / "platforms/android-36/android.jar"
output = Path(tempfile.mkdtemp(prefix="petal-haptics-preview-"))
assets = output / "assets"
assets.mkdir()
shutil.copy(root / "frameworks/base/core/res/res/raw/petal_richtap_patterns.json", assets)
classes = output / "classes"
classes.mkdir()


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


run(build_tools / "aapt2", "link", "-I", android_jar, "--manifest",
    source / "AndroidManifest.xml", "-A", assets, "-o", output / "unsigned.apk")
run("javac", "--release", "17", "-classpath", android_jar, "-d", classes,
    source / "MainActivity.java",
    root / "frameworks/base/services/core/java/com/android/server/vibrator/PetalRichTapPattern.java")
run(build_tools / "d8", "--lib", android_jar, "--min-api", "31", "--output", output,
    *sorted(classes.rglob("*.class")))
with zipfile.ZipFile(output / "unsigned.apk", "a") as apk:
    apk.write(output / "classes.dex", "classes.dex")
run(build_tools / "zipalign", "-f", "4", output / "unsigned.apk", output / "aligned.apk")
keystore = Path(os.environ.get("PETAL_PREVIEW_KEYSTORE", output / "preview.jks"))
if not keystore.exists():
    run("keytool", "-genkeypair", "-keystore", keystore, "-storepass", "android",
        "-keypass", "android", "-alias", "preview", "-keyalg", "RSA", "-validity", "365",
        "-dname", "CN=Petal Haptics Preview")
run(build_tools / "apksigner", "sign", "--ks", keystore, "--ks-pass",
    "pass:android", "--out", output / "PetalHapticsPreview.apk", output / "aligned.apk")
run(build_tools / "apksigner", "verify", output / "PetalHapticsPreview.apk")
print(output / "PetalHapticsPreview.apk")
