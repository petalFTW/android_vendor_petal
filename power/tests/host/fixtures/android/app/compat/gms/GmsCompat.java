package android.app.compat.gms;
import android.content.pm.ApplicationInfo;
/** Host-only fixture: custom GmsCompat metadata is absent in AOSP android-all. */
public final class GmsCompat {
    public static boolean isEnabledFor(ApplicationInfo app) {
        if (!"com.example.dashcam".equals(app.packageName)) {
            throw new AssertionError("Unexpected fixture; actual GmsCompat eligibility needs device tests");
        }
        return false;
    }
}
