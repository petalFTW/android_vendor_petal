package android.app;
/** Host-only service interface. Actual AppOps authorization is outside journal unit tests. */
public class AppOpsManager {
    public static final int MODE_ALLOWED = 0;
    public static final int MODE_IGNORED = 1;
    public static final int MODE_ERRORED = 2;
    public static final int MODE_DEFAULT = 3;
    public static final int MODE_FOREGROUND = 4;
    public static final String OPSTR_FINE_LOCATION = "android:fine_location";
    public static final String OPSTR_COARSE_LOCATION = "android:coarse_location";
    public static final String OPSTR_BLUETOOTH_SCAN = "android:bluetooth_scan";
    public static final String OPSTR_RUN_ANY_IN_BACKGROUND = "android:run_any_in_background";
    public int unsafeCheckOpRawNoThrow(String op, int uid, String name) { throw new UnsupportedOperationException(); }
    public void setMode(String op, int uid, String name, int mode) { throw new UnsupportedOperationException(); }
}
