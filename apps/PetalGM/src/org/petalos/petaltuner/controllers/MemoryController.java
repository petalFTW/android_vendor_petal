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

package org.petalos.petaltuner.controllers;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import org.petalos.petaltuner.core.StateBackup;
import org.petalos.petaltuner.core.Sysfs;

import java.util.List;

public final class MemoryController {

    private static final String VM = "/proc/sys/vm";
    private static final String ZRAM = "/sys/block/zram0";

    private final Context mContext;
    private final StateBackup mBackup;

    public MemoryController(Context context) {
        mContext = context.getApplicationContext();
        mBackup = new StateBackup(context);
    }

    public int getSwappiness() {
        return Sysfs.readInt(VM + "/swappiness", 60);
    }

    public boolean setSwappiness(int value) {
        mBackup.capture(VM + "/swappiness");
        return Sysfs.write(VM + "/swappiness", Integer.toString(value));
    }

    public boolean setExtraFreeKbytes(int value) {
        mBackup.capture(VM + "/extra_free_kbytes");
        return Sysfs.write(VM + "/extra_free_kbytes", Integer.toString(value));
    }

    public boolean setVfsCachePressure(int value) {
        mBackup.capture(VM + "/vfs_cache_pressure");
        return Sysfs.write(VM + "/vfs_cache_pressure", Integer.toString(value));
    }

    public boolean applyGamingProfile() {
        boolean ok = false;
        ok |= setSwappiness(100);
        if (Sysfs.exists(VM + "/extra_free_kbytes")) {
            ok |= setExtraFreeKbytes(32768);
        }
        ok |= setVfsCachePressure(120);
        return ok;
    }

    public String getZramAlgorithm() {
        return Sysfs.read(ZRAM + "/comp_algorithm");
    }

    public boolean setZramAlgorithm(String algorithm) {
        if (!Sysfs.exists(ZRAM + "/comp_algorithm")) {
            return false;
        }
        mBackup.capture(ZRAM + "/comp_algorithm");
        return Sysfs.write(ZRAM + "/comp_algorithm", algorithm);
    }

    public boolean setZramMemLimit(long bytes) {
        if (!Sysfs.exists(ZRAM + "/mem_limit")) {
            return false;
        }
        mBackup.capture(ZRAM + "/mem_limit");
        return Sysfs.write(ZRAM + "/mem_limit", Long.toString(bytes));
    }

    public ActivityManager.MemoryInfo getMemoryInfo() {
        ActivityManager activityManager = mContext.getSystemService(ActivityManager.class);
        if (activityManager == null) {
            return null;
        }
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(info);
        return info;
    }

    public int getMemTotalKb() {
        // Shell takes argv, not a string. run() is the one we want here
        List<String> lines = org.petalos.petaltuner.core.Shell.sh("cat /proc/meminfo").output;
        for (String line : lines) {
            if (line.startsWith("MemTotal:")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    try {
                        return Integer.parseInt(parts[1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return 0;
    }

    public void killBackgroundProcesses() {
        ActivityManager activityManager = mContext.getSystemService(ActivityManager.class);
        PackageManager packageManager = mContext.getPackageManager();
        if (activityManager == null) {
            return;
        }
        List<ApplicationInfo> packages = packageManager.getInstalledApplications(0);
        for (ApplicationInfo info : packages) {
            if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                continue;
            }
            try {
                activityManager.killBackgroundProcesses(info.packageName);
            } catch (SecurityException e) {
                return;
            }
        }
    }

    public void trimCaches() {
        ActivityManager activityManager = mContext.getSystemService(ActivityManager.class);
        if (activityManager != null) {
            activityManager.killBackgroundProcesses(mContext.getPackageName());
        }
    }
}
