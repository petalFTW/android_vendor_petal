#!/system/bin/sh
#
# petalOS content blocker helper.
# Applies the enabled flag, then parks in inotifyd watching that same flag.
# No polling: it just sits in do_sys_poll until the toggle writes the file.
# It never mounts itself, it only tells init to (init is the one allowed to).

DIR=/data/petal/blocklist
SRC=$DIR/hosts
STATE=$DIR/enabled

apply() {
    want=$(cat "$STATE" 2>/dev/null)
    if [ "$want" = "1" ]; then
        setprop sys.petal.blocklist 1
    else
        setprop sys.petal.blocklist 0
    fi
}

mkdir -p "$DIR" 2>/dev/null
chmod 0777 "$DIR" 2>/dev/null
[ -f "$SRC" ] || printf '127.0.0.1 localhost\n::1 localhost\n' > "$SRC" 2>/dev/null
chmod 0666 "$SRC" 2>/dev/null
[ -f "$STATE" ] || echo 0 > "$STATE" 2>/dev/null
chmod 0666 "$STATE" 2>/dev/null

# inotifyd re-runs us with args, that's the apply path
if [ -n "$1" ]; then
    apply
    exit 0
fi

# first run: sync once, then block on inotify forever
apply
exec inotifyd /system_ext/bin/petal-blocklist.sh "$STATE:w"
