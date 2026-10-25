/*
 * Copyright (C) 2026 petalOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.petalos.hub;

import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

/** hub is a system uid app, so it does the grants and display switches game mode can't */
public final class GameModeReceiver extends BroadcastReceiver {

    private static final String TAG = "PetalHub/GameMode";

    public static final String GM_PACKAGE = "org.petalos.petaltuner";
    public static final String ACTION_GRANT = "org.petalos.hub.action.GM_GRANT";
    public static final String ACTION_DISPLAY = "org.petalos.hub.action.GM_DISPLAY";
    public static final String EXTRA_MAX_RES = "max_res";

    private static final String PREFS = "gm_display";
    private static final String KEY_W = "orig_w";
    private static final String KEY_H = "orig_h";
    private static final String KEY_HZ = "orig_hz";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_GRANT.equals(action)) {
            grant(context);
        } else if (ACTION_DISPLAY.equals(action)) {
            setMaxResolution(context, intent.getBooleanExtra(EXTRA_MAX_RES, false));
        }
    }

    public static void grant(Context context) {
        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(GM_PACKAGE, 0);
            AppOpsManager ops = context.getSystemService(AppOpsManager.class);
            ops.setMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, info.uid, GM_PACKAGE,
                    AppOpsManager.MODE_ALLOWED);
            ops.setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, info.uid, GM_PACKAGE,
                    AppOpsManager.MODE_ALLOWED);
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (!nm.isNotificationPolicyAccessGrantedForPackage(GM_PACKAGE)) {
                nm.setNotificationPolicyAccessGranted(GM_PACKAGE, true);
            }
        } catch (Exception e) {
            Log.w(TAG, "grant failed", e);
        }
    }

    private static void setMaxResolution(Context context, boolean max) {
        try {
            DisplayManager dm = context.getSystemService(DisplayManager.class);
            Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
            SharedPreferences store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            Display.Mode current = display.getMode();
            if (max) {
                Display.Mode best = null;
                for (Display.Mode mode : display.getSupportedModes()) {
                    if (best == null || area(mode) > area(best)
                            || (area(mode) == area(best)
                            && mode.getRefreshRate() > best.getRefreshRate())) {
                        best = mode;
                    }
                }
                if (best == null) {
                    return;
                }
                if (!store.contains(KEY_W)) {
                    store.edit().putInt(KEY_W, current.getPhysicalWidth())
                            .putInt(KEY_H, current.getPhysicalHeight())
                            .putFloat(KEY_HZ, current.getRefreshRate()).apply();
                }
                if (best.getPhysicalWidth() != current.getPhysicalWidth()
                        || best.getPhysicalHeight() != current.getPhysicalHeight()) {
                    display.setUserPreferredDisplayMode(best);
                }
            } else if (store.contains(KEY_W)) {
                Display.Mode original = new Display.Mode(store.getInt(KEY_W, 0),
                        store.getInt(KEY_H, 0), store.getFloat(KEY_HZ, 60f));
                store.edit().clear().apply();
                if (original.getPhysicalWidth() != current.getPhysicalWidth()
                        || original.getPhysicalHeight() != current.getPhysicalHeight()) {
                    display.setUserPreferredDisplayMode(original);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "resolution switch failed", e);
        }
    }

    private static long area(Display.Mode mode) {
        return (long) mode.getPhysicalWidth() * mode.getPhysicalHeight();
    }
}
