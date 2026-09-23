/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.automation.engine;

import android.content.Context;
import android.content.SharedPreferences;

/** the big red switch. off means nothing is registered, zero idle footprint. */
public final class PowerState {
    private static final String PREFS = "automation_power";
    private static final String KEY_ENABLED = "enabled";

    private PowerState() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
        AutomationEngine.reconcile(context);
    }
}
