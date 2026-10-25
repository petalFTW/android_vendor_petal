#!/system/bin/sh
# Prepare Proton4A storage early in boot.

DATA=/data/adb/proton4a
mkdir -p "$DATA/runtime" "$DATA/prefix" "$DATA/home" "$DATA/tmp" "$DATA/fb"
chmod 0755 "$DATA" "$DATA/runtime" "$DATA/prefix" "$DATA/home" "$DATA/tmp" "$DATA/fb"

# Unpack a bundled payload once. The archive root is "runtime/", so it is
# extracted into $DATA.
PAYLOAD=/data/adb/modules/proton4a/runtime/proton4a-runtime.tar.gz
if [ -f "$PAYLOAD" ] && [ ! -e "$DATA/runtime/box64" ]; then
    TAR="tar"
    command -v busybox >/dev/null 2>&1 && TAR="busybox tar"
    echo "proton4a: extracting bundled runtime" > /dev/kmsg
    $TAR -xzf "$PAYLOAD" -C "$DATA" || echo "proton4a: extraction failed" > /dev/kmsg
fi
