/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.power;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.PowerExemptionManager;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;
import java.util.Set;

/** Actual idle-policy exemptions, separate from app-reported/spoofed state. */
final class IdlePowerProtection {
    private static final Set<String> PUSH_PACKAGES = Set.of(
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
            "app.revanced.android.gms", "com.mgoogle.android.gms", "org.microg.gms",
            "app.grapheneos.gmscompat", "app.grapheneos.gmscompat.lib",
            "app.grapheneos.gmscompat.config", "org.petalos.gmscompat");

    private IdlePowerProtection() {}

    static boolean protects(Context context, String pkg) {
        if (PUSH_PACKAGES.contains(pkg)) return true;
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageInfo(pkg, 0);
            ApplicationInfo app = info == null ? null : info.applicationInfo;
            if (app == null || UserHandle.getAppId(app.uid) < Process.FIRST_APPLICATION_UID
                    || app.isSystemApp() || app.isUpdatedSystemApp()) return true;
            PowerExemptionManager exemptions = context.getSystemService(PowerExemptionManager.class);
            if (exemptions == null) return true;
            String[] siblings = pm.getPackagesForUid(app.uid);
            if (siblings == null || siblings.length == 0) return true;
            // Firewall rules are UID-wide; one protected sibling protects the whole UID.
            for (String sibling : siblings) {
                if (PUSH_PACKAGES.contains(sibling) || exemptions.isAllowListed(sibling, true)) {
                    return true;
                }
            }
            return exemptions.isAllowListed(pkg, true);
        } catch (PackageManager.NameNotFoundException | RuntimeException failure) {
            Log.w("PetalIdlePower", "Skipping unresolved idle target " + pkg, failure);
            return true;
        }
    }
}
