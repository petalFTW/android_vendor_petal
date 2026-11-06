/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.power;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// per-app network kill switch via netd. maps die on reboot, settings is the truth
public final class NetworkPolicyReceiver extends BroadcastReceiver {
    private static final String TAG = "PetalNetPolicy";
    private static final PolicyUpdateQueue UPDATES = new PolicyUpdateQueue(
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    task -> new Thread(task, "PetalNetPolicy")),
            failure -> Log.w(TAG, "Network policy update failed", failure));

    /** PetalHub broadcasts this after the user changes the list. */
    public static final String ACTION_APPLY = "org.petalos.power.action.APPLY_NETWORK_POLICY";
    /** Comma separated package names without network access. */
    public static final String SETTING_DENIED = "petal_network_denied_packages";
    private static final String NET_PREFS = "net_policy_state";
    private static final String KEY_BOOT = "boot";
    private static final String KEY_DENIED_UIDS = "denied_uids";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        final String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || ACTION_APPLY.equals(action)) {
            final PendingResult result = goAsync();
            final Context app = context.getApplicationContext();
            UPDATES.submit(() -> apply(app), result::finish);
        }
    }

    /** Apply on a worker thread; the firewall APIs do netlink I/O. */
    public static void requestApply(Context context) {
        final Context app = context.getApplicationContext();
        UPDATES.submit(() -> apply(app), null);
    }

    /** Pushes only the uids that changed since last time. bpf maps die on reboot so boot = clean slate. */
    public static synchronized void apply(Context context) {
        final ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        final Set<String> denied = deniedPackages(context);

        final PackageManager pm = context.getPackageManager();
        final List<ApplicationInfo> installed = pm.getInstalledApplications(0);
        // netd works on uids, one denied sibling = whole uid denied
        final Set<Integer> deniedUids = new LinkedHashSet<>();
        for (ApplicationInfo app : installed) {
            final int appId = android.os.UserHandle.getAppId(app.uid);
            if (appId < android.os.Process.FIRST_APPLICATION_UID
                    || appId > android.os.Process.LAST_APPLICATION_UID) continue;
            if (denied.contains(app.packageName)) deniedUids.add(app.uid);
        }

        final SharedPreferences state = state(context);
        final int boot = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.BOOT_COUNT, -1);
        final Set<Integer> previous = boot == state.getInt(KEY_BOOT, -2)
                ? parseUids(state.getString(KEY_DENIED_UIDS, "")) : new LinkedHashSet<>();
        if (deniedUids.isEmpty() && previous.isEmpty()) {
            state.edit().putInt(KEY_BOOT, boot).putString(KEY_DENIED_UIDS, "").commit();
            return;
        }

        // getFirewallChainEnabled is module-only api, can't see it from here. just flip it on
        try {
            cm.setFirewallChainEnabled(ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_1, true);
        } catch (RuntimeException e) {
            // usually on by the time this throws, carry on with the rules
            Log.w(TAG, "setFirewallChainEnabled reported " + e);
        }

        final Set<Integer> nowDenied = new LinkedHashSet<>(previous);
        int changed = 0;
        for (int uid : deniedUids) {
            if (previous.contains(uid)) continue;
            if (setRule(cm, uid, ConnectivityManager.FIREWALL_RULE_DENY)) {
                nowDenied.add(uid);
                changed++;
            }
        }
        for (int uid : previous) {
            if (deniedUids.contains(uid)) continue;
            // failed allow stays in the set so it gets retried next pass
            if (setRule(cm, uid, ConnectivityManager.FIREWALL_RULE_ALLOW)) {
                nowDenied.remove(uid);
                changed++;
            }
        }
        state.edit().putInt(KEY_BOOT, boot).putString(KEY_DENIED_UIDS, joinUids(nowDenied)).commit();
        Log.i(TAG, "Network policy changed " + changed + " uids, denied=" + nowDenied.size());
    }

    private static boolean setRule(ConnectivityManager cm, int uid, int rule) {
        try {
            cm.setUidFirewallRule(ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_1, uid, rule);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to set network rule for uid " + uid + ": " + e);
            return false;
        }
    }

    private static Set<Integer> parseUids(String raw) {
        final Set<Integer> out = new LinkedHashSet<>();
        if (TextUtils.isEmpty(raw)) return out;
        for (String s : raw.split(",")) {
            try {
                out.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
                // junk in prefs, whatever
            }
        }
        return out;
    }

    private static String joinUids(Set<Integer> uids) {
        final StringBuilder sb = new StringBuilder();
        for (int uid : uids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(uid);
        }
        return sb.toString();
    }

    private static SharedPreferences state(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(NET_PREFS, Context.MODE_PRIVATE);
    }

    // user's deny list + whatever idle power has locked down right now
    public static Set<String> deniedPackages(Context context) {
        final Set<String> result = new LinkedHashSet<>();
        addCsv(result,
                Settings.System.getString(context.getContentResolver(), SETTING_DENIED));
        result.addAll(IdlePowerReceiver.idleNetworkPackages(context));
        return result;
    }

    private static void addCsv(Set<String> target, String raw) {
        if (TextUtils.isEmpty(raw)) return;
        for (String entry : raw.split(",")) {
            final String packageName = entry.trim();
            if (!packageName.isEmpty()) target.add(packageName);
        }
    }
}
