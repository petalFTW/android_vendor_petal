/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.power;

import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import java.util.LinkedHashSet;
import java.util.Set;
import com.android.internal.app.PetalSpoofConfig;

/** applies idle power policy on screen off */
public final class IdlePowerReceiver extends BroadcastReceiver {
    private static final String TAG = "PetalIdlePower";
    private static final String ACTION_APPLY = "org.petalos.power.action.APPLY_IDLE_POLICY";
    public static final String ACTION_SETTINGS_CHANGED = "org.petalos.power.action.UPDATE_IDLE_POLICY";
    private static final String ACTION_SCREEN_OFF = "org.petalos.power.action.SCREEN_OFF";
    private static final String ACTION_SCREEN_ON = "org.petalos.power.action.SCREEN_ON";
    private static final String ACTION_RECONCILE = "org.petalos.power.action.RECONCILE_IDLE_POLICY";
    private static final java.util.concurrent.ExecutorService WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final String PREFS = "idle_power_state";
    private static final String KEY_TRANSACTION = "transaction_active";
    private static final String KEY_BUCKET = "bucket:";
    private static final String KEY_APPLIED_BUCKET = "applied_bucket:";
    private static final String KEY_OP = "op:";
    private static final String KEY_UID = "uid:";
    private static final String KEY_INSTALLED = "installed:";
    private static final String KEY_PROFILE = "applied_profile";
    private static final String KEY_RESTORING = "restoring";
    private static final String KEY_RESTORE_ERROR = "restore_error:";
    private static final String[] DEEP_OPS = {AppOpsManager.OPSTR_FINE_LOCATION,
            AppOpsManager.OPSTR_COARSE_LOCATION, AppOpsManager.OPSTR_BLUETOOTH_SCAN,
            AppOpsManager.OPSTR_RUN_ANY_IN_BACKGROUND};

    public static final String SETTING_ENABLED = "petal_idle_power_enabled";
    public static final String SETTING_PROFILE = "petal_idle_power_profile";
    public static final String SETTING_PACKAGES = "petal_idle_power_packages";
    public static final String SETTING_GRACE_MINUTES = "petal_idle_power_grace_minutes";
    public static final String SETTING_DISABLE_WIFI_SCAN = "petal_idle_disable_wifi_scan";
    public static final String SETTING_DISABLE_BLE_SCAN = "petal_idle_disable_ble_scan";

