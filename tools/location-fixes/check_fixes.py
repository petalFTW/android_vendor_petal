#!/usr/bin/env python3
# Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0
import argparse
import pathlib
import re
import subprocess
import tempfile
import urllib.request
import xml.etree.ElementTree as ET

HERE = pathlib.Path(__file__).resolve().parent
TOP = HERE.parents[3]
JAVA = TOP / "prebuilts/jdk/jdk21/linux-x86/bin"
ANDROID = TOP / "prebuilts/misc/common/robolectric/android-all/android-all-13-robolectric-9030017.jar"
MICROG_PATH = "play-services-location/core/src/main/kotlin/org/microg/gms/location/manager/LocationRequestManager.kt"
MICROG_URL = f"https://raw.githubusercontent.com/microg/GmsCore/v0.3.17.252432/{MICROG_PATH}"


def method(source, signature):
    start = source.index(signature)
    brace = source.index("{", start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


def check_permission(work):
    source = (TOP / "frameworks/base/core/java/android/app/ContextImpl.java").read_text()
    body = method(source, "public int checkPermission(String permission, int pid, int uid)")
    body = body.replace("android.os.Process.", "Process.")
    fixture = (HERE / "PermissionBridgeHarness.java.in").read_text().replace("METHOD_BODY", body)
    target = work / "PermissionBridgeHarness.java"
    target.write_text(fixture)
    subprocess.run([str(JAVA / "javac"), "-cp", str(ANDROID), str(target)], check=True)
    subprocess.run([str(JAVA / "java"), "-cp", f"{work}:{ANDROID}",
                    "PermissionBridgeHarness"], check=True)


def check_resources():
    values = ET.parse(TOP / "packages/apps/Settings/res/values/petal_spoofed_state.xml").getroot()
    names = {item.attrib["name"] for item in values}
    screen = ET.parse(TOP / "packages/apps/Settings/res/xml/petal_spoofed_state.xml").getroot()
    for item in screen.iter():
        for value in item.attrib.values():
            if value.startswith(("@string/", "@array/")):
                assert value.split("/", 1)[1] in names, value
    assert len(values.find("string-array[@name='petal_spoof_modes']")) == 4
    assert [item.text for item in values.find(
        "string-array[@name='petal_spoof_mode_values']")] == ["0", "1", "2", "3"]
    fragment = (TOP / "packages/apps/Settings/src/com/android/settings/applications/appinfo/SpoofedStateSettings.java").read_text()
    for name in re.findall(r"R\.string\.(petal_spoof_\w+)", fragment):
        assert name in names, name
    print("Settings XML and mode compatibility: passed", flush=True)


def check_microg(work, local):
    source = local.read_text() if local else urllib.request.urlopen(MICROG_URL, timeout=30).read().decode()
    target = work / MICROG_PATH
    target.parent.mkdir(parents=True)
    target.write_text(source)
    subprocess.run(["patch", "--batch", "--fuzz=0", "-p1", "-i",
                    str(HERE / "microg-0.3.17.252432-location-stop.patch")], cwd=work, check=True)
    patched = target.read_text()
    fixture = (HERE / "LocationStopHarness.kt.in").read_text()
    fixture = fixture.replace("STOP_BODY", method(patched, "fun stop()"))
    fixture = fixture.replace("UPDATE_BODY", method(patched, "private fun updateAppOps()"))
    kotlin = work / "LocationStopHarness.kt"
    kotlin.write_text(fixture)
    jar = work / "location-stop.jar"
    subprocess.run([str(TOP / "external/kotlinc/bin/kotlinc"), str(kotlin),
                    "-include-runtime", "-d", str(jar)], check=True)
    subprocess.run([str(JAVA / "java"), "-jar", str(jar)], check=True)
    original_fixture = fixture.replace(method(patched, "fun stop()"), method(source, "fun stop()"))
    kotlin.write_text(original_fixture)
    subprocess.run([str(TOP / "external/kotlinc/bin/kotlinc"), str(kotlin),
                    "-include-runtime", "-d", str(jar)], check=True)
    original = subprocess.run([str(JAVA / "java"), "-jar", str(jar)],
                              text=True, capture_output=True)
    assert original.returncode != 0 and "location operation leaked after stop" in original.stderr
    print("Unpatched microG reproduces the operation leak in the same harness", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--microg-source", type=pathlib.Path,
                        help="Use a local unpatched LocationRequestManager.kt instead of downloading")
    args = parser.parse_args()
    check_resources()
    with tempfile.TemporaryDirectory(prefix="location-fixes-", dir="/tmp/opencode") as temp:
        work = pathlib.Path(temp)
        check_permission(work)
        check_microg(work, args.microg_source)
