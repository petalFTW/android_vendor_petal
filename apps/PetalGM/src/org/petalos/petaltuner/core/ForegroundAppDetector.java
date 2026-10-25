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

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;

public final class ForegroundAppDetector {

    private static final String TAG = "PetalTuner/Foreground";
    private static final long WINDOW_MS = 3600000;

    private final Context mContext;
    private final GmConfig mConfig;

    private String mLastPackage;
    private long mLastTime;
    private long mLastQuery;

    public ForegroundAppDetector(Context context) {
        mContext = context.getApplicationContext();
        mConfig = new GmConfig(context);
    }

    public String getForegroundPackage() {
        PowerManager power = mContext.getSystemService(PowerManager.class);
        if (power != null && !power.isInteractive()) return null;
        KeyguardManager keyguard = mContext.getSystemService(KeyguardManager.class);
        if (keyguard != null && keyguard.isKeyguardLocked()) return null;
        String top = topTaskPackage();
        if (top != null) {
            mLastPackage = top;
            return top;
        }
        UsageStatsManager manager = mContext.getSystemService(UsageStatsManager.class);
        if (manager == null) {
            return mLastPackage;
        }
        long now = System.currentTimeMillis();
        if (now - mLastQuery < 1000) {
            return mLastPackage;
        }
        mLastQuery = now;
        try {
            // last boot's game isn't still running, mate
            long sinceBoot = now - SystemClock.elapsedRealtime();
            UsageEvents events = manager.queryEvents(Math.max(now - WINDOW_MS, sinceBoot), now);
            UsageEvents.Event event = new UsageEvents.Event();
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED
                        && event.getTimeStamp() >= mLastTime) {
                    mLastTime = event.getTimeStamp();
                    mLastPackage = event.getPackageName();
                } else if (event.getEventType() == UsageEvents.Event.ACTIVITY_PAUSED
                        && event.getTimeStamp() >= mLastTime
                        && event.getPackageName().equals(mLastPackage)) {
                    mLastTime = event.getTimeStamp();
                    mLastPackage = null;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "queryEvents failed", e);
        }
        return mLastPackage;
    }

    @SuppressWarnings("deprecation")
    private String topTaskPackage() {
        try {
            ActivityManager am = mContext.getSystemService(ActivityManager.class);
            if (am == null) {
                return null;
            }
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty() || tasks.get(0).topActivity == null) {
                return null;
            }
            return tasks.get(0).topActivity.getPackageName();
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isSelectedGame(String packageName) {
        return mConfig.isGame(packageName);
    }

    public boolean isForegroundGame() {
        String foreground = getForegroundPackage();
        return foreground != null && isSelectedGame(foreground);
    }

    public String getForegroundGame() {
        String foreground = getForegroundPackage();
        return isSelectedGame(foreground) ? foreground : null;
    }
}
