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

/** applies idle power policy on screen off */
public final class IdlePowerReceiver extends BroadcastReceiver {
    private static final String TAG = "PetalIdlePower";
    private static final String ACTION_APPLY = "org.petalos.power.action.APPLY_IDLE_POLICY";
    private static final String PREFS = "idle_power_state";
    private static final String KEY_TRANSACTION = "transaction_active";
    private static final String KEY_BUCKET = "bucket:";
    private static final String KEY_OP = "op:";

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
        final String action = intent.getAction();
        if (action == null) return;
        if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            schedule(context);
        } else if (Intent.ACTION_SCREEN_ON.equals(action)
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
        }
    }

    private static void schedule(Context context) {
        if (getInt(context, SETTING_ENABLED, 1) == 0) return;
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

    private static void apply(Context context) {
        final SharedPreferences prefs = prefs(context);
        if (prefs.getBoolean(KEY_TRANSACTION, false)) return;
        final Set<String> packages = configuredPackages(context);
        final UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        final PackageManager packageManager = context.getPackageManager();
        final SharedPreferences.Editor state = prefs.edit().clear();
        final int profile = getInt(context, SETTING_PROFILE, PROFILE_OPTIMIZED);

        for (String packageName : packages) {
            try {
                state.putInt(KEY_BUCKET + packageName, usage.getAppStandbyBucket(packageName));
                usage.setAppStandbyBucket(packageName, UsageStatsManager.STANDBY_BUCKET_RESTRICTED);
                if (profile == PROFILE_DEEP) {
                    final int uid = packageManager.getPackageUidAsUser(
                            packageName, UserHandle.myUserId());
                    saveAndSetOp(state, appOps, packageName, uid,
                            AppOpsManager.OPSTR_FINE_LOCATION, AppOpsManager.MODE_IGNORED);
                    saveAndSetOp(state, appOps, packageName, uid,
                            AppOpsManager.OPSTR_COARSE_LOCATION, AppOpsManager.MODE_IGNORED);
                    saveAndSetOp(state, appOps, packageName, uid,
                            AppOpsManager.OPSTR_BLUETOOTH_SCAN, AppOpsManager.MODE_IGNORED);
                    saveAndSetOp(state, appOps, packageName, uid,
                            AppOpsManager.OPSTR_RUN_ANY_IN_BACKGROUND, AppOpsManager.MODE_IGNORED);
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Configured package is not installed: " + packageName);
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to restrict " + packageName, e);
            }
        }
        saveAndDisableScan(context, state, Settings.Global.WIFI_SCAN_ALWAYS_AVAILABLE,
                SETTING_DISABLE_WIFI_SCAN);
        saveAndDisableScan(context, state, Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE,
                SETTING_DISABLE_BLE_SCAN);
        state.putBoolean(KEY_TRANSACTION, true).apply();
        Log.i(TAG, "Applied profile " + profile + " to " + packages.size() + " packages");
    }

    private static void restore(Context context) {
        final SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean(KEY_TRANSACTION, false)) return;
        final UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        final PackageManager packageManager = context.getPackageManager();
        for (String key : prefs.getAll().keySet()) {
            try {
                if (key.startsWith(KEY_BUCKET)) {
                    final String pkg = key.substring(KEY_BUCKET.length());
                    usage.setAppStandbyBucket(pkg, prefs.getInt(key,
                            UsageStatsManager.STANDBY_BUCKET_FREQUENT));
                } else if (key.startsWith(KEY_OP)) {
                    final String remainder = key.substring(KEY_OP.length());
                    final int separator = remainder.lastIndexOf(':');
                    if (separator <= 0) continue;
                    final String op = remainder.substring(0, separator);
                    final String pkg = remainder.substring(separator + 1);
                    final int uid = packageManager.getPackageUidAsUser(pkg, UserHandle.myUserId());
                    appOps.setMode(op, uid, pkg, prefs.getInt(key, AppOpsManager.MODE_DEFAULT));
                } else if (key.startsWith("global:")) {
                    Settings.Global.putInt(context.getContentResolver(), key.substring(7),
                            prefs.getInt(key, 1));
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.i(TAG, "Package removed before idle state restoration");
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to restore " + key, e);
            }
        }
        prefs.edit().clear().apply();
        Log.i(TAG, "Restored pre-idle policy state");
    }

    private static void saveAndSetOp(SharedPreferences.Editor state, AppOpsManager appOps,
            String packageName, int uid, String op, int mode) {
        state.putInt(KEY_OP + op + ':' + packageName,
                appOps.unsafeCheckOpNoThrow(op, uid, packageName));
        appOps.setMode(op, uid, packageName, mode);
    }

    private static void saveAndDisableScan(Context context, SharedPreferences.Editor state,
            String globalKey, String toggleKey) {
        if (getInt(context, toggleKey, 0) == 0) return;
        state.putInt("global:" + globalKey,
                Settings.Global.getInt(context.getContentResolver(), globalKey, 0));
        Settings.Global.putInt(context.getContentResolver(), globalKey, 0);
    }

    private static Set<String> configuredPackages(Context context) {
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
