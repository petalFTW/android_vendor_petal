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

import java.util.List;

public final class GpuController {

    private static final String KGSL = "/sys/class/kgsl/kgsl-3d0";

    private final StateBackup mBackup;

    public GpuController(Context context) {
        mBackup = new StateBackup(context);
    }

    public String devfreqBase() {
        if (Sysfs.exists(KGSL + "/devfreq/governor")) {
            return KGSL + "/devfreq";
        }
        List<String> nodes = Sysfs.listDirs("/sys/class/devfreq", null);
        for (String node : nodes) {
            String name = node.toLowerCase();
            if (name.contains("gpu") || name.contains("kgsl") || name.contains("3d")) {
                return node;
            }
        }
        return null;
    }

    public boolean isAvailable() {
        return devfreqBase() != null || Sysfs.exists(KGSL);
    }

    public String getGovernor() {
        String base = devfreqBase();
        return base == null ? null : Sysfs.read(base + "/governor");
    }

    public String[] getAvailableGovernors() {
        String base = devfreqBase();
        if (base == null) {
            return new String[0];
        }
        String value = Sysfs.read(base + "/available_governors");
        return value == null ? new String[0] : value.trim().split("\\s+");
    }

    public int getMinFreq() {
        String base = devfreqBase();
        return base == null ? 0 : Sysfs.readInt(base + "/min_freq", 0);
    }

    public int getMaxFreq() {
        String base = devfreqBase();
        return base == null ? 0 : Sysfs.readInt(base + "/max_freq", 0);
    }

    public int[] getAvailableFreqs() {
        String base = devfreqBase();
        return base == null ? new int[0] : Sysfs.parseFrequencies(
                Sysfs.read(base + "/available_frequencies"));
    }

    public boolean setGovernor(String governor) {
        String base = devfreqBase();
        if (base == null) {
            return false;
        }
        mBackup.capture(base + "/governor");
        return Sysfs.write(base + "/governor", governor);
    }

    public boolean setMinFreq(int hz) {
        String base = devfreqBase();
        if (base == null) {
            return false;
        }
        mBackup.capture(base + "/min_freq");
        return Sysfs.write(base + "/min_freq", Integer.toString(hz));
    }

    public boolean setMaxFreq(int hz) {
        String base = devfreqBase();
        if (base == null) {
            return false;
        }
        mBackup.capture(base + "/max_freq");
        return Sysfs.write(base + "/max_freq", Integer.toString(hz));
    }

    public int getNumPwrLevels() {
        return Sysfs.readInt(KGSL + "/num_pwrlevels", 0);
    }

    public boolean setMaxPwrLevel(int level) {
        mBackup.capture(KGSL + "/max_pwrlevel");
        return Sysfs.write(KGSL + "/max_pwrlevel", Integer.toString(level));
    }

    public boolean setMinPwrLevel(int level) {
        mBackup.capture(KGSL + "/min_pwrlevel");
        return Sysfs.write(KGSL + "/min_pwrlevel", Integer.toString(level));
    }

    public boolean setForceClkOn(boolean on) {
        mBackup.capture(KGSL + "/force_clk_on");
        return Sysfs.write(KGSL + "/force_clk_on", on ? "1" : "0");
    }

    public boolean setBusSplit(boolean on) {
        mBackup.capture(KGSL + "/bus_split");
        return Sysfs.write(KGSL + "/bus_split", on ? "1" : "0");
    }
}