    private static final int PROFILE_OPTIMIZED = 1;
    private static final int PROFILE_DEEP = 2;

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        final PendingResult pending = goAsync();
        WORKER.execute(() -> {
            try {
                handle(context.getApplicationContext(), action);
            } finally {
                if (pending != null) pending.finish();
            }
        });
    }

    private static void handle(Context context, String action) {
        if (Intent.ACTION_SCREEN_OFF.equals(action) || ACTION_SCREEN_OFF.equals(action)) {
            schedule(context);
        } else if (Intent.ACTION_SCREEN_ON.equals(action)
                || ACTION_SCREEN_ON.equals(action)
                || Intent.ACTION_USER_PRESENT.equals(action)) {
            cancel(context);
            restore(context);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            restore(context);
            final PowerManager pm = context.getSystemService(PowerManager.class);
            if (pm != null && !pm.isInteractive()) schedule(context);
        } else if (ACTION_APPLY.equals(action)) {
            final PowerManager pm = context.getSystemService(PowerManager.class);
            if (pm != null && !pm.isInteractive()) apply(context);
            else restore(context);
        } else if (ACTION_RECONCILE.equals(action)) {
            reconcileExemptions(context);
        }
    }

    private static void schedule(Context context) {
        final PowerManager power = context.getSystemService(PowerManager.class);
        if (getInt(context, SETTING_ENABLED, 1) == 0 || power == null || power.isInteractive()) {
            cancel(context);
            restore(context);
            return;
        }
        if (effectivePackages(context).isEmpty() && getInt(context, SETTING_DISABLE_WIFI_SCAN, 0) == 0
                && getInt(context, SETTING_DISABLE_BLE_SCAN, 0) == 0) {
            cancel(context);
            restore(context);
            return;
        }
        final int minutes = Math.max(0, Math.min(120,
                getInt(context, SETTING_GRACE_MINUTES, 10)));
        final AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + minutes * 60_000L, pendingApply(context));
        Log.i(TAG, "Idle policy scheduled in " + minutes + " minutes");
    }

    private static void cancel(Context context) {
        final AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms != null) alarms.cancel(pendingApply(context));
    }

    private static PendingIntent pendingApply(Context context) {
        final Intent intent = new Intent(context, IdlePowerReceiver.class).setAction(ACTION_APPLY);
        return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static synchronized void apply(Context context) {
        final PowerManager power = context.getSystemService(PowerManager.class);
        if (getInt(context, SETTING_ENABLED, 1) == 0 || power == null || power.isInteractive()) {
            cancel(context);
            restore(context);
            return;
        }
        final SharedPreferences prefs = prefs(context);
        if (prefs.getBoolean(KEY_TRANSACTION, false)) {
            if (prefs.getBoolean(KEY_RESTORING, false)) restore(context);
            if (prefs.getBoolean(KEY_TRANSACTION, false)) return;
        }
        final Set<String> packages = effectivePackages(context);
        if (packages.isEmpty() && getInt(context, SETTING_DISABLE_WIFI_SCAN, 0) == 0
                && getInt(context, SETTING_DISABLE_BLE_SCAN, 0) == 0) return;
        final SharedPreferences.Editor state = prefs.edit().clear();
        final int profile = getInt(context, SETTING_PROFILE, PROFILE_OPTIMIZED) == PROFILE_DEEP
                ? PROFILE_DEEP : PROFILE_OPTIMIZED;

        // Write-ahead: record the pre-idle state and mark the transaction durably
        // BEFORE mutating anything. If this process dies partway through the
        // restriction pass, restore() still holds everything needed to undo, so a
        // package can never be left wedged in RESTRICTED with no way back.
        recordScan(context, state, SETTING_DISABLE_WIFI_SCAN,
                Settings.Global.WIFI_SCAN_ALWAYS_AVAILABLE);
        recordScan(context, state, SETTING_DISABLE_BLE_SCAN,
                Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE);
        if (!state.putBoolean(KEY_TRANSACTION, true).putInt(KEY_PROFILE, profile).commit()) {
            Log.e(TAG, "Unable to persist idle transaction; no restrictions applied");
            return;
        }

        for (String packageName : packages) restrictPackage(context, prefs, packageName, profile);
        if (getInt(context, SETTING_DISABLE_WIFI_SCAN, 0) != 0) {
            Settings.Global.putInt(context.getContentResolver(),
                    Settings.Global.WIFI_SCAN_ALWAYS_AVAILABLE, 0);
        }
        if (getInt(context, SETTING_DISABLE_BLE_SCAN, 0) != 0) {
            Settings.Global.putInt(context.getContentResolver(),
                    Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE, 0);
        }
        Log.i(TAG, "Applied profile " + profile + " to " + packages.size() + " packages");
        // Idle-restricted apps now also lose network, via the shared enforcement.
        NetworkPolicyReceiver.requestApply(context);
    }

    private static synchronized void restore(Context context) {
        final SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean(KEY_TRANSACTION, false)) return;
        if (!prefs.edit().putBoolean(KEY_RESTORING, true).commit()) return;
        final Set<String> packages = recordedPackages(prefs);
        for (String pkg : packages) restorePackage(context, prefs, pkg);
        for (String key : prefs.getAll().keySet()) {
            if (!key.startsWith("global:")) continue;
            try {
                final String setting = key.substring(7);
                if (Settings.Global.getInt(context.getContentResolver(), setting, 0) == 0) {
                    if (!Settings.Global.putInt(context.getContentResolver(), setting,
                            prefs.getInt(key, 1))) continue;
                }
                prefs.edit().remove(key).commit();
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to restore " + key, e);
            }
        }
        // Retain failed work for retry, rather than losing the only restoration journal.
        if (recordedPackages(prefs).isEmpty()
                && prefs.getAll().keySet().stream().noneMatch(key -> key.startsWith("global:"))) {
            prefs.edit().clear().commit();
        }
        publishRestoreErrors(context, prefs);
        Log.i(TAG, "Restored pre-idle policy state");
        // Idle list no longer applies, so re-run the shared network enforcement.
        NetworkPolicyReceiver.requestApply(context);
    }

    private static void restrictPackage(Context context, SharedPreferences prefs, String pkg, int profile) {
        try {
            if (isExempt(context, PetalSpoofConfig.read(context), pkg)) return;
            final android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(pkg, 0);
            final int uid = info.applicationInfo.uid;
            final UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
            final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
            final int bucket = usage.getAppStandbyBucket(pkg);
            final int[] modes = new int[DEEP_OPS.length];
            if (profile == PROFILE_DEEP) {
                for (int i = 0; i < DEEP_OPS.length; i++) {
                    modes[i] = appOps.unsafeCheckOpRawNoThrow(DEEP_OPS[i], uid, pkg);
                }
            }
            final int appliedBucket = profile == PROFILE_DEEP
                    ? UsageStatsManager.STANDBY_BUCKET_RESTRICTED : UsageStatsManager.STANDBY_BUCKET_RARE;
            final SharedPreferences.Editor journal = prefs.edit().putInt(KEY_BUCKET + pkg, bucket)
                    .putInt(KEY_APPLIED_BUCKET + pkg, appliedBucket)
                    .putInt(KEY_UID + pkg, uid).putLong(KEY_INSTALLED + pkg, info.firstInstallTime);
            if (profile == PROFILE_DEEP) {
                for (int i = 0; i < DEEP_OPS.length; i++) {
                    journal.putInt(KEY_OP + DEEP_OPS[i] + ':' + pkg, modes[i]);
                }
            }
            if (!journal.commit()) return;
            // The complete package baseline is durable before the first real mutation.
            if (isExempt(context, PetalSpoofConfig.read(context), pkg)) {
                restorePackage(context, prefs, pkg);
                return;
            }
            usage.setAppStandbyBucket(pkg, appliedBucket);
            if (profile == PROFILE_DEEP) {
                for (String op : DEEP_OPS) appOps.setMode(op, uid, pkg, AppOpsManager.MODE_IGNORED);
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Log.w(TAG, "Unable to restrict " + pkg, e);
        }
    }

    /** Reconcile exemptions without disturbing other packages or global scan state. */
    static synchronized void reconcileExemptions(Context context) {
        final SharedPreferences prefs = prefs(context);
        if (prefs.getBoolean(KEY_TRANSACTION, false)) {
            final PowerManager pm = context.getSystemService(PowerManager.class);
            if (prefs.getBoolean(KEY_RESTORING, false) || (pm != null && pm.isInteractive())
                    || getInt(context, SETTING_ENABLED, 1) == 0) {
                restore(context);
                return;
            }
            final PetalSpoofConfig config = PetalSpoofConfig.read(context);
            for (String pkg : recordedPackages(prefs)) {
                if (isExempt(context, config, pkg)
                        || !transactionIdentityMatches(context, prefs, pkg)) {
                    restorePackage(context, prefs, pkg);
                }
            }
            // Removing an exemption mid-transaction rejoins the current policy, with a new
            // write-ahead baseline. Exemptions do not silently remain until another screen cycle.
            final Set<String> recorded = recordedPackages(prefs);
            for (String pkg : effectivePackages(context)) {
                if (!recorded.contains(pkg)) restrictPackage(context, prefs, pkg,
                        prefs.getInt(KEY_PROFILE, PROFILE_OPTIMIZED));
            }
        }
        publishRestoreErrors(context, prefs);
        NetworkPolicyReceiver.requestApply(context);
    }

    static synchronized void updateSettings(Context context) {
        cancel(context);
        restore(context);
        if (getInt(context, SETTING_ENABLED, 1) != 0) schedule(context);
    }

    private static void restorePackage(Context context, SharedPreferences prefs, String pkg) {
        final SharedPreferences.Editor cleared = prefs.edit();
        boolean identityMatches = false;
        int uid = -1;
        try {
            final android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(pkg, 0);
            uid = info.applicationInfo.uid;
            identityMatches = (!prefs.contains(KEY_UID + pkg)
                    || prefs.getInt(KEY_UID + pkg, -1) == uid)
                    && (!prefs.contains(KEY_INSTALLED + pkg)
                    || prefs.getLong(KEY_INSTALLED + pkg, -1) == info.firstInstallTime);
        } catch (PackageManager.NameNotFoundException e) {
            // Discard removed/reinstalled targets; never apply their old AppOps to a new UID.
        } catch (RuntimeException e) {
            prefs.edit().putBoolean(KEY_RESTORE_ERROR + pkg, true).commit();
            Log.w(TAG, "Unable to resolve restoration target " + pkg, e);
            return;
        }
        boolean failed = false;
        for (String key : prefs.getAll().keySet()) {
            final boolean bucket = key.equals(KEY_BUCKET + pkg);
            final boolean op = key.startsWith(KEY_OP) && key.endsWith(':' + pkg);
            if (!bucket && !op) continue;
            try {
                if (identityMatches && bucket) {
                    final UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
                    if (usage.getAppStandbyBucket(pkg) == prefs.getInt(KEY_APPLIED_BUCKET + pkg,
                            UsageStatsManager.STANDBY_BUCKET_RESTRICTED)) {
                        usage.setAppStandbyBucket(pkg, prefs.getInt(key,
                                UsageStatsManager.STANDBY_BUCKET_FREQUENT));
                    }
                } else if (identityMatches) {
                    final String code = key.substring(KEY_OP.length(), key.length() - pkg.length() - 1);
                    final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
                    // Never overwrite a newer real battery/AppOps selection.
                    if (appOps.unsafeCheckOpRawNoThrow(code, uid, pkg) == AppOpsManager.MODE_IGNORED) {
                        appOps.setMode(code, uid, pkg, prefs.getInt(key, AppOpsManager.MODE_DEFAULT));
                    }
                }
                cleared.remove(key);
            } catch (RuntimeException e) {
                failed = true;
                Log.w(TAG, "Unable to restore " + key, e);
            }
        }
        if (!failed) {
            cleared.remove(KEY_UID + pkg).remove(KEY_INSTALLED + pkg).remove(KEY_RESTORE_ERROR + pkg)
                    .remove(KEY_APPLIED_BUCKET + pkg);
        } else {
            cleared.putBoolean(KEY_RESTORE_ERROR + pkg, true);
        }
        if (!cleared.commit()) prefs.edit().putBoolean(KEY_RESTORE_ERROR + pkg, true).commit();
    }

    private static Set<String> recordedPackages(SharedPreferences prefs) {
        final Set<String> result = new LinkedHashSet<>();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(KEY_BUCKET)) result.add(key.substring(KEY_BUCKET.length()));
            else if (key.startsWith(KEY_OP)) result.add(key.substring(key.lastIndexOf(':') + 1));
        }
        return result;
    }

    private static void publishRestoreErrors(Context context, SharedPreferences prefs) {
        final Set<String> failed = new LinkedHashSet<>();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(KEY_RESTORE_ERROR)) failed.add(key.substring(KEY_RESTORE_ERROR.length()));
        }
        Settings.Secure.putStringForUser(context.getContentResolver(),
                Settings.Secure.PETAL_SPOOF_POWER_ERRORS, TextUtils.join(",", failed), context.getUserId());
    }

    private static Set<String> effectivePackages(Context context) {
        final Set<String> packages = configuredPackages(context);
        final PetalSpoofConfig config = PetalSpoofConfig.read(context);
        packages.removeIf(pkg -> isExempt(context, config, pkg));
        return packages;
    }

    /** Network targets are the actual transaction, excluding newly exempted packages. */
    static synchronized Set<String> idleNetworkPackages(Context context) {
        final SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean(KEY_TRANSACTION, false) || prefs.getBoolean(KEY_RESTORING, false)) {
            return new LinkedHashSet<>();
        }
        if (prefs.getInt(KEY_PROFILE, PROFILE_DEEP) != PROFILE_DEEP) return new LinkedHashSet<>();
        final Set<String> packages = recordedPackages(prefs);
        final PetalSpoofConfig config = PetalSpoofConfig.read(context);
        packages.removeIf(pkg -> isExempt(context, config, pkg)
                || !transactionIdentityMatches(context, prefs, pkg));
        return packages;
    }

    private static boolean isExempt(Context context, PetalSpoofConfig config, String pkg) {
        return IdlePowerProtection.protects(context, pkg) || config.isIdleExempt(context, pkg);
    }

    private static boolean transactionIdentityMatches(Context context, SharedPreferences prefs, String pkg) {
        try {
            final android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(pkg, 0);
            return (!prefs.contains(KEY_UID + pkg) || prefs.getInt(KEY_UID + pkg, -1) == info.applicationInfo.uid)
                    && (!prefs.contains(KEY_INSTALLED + pkg)
                    || prefs.getLong(KEY_INSTALLED + pkg, -1) == info.firstInstallTime);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    private static void recordScan(Context context, SharedPreferences.Editor state,
            String toggleKey, String globalKey) {
        if (getInt(context, toggleKey, 0) == 0) return;
        state.putInt("global:" + globalKey,
                Settings.Global.getInt(context.getContentResolver(), globalKey, 0));
    }

    /** True while the idle restriction is applied and not yet restored. */
    static boolean transactionActive(Context context) {
        return prefs(context).getBoolean(KEY_TRANSACTION, false);
    }

    static Set<String> configuredPackages(Context context) {
        final LinkedHashSet<String> result = new LinkedHashSet<>();
        final String raw = Settings.System.getString(
                context.getContentResolver(), SETTING_PACKAGES);
        if (TextUtils.isEmpty(raw)) return result;
        for (String entry : raw.split(",")) {
            final String packageName = entry.trim();
            if (!packageName.isEmpty()) result.add(packageName);
        }
        return result;
    }

    private static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int getInt(Context context, String key, int defaultValue) {
        return Settings.System.getInt(context.getContentResolver(), key, defaultValue);
    }
}
