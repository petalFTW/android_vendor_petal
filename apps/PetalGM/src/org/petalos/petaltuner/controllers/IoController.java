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

import android.content.Context;
import android.text.TextUtils;

import org.petalos.petaltuner.core.StateBackup;
import org.petalos.petaltuner.core.Sysfs;

import java.util.ArrayList;
import java.util.List;

public final class IoController {

    private final StateBackup mBackup;

    public IoController(Context context) {
        mBackup = new StateBackup(context);
    }

    public List<String> devices() {
        List<String> result = new ArrayList<>();
        for (String device : Sysfs.listDirs("/sys/block", null)) {
            String name = device.substring(device.lastIndexOf('/') + 1);
            if (name.startsWith("loop") || name.startsWith("ram") || name.startsWith("zram")
                    || name.startsWith("dm-")) {
                continue;
            }
            if (Sysfs.exists(device + "/queue/scheduler")) {
                result.add(device);
            }
        }
        return result;
    }

    public String getScheduler(String device) {
        String raw = Sysfs.read(device + "/queue/scheduler");
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('[');
        int end = raw.indexOf(']');
        if (start >= 0 && end > start) {
            return raw.substring(start + 1, end);
        }
        return raw.split("\\s+")[0];
    }

    public String[] getAvailableSchedulers(String device) {
        String raw = Sysfs.read(device + "/queue/scheduler");
        if (TextUtils.isEmpty(raw)) {
            return new String[0];
        }
        return raw.replace("[", "").replace("]", "").trim().split("\\s+");
    }

    public boolean setScheduler(String device, String scheduler) {
        mBackup.capture(device + "/queue/scheduler");
        return Sysfs.write(device + "/queue/scheduler", scheduler);
    }

    public boolean applyScheduler(String scheduler) {
        boolean applied = false;
        for (String device : devices()) {
            for (String available : getAvailableSchedulers(device)) {
                if (available.equals(scheduler)) {
                    applied |= setScheduler(device, scheduler);
                    break;
                }
            }
        }
        return applied;
    }

    public int getReadAhead(String device) {
        return Sysfs.readInt(device + "/queue/read_ahead_kb", 0);
    }

    public boolean setReadAhead(String device, int kb) {
        mBackup.capture(device + "/queue/read_ahead_kb");
        return Sysfs.write(device + "/queue/read_ahead_kb", Integer.toString(kb));
    }

    public boolean applyReadAhead(int kb) {
        boolean applied = false;
        for (String device : devices()) {
            applied |= setReadAhead(device, kb);
        }
        return applied;
    }
}
