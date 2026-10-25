/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.power;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-app network access toggle, enforced with netd's firewall.
 *
 * The netd BPF maps are kernel state and do not survive a reboot, so the
 * authoritative list lives in Settings.System and is re-applied on boot and
 * whenever PetalHub changes it.
 */
public final class NetworkPolicyReceiver extends BroadcastReceiver {
    private static final String TAG = "PetalNetPolicy";

    /** PetalHub broadcasts this after the user changes the list. */
    public static final String ACTION_APPLY = "org.petalos.power.action.APPLY_NETWORK_POLICY";
    /** Comma separated package names without network access. */
    public static final String SETTING_DENIED = "petal_network_denied_packages";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        final String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || ACTION_APPLY.equals(action)) {
            final PendingResult result = goAsync();
            new Thread(() -> {
                try {
                    apply(context.getApplicationContext());
                } finally {
                    result.finish();
                }
            }, "PetalNetPolicy").start();
        }
    }

    /** Apply on a worker thread; the firewall APIs do netlink I/O. */
    public static void requestApply(Context context) {
        final Context app = context.getApplicationContext();
        new Thread(() -> apply(app), "PetalNetPolicy").start();
    }

    /** Enables the OEM deny chain and pushes the current deny list to netd. */
    public static synchronized void apply(Context context) {
        final ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        final Set<String> denied = deniedPackages(context);

        try {
            cm.setFirewallChainEnabled(ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_1, true);
        } catch (RuntimeException e) {
            // The chain is usually enabled by the time this throws (socket bookkeeping
            // runs after the BPF update); keep going and set the per-uid rules.
            Log.w(TAG, "setFirewallChainEnabled reported " + e);
        }

        final PackageManager pm = context.getPackageManager();
        final List<ApplicationInfo> installed = pm.getInstalledApplications(0);
        // netd enforces UIDs, not package names: any denied sibling must deny the shared UID.
        final Set<Integer> deniedUids = new LinkedHashSet<>();
        for (ApplicationInfo app : installed) {
            if (denied.contains(app.packageName)) deniedUids.add(app.uid);
        }
        final Set<Integer> visited = new LinkedHashSet<>();
        int applied = 0;
        for (ApplicationInfo app : installed) {
            final int uid = app.uid;
            final int appId = android.os.UserHandle.getAppId(uid);
            if (appId < android.os.Process.FIRST_APPLICATION_UID
                    || appId > android.os.Process.LAST_APPLICATION_UID) {
                continue;
            }
            if (!visited.add(uid)) continue;
            final boolean block = deniedUids.contains(uid);
            try {
                cm.setUidFirewallRule(ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_1, uid,
                        block ? ConnectivityManager.FIREWALL_RULE_DENY
                              : ConnectivityManager.FIREWALL_RULE_ALLOW);
                applied++;
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to set network rule for " + app.packageName + ": " + e);
            }
        }
        Log.i(TAG, "Network policy applied to " + applied + " uids, denied=" + denied.size());
    }

    /**
     * Packages without network access: the user's explicit deny list, plus the
     * idle-power list while its restriction transaction is active. This lets the
     * idle policy reuse the same enforcement instead of a separate mechanism.
     */
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
