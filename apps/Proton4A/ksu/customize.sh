#!/system/bin/sh
# KernelSU / Magisk installer hook for the Proton4A test module.

ui_print "*******************************"
ui_print " Proton4A runtime (test module)"
ui_print "*******************************"

# A bundled payload is optional; the runtime can also be pushed later.
if [ -f "$MODPATH/runtime/proton4a-runtime.tar.gz" ]; then
    ui_print "- Bundled runtime payload found; it will be installed on first boot."
else
    ui_print "- No payload bundled. Push proton4a-runtime.tar.gz to"
    ui_print "  /data/adb/proton4a/ and run:"
    ui_print "    su -c 'proton4a install-runtime'"
fi
ui_print "- Test with: su -c 'proton4a test'"
