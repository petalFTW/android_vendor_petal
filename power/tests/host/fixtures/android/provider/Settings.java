package android.provider;
import android.content.ContentResolver;
/** Host-only in-memory settings; production policy is never written by these tests. */
public final class Settings {
    private static final java.util.Map<String, String> VALUES = new java.util.concurrent.ConcurrentHashMap<>();
    public static void clear() { VALUES.clear(); }
    public static final class Secure {
        public static final String PETAL_SPOOFED_PACKAGES = "petal_spoofed_packages";
        public static final String PETAL_SPOOF_POWER_ERRORS = "petal_spoof_power_errors";
        public static String getStringForUser(ContentResolver resolver, String name, int userId) {
            return VALUES.get("secure:" + userId + ':' + name);
        }
        public static boolean putStringForUser(ContentResolver resolver, String name, String value, int userId) {
            VALUES.put("secure:" + userId + ':' + name, value);
            return true;
        }
    }
    public static final class System {
        public static String getString(ContentResolver resolver, String name) { return VALUES.get("system:" + name); }
        public static boolean putString(ContentResolver resolver, String name, String value) { VALUES.put("system:" + name, value); return true; }
        public static int getInt(ContentResolver resolver, String name, int defaultValue) {
            String value = getString(resolver, name);
            return value == null ? defaultValue : Integer.parseInt(value);
        }
    }
    public static final class Global {
        public static final String WIFI_SCAN_ALWAYS_AVAILABLE = "wifi_scan_always_enabled";
        public static final String BLE_SCAN_ALWAYS_AVAILABLE = "ble_scan_always_enabled";
        public static int getInt(ContentResolver resolver, String name, int defaultValue) {
            String value = VALUES.get("global:" + name);
            return value == null ? defaultValue : Integer.parseInt(value);
        }
        public static boolean putInt(ContentResolver resolver, String name, int value) { VALUES.put("global:" + name, Integer.toString(value)); return true; }
    }
}
