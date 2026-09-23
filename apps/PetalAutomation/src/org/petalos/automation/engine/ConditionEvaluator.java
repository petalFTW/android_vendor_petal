/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.automation.engine;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.PowerManager;

import org.petalos.automation.model.Condition;
import org.petalos.automation.model.Types;

import java.util.Calendar;
import java.util.List;

/** does this gate pass or not. */
public final class ConditionEvaluator {
    private ConditionEvaluator() {
    }

    public static boolean allPass(Context context, List<Condition> conditions) {
        for (Condition condition : conditions) {
            if (!passes(context, condition)) {
                return false;
            }
        }
        return true;
    }

    public static boolean passes(Context context, Condition condition) {
        final int level = Battery.level(context);
        switch (condition.type) {
            case Types.C_BATTERY_ABOVE:
                return level >= condition.getInt(Types.P_LEVEL, 50);
            case Types.C_BATTERY_BELOW:
                return level >= 0 && level <= condition.getInt(Types.P_LEVEL, 50);
            case Types.C_CHARGING:
                return Battery.charging(context);
            case Types.C_WIFI_ON: {
                final WifiManager wifi = context.getSystemService(WifiManager.class);
                return wifi != null && wifi.isWifiEnabled();
            }
            case Types.C_WIFI_SSID: {
                final WifiManager wifi = context.getSystemService(WifiManager.class);
                if (wifi == null || wifi.getConnectionInfo() == null) {
                    return false;
                }
                final String ssid = wifi.getConnectionInfo().getSSID();
                final String wanted = condition.get(Types.P_VALUE, "");
                return ssid != null && ssid.replace("\"", "").equals(wanted);
            }
            case Types.C_SCREEN_ON: {
                final PowerManager pm = context.getSystemService(PowerManager.class);
                return pm != null && pm.isInteractive();
            }
            case Types.C_TIME_AFTER:
                return minutesNow() >= condition.getInt(Types.P_HOUR, 0) * 60
                        + condition.getInt(Types.P_MINUTE, 0);
            case Types.C_TIME_BEFORE:
                return minutesNow() < condition.getInt(Types.P_HOUR, 0) * 60
                        + condition.getInt(Types.P_MINUTE, 0);
            case Types.C_APP_FOREGROUND:
                return condition.get(Types.P_APP, "").equals(foregroundApp(context));
            default:
                return true;
        }
    }

    private static int minutesNow() {
        final Calendar now = Calendar.getInstance();
        return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
    }

    static String foregroundApp(Context context) {
        final UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
        if (usage == null) {
            return null;
        }
        final long now = System.currentTimeMillis();
        final List<UsageStats> stats = usage.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now);
        if (stats == null || stats.isEmpty()) {
            return null;
        }
        UsageStats newest = null;
        for (UsageStats stat : stats) {
            if (newest == null || stat.getLastTimeUsed() > newest.getLastTimeUsed()) {
                newest = stat;
            }
        }
        return newest == null ? null : newest.getPackageName();
    }
}
