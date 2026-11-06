#!/usr/bin/env python3
# Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0
"""Capture comparable idle endpoints without resetting stats or changing device policy."""

import argparse
import datetime
import json
import pathlib
import subprocess
import sys
import time


COMMANDS = {
    "battery": ["dumpsys", "battery"],
    "batterystats": ["dumpsys", "batterystats"],
    "batterystats-checkin": ["dumpsys", "batterystats", "--checkin"],
    "power": ["dumpsys", "power"],
    "deviceidle": ["dumpsys", "deviceidle"],
    "alarms": ["dumpsys", "alarm"],
    "jobs": ["dumpsys", "jobscheduler"],
    "bluetooth": ["dumpsys", "bluetooth_manager"],
    "thermal": ["dumpsys", "thermalservice"],
    "suspend": ["dumpsys", "suspend_control_internal"],
    "systemui-frames": ["dumpsys", "gfxinfo", "com.android.systemui", "framestats"],
    "build": ["getprop", "ro.build.fingerprint"],
    "uptime": ["cat", "/proc/uptime"],
}


def capture(adb, output, name):
    folder = output / name
    folder.mkdir()
    result = {"started_utc": datetime.datetime.now(datetime.timezone.utc).isoformat()}
    for label, command in COMMANDS.items():
        try:
            proc = subprocess.run(adb + ["shell"] + command, capture_output=True,
                                  text=True, timeout=180)
            text = proc.stdout + proc.stderr
            result[label] = {"exit_code": proc.returncode}
        except subprocess.TimeoutExpired:
            text = "Collection timed out after 180 seconds.\n"
            result[label] = {"error": "timeout"}
        (folder / (label + ".txt")).write_text(text)
        if label == "battery":
            powered = any(line.strip().lower() in {
                "ac powered: true", "usb powered: true", "wireless powered: true",
                "dock powered: true"} for line in text.splitlines())
            result["externally_powered"] = powered
            if powered:
                print(f"{name}: external power detected; this is not an unplugged idle sample.",
                      file=sys.stderr)
    result["finished_utc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    (folder / "collection.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__, epilog=(
        "Use wireless ADB with the phone unplugged and leave its normal radios, apps and "
        "AOD settings unchanged. Run an equivalent baseline and candidate interval. "
        "Endpoint collection itself briefly activates services; no commands run during "
        "the waiting interval. Keep original battery-monitor readings alongside the files."))
    parser.add_argument("--serial", help="ADB device serial or wireless endpoint")
    parser.add_argument("--duration", type=int, default=3600, help="idle interval in seconds")
    parser.add_argument("--output", type=pathlib.Path, required=True,
                        help="new output directory, for example /tmp/petal-idle-baseline")
    args = parser.parse_args()
    if args.duration < 0:
        parser.error("duration must be non-negative")
    if args.output.exists():
        parser.error("output directory already exists; choose a new directory")
    adb = ["adb"] + (["-s", args.serial] if args.serial else [])
    try:
        state = subprocess.run(adb + ["get-state"], capture_output=True, text=True, timeout=15)
    except (FileNotFoundError, subprocess.TimeoutExpired) as error:
        parser.error(str(error))
    if state.returncode != 0 or state.stdout.strip() != "device":
        parser.error("connect one authorized device or specify --serial: " + state.stderr.strip())
    args.output.mkdir(parents=True)
    before = capture(adb, args.output, "before")
    started = time.monotonic()
    print(f"Waiting {args.duration}s; no polling or power-policy changes. Output: {args.output}",
          flush=True)
    time.sleep(args.duration)
    elapsed = time.monotonic() - started
    after = capture(adb, args.output, "after")
    summary = {"requested_idle_seconds": args.duration, "observed_idle_seconds": elapsed,
               "before": before, "after": after}
    (args.output / "session.json").write_text(json.dumps(summary, indent=2) + "\n")
    print("Captured both endpoints. Device drain and attribution require reviewing the deltas.")


if __name__ == "__main__":
    main()
