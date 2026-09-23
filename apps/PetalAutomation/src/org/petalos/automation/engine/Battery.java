/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.automation.engine;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

/** sticky battery intent, that's it. */
public final class Battery {
    private Battery() {
    }

    public static Intent intent(Context context) {
        return context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    public static int level(Context context) {
        final Intent intent = intent(context);
        if (intent == null) {
            return -1;
        }
        final int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) {
            return -1;
        }
        return Math.round(level * 100f / scale);
    }

    public static boolean charging(Context context) {
        final Intent intent = intent(context);
        if (intent == null) {
            return false;
        }
        final int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }
}
