package android.os;
/** Host-only clock for Android ApplicationInfo timestamps. */
public final class SystemClock {
    public static long uptimeMillis() { return System.nanoTime() / 1000000L; }
    public static long elapsedRealtime() { return uptimeMillis(); }
}
