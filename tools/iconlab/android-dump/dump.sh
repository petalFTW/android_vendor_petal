#!/usr/bin/env bash
# Dumps all installed app icons from the connected phone.
# Usage: ./dump.sh [serial]
set -euo pipefail
cd "$(dirname "$0")"
SERIAL="${1:-}"
ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")
"${ADB[@]}" install -r icondump.apk
"${ADB[@]}" shell am start -n com.petalos.icondump/.DumpActivity --ez all true
echo "waiting for the dump..."
for _ in $(seq 1 120); do
  if "${ADB[@]}" shell 'ls /sdcard/Android/data/com.petalos.icondump/files/dump/metadata.csv' >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
rm -rf ../data/dump
"${ADB[@]}" pull /sdcard/Android/data/com.petalos.icondump/files/dump ../data/dump
echo "done: $(find ../data/dump -type f | wc -l) files"
