package android.util;
/** Host-only fixture: logging must not invoke Android JNI. */
public final class Log {
    public static int w(String tag, String message, Throwable failure) { return 0; }
    public static int w(String tag, String message) { return 0; }
    public static int i(String tag, String message) { return 0; }
    public static int e(String tag, String message) { return 0; }
}
