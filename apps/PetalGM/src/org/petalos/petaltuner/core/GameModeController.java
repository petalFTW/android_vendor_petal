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

package org.petalos.petaltuner.core;

import android.app.GameManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

public final class GameModeController {

    private static final String TAG = "PetalTuner/GameMode";

    private final Context mContext;

    public GameModeController(Context context) {
        mContext = context.getApplicationContext();
    }

    private GameManager manager() {
        return mContext.getSystemService(GameManager.class);
    }

    public boolean isSupported() {
        return manager() != null;
    }

    public void applyGameMode(String packageName, int mode) {
        GameManager manager = manager();
        if (manager == null || packageName == null) {
            return;
        }
        try {
            manager.setGameMode(packageName, mode);
        } catch (Exception e) {
            Log.w(TAG, "setGameMode failed for " + packageName, e);
        }
    }

    public void applyProfileMode(String packageName, String profileKey) {
        int mode;
        if ("performance".equals(profileKey) || "extreme".equals(profileKey)) {
            mode = GameManager.GAME_MODE_PERFORMANCE;
        } else if ("battery".equals(profileKey)) {
            mode = GameManager.GAME_MODE_BATTERY;
        } else {
            mode = GameManager.GAME_MODE_STANDARD;
        }
        applyGameMode(packageName, mode);
    }

    public void resetAll() {
        GameManager manager = manager();
        if (manager == null) {
            return;
        }
        PackageManager packageManager = mContext.getPackageManager();
        for (ApplicationInfo info : packageManager.getInstalledApplications(0)) {
            if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                continue;
            }
            applyGameMode(info.packageName, GameManager.GAME_MODE_STANDARD);
        }
    }
}
