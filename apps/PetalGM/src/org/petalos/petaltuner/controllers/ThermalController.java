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

import org.petalos.petaltuner.core.StateBackup;
import org.petalos.petaltuner.core.Sysfs;

import java.util.ArrayList;
import java.util.List;

public final class ThermalController {

    private static final String THERMAL = "/sys/class/thermal";

    private final StateBackup mBackup;

    public ThermalController(Context context) {
        mBackup = new StateBackup(context);
    }

    public List<String> zones() {
        return Sysfs.listDirs(THERMAL, "thermal_zone");
    }

    public String zoneType(String zone) {
        return Sysfs.read(zone + "/type");
    }

    public int getTemperature(String zone) {
        return Sysfs.readInt(zone + "/temp", Integer.MIN_VALUE);
    }

    public int getMode(String zone) {
        return Sysfs.readInt(zone + "/mode", 0);
    }

    public boolean setMode(String zone, int mode) {
        if (!Sysfs.exists(zone + "/mode")) {
            return false;
        }
        mBackup.capture(zone + "/mode");
        return Sysfs.write(zone + "/mode", Integer.toString(mode));
    }

    public boolean setThrottlingEnabled(boolean enabled) {
        boolean ok = false;
        for (String zone : zones()) {
            if (Sysfs.exists(zone + "/mode")) {
                ok |= setMode(zone, enabled ? 0 : 1);
            }
        }
        return ok;
    }

    public int getHottestTemperature() {
        int hottest = Integer.MIN_VALUE;
        for (String zone : zones()) {
            int temperature = getTemperature(zone);
            if (temperature != Integer.MIN_VALUE && temperature > hottest) {
                hottest = temperature;
            }
        }
        return hottest;
    }

    public String getHottestZone() {
        int hottest = Integer.MIN_VALUE;
        String name = null;
        for (String zone : zones()) {
            int temperature = getTemperature(zone);
            if (temperature != Integer.MIN_VALUE && temperature > hottest) {
                hottest = temperature;
                name = zoneType(zone);
            }
        }
        return name;
    }

    public List<String> coolingDevices() {
        List<String> result = new ArrayList<>();
        for (String device : Sysfs.listDirs(THERMAL, "cooling_device")) {
            result.add(device);
        }
        return result;
    }
}
