#!/system/bin/sh
# Late-start service for Proton4A. Nothing runs by default: the display server
# and Wine are started on demand by `proton4a xvfb` / `proton4a run`.
#
# Set PROTON4A_AUTOSTART=1 in /data/adb/proton4a/env to bring Xvfb up at boot.

DATA=/data/adb/proton4a
[ -f "$DATA/env" ] && . "$DATA/env"

# A bundled Hub is installed as an ordinary signed update, rather than
# shadowing /system_ext/priv-app (which would replace the original at boot).
HUB_APK=${0%/*}/hub/PetalHub.apk
if [ -f "$HUB_APK" ]; then
    desired=$(sha256sum "$HUB_APK" | cut -d ' ' -f 1)
    current=$(cat "$DATA/hub-installed.sha256" 2>/dev/null)
    if [ "$desired" != "$current" ]; then
        # `cmd package` runs in system_server's SELinux domain and cannot
        # write directly to /data/adb; capture output through a pipe instead.
        if result=$(pm install -r "$HUB_APK" 2>&1); then
            printf '%s\n' "$result" > "$DATA/hub-install.log"
            echo "$desired" > "$DATA/hub-installed.sha256"
        else
            printf '%s\n' "$result" > "$DATA/hub-install.log"
        fi
    fi
fi

if [ "${PROTON4A_AUTOSTART:-0}" = "1" ]; then
    /system/bin/proton4a xvfb >/dev/null 2>&1 &
fi

exit 0
