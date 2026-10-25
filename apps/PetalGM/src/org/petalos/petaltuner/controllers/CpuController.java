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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CpuController {

    private static final String CPU_BASE = "/sys/devices/system/cpu";

    private static final Map<String, String> SCHED_BOOST = new LinkedHashMap<>();
    private static final Map<String, String> SCHED_DEFAULT = new LinkedHashMap<>();

    static {
        SCHED_BOOST.put("/proc/sys/kernel/sched_min_granularity_ns", "3000000");
        SCHED_BOOST.put("/proc/sys/kernel/sched_wakeup_granularity_ns", "2000000");
        SCHED_BOOST.put("/proc/sys/kernel/sched_latency_ns", "10000000");
        SCHED_BOOST.put("/proc/sys/kernel/sched_migration_cost_ns", "500000");
        SCHED_BOOST.put("/proc/sys/kernel/sched_nr_migrate", "8");
        SCHED_BOOST.put("/proc/sys/kernel/sched_tunable_scaling", "0");
        SCHED_BOOST.put("/proc/sys/kernel/sched_boost", "1");
        SCHED_BOOST.put("/sys/kernel/msm_performance/parameters/touchboost", "1");

        SCHED_DEFAULT.put("/proc/sys/kernel/sched_min_granularity_ns", "3000000");
        SCHED_DEFAULT.put("/proc/sys/kernel/sched_wakeup_granularity_ns", "4000000");
        SCHED_DEFAULT.put("/proc/sys/kernel/sched_latency_ns", "24000000");
        SCHED_DEFAULT.put("/proc/sys/kernel/sched_migration_cost_ns", "500000");
        SCHED_DEFAULT.put("/proc/sys/kernel/sched_nr_migrate", "32");
        SCHED_DEFAULT.put("/proc/sys/kernel/sched_tunable_scaling", "1");
        SCHED_DEFAULT.put("/proc/sys/kernel/sched_boost", "0");
        SCHED_DEFAULT.put("/sys/kernel/msm_performance/parameters/touchboost", "0");
    }

    private final StateBackup mBackup;

    public CpuController(Context context) {
        mBackup = new StateBackup(context);
    }

    public List<String> policies() {
        return Sysfs.listDirs(CPU_BASE + "/cpufreq", "policy");
    }

    public List<Integer> cores() {
        List<Integer> cores = new ArrayList<>();
        for (String cpu : Sysfs.listDirs(CPU_BASE, "cpu")) {
            String name = cpu.substring(cpu.lastIndexOf('/') + 1);
            try {
                cores.add(Integer.parseInt(name.substring(3)));
            } catch (NumberFormatException ignored) {
            }
        }
        return cores;
    }

    public String getGovernor(String policy) {
        return Sysfs.read(policy + "/scaling_governor");
    }

    public String[] getAvailableGovernors(String policy) {
        String value = Sysfs.read(policy + "/scaling_available_governors");
        return value == null ? new String[0] : value.trim().split("\\s+");
    }

    public int getMinFreq(String policy) {
        return Sysfs.readInt(policy + "/scaling_min_freq", 0);
    }

    public int getMaxFreq(String policy) {
        return Sysfs.readInt(policy + "/scaling_max_freq", 0);
    }

    public int getCpuInfoMin(String policy) {
        return Sysfs.readInt(policy + "/cpuinfo_min_freq", 0);
    }

    public int getCpuInfoMax(String policy) {
        return Sysfs.readInt(policy + "/cpuinfo_max_freq", 0);
    }

    public int[] getAvailableFreqs(String policy) {
        return Sysfs.parseFrequencies(Sysfs.read(policy + "/scaling_available_frequencies"));
    }

    public boolean setGovernor(String policy, String governor) {
        mBackup.capture(policy + "/scaling_governor");
        return Sysfs.write(policy + "/scaling_governor", governor);
    }

    public boolean setMinFreq(String policy, int khz) {
        mBackup.capture(policy + "/scaling_min_freq");
        String maxPath = policy + "/scaling_max_freq";
        if (khz > getMaxFreq(policy)) {
            Sysfs.write(maxPath, Integer.toString(khz));
        }
        return Sysfs.write(policy + "/scaling_min_freq", Integer.toString(khz));
    }

    public boolean setMaxFreq(String policy, int khz) {
        mBackup.capture(policy + "/scaling_max_freq");
        String minPath = policy + "/scaling_min_freq";
        if (khz < getMinFreq(policy)) {
            Sysfs.write(minPath, Integer.toString(khz));
        }
        return Sysfs.write(policy + "/scaling_max_freq", Integer.toString(khz));
    }

    public void setAllGovernors(String governor) {
        for (String policy : policies()) {
            setGovernor(policy, governor);
        }
    }

    public void setAllMaxFreq(boolean useCpuInfo) {
        for (String policy : policies()) {
            setMaxFreq(policy, useCpuInfo ? getCpuInfoMax(policy) : maxAvailable(policy));
        }
    }

    // biggest freq the policy actually advertises
    private int maxAvailable(String policy) {
        int max = 0;
        for (int freq : getAvailableFreqs(policy)) {
            if (freq > max) {
                max = freq;
            }
        }
        return max > 0 ? max : getCpuInfoMax(policy);
    }

    public void setAllMinFreq(int khz) {
        for (String policy : policies()) {
            setMinFreq(policy, khz);
        }
    }

    public void applyScheduler(boolean boost) {
        Map<String, String> values = boost ? SCHED_BOOST : SCHED_DEFAULT;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (Sysfs.exists(entry.getKey())) {
                mBackup.capture(entry.getKey());
                Sysfs.write(entry.getKey(), entry.getValue());
            }
        }
    }

    public boolean setCoreOnline(int core, boolean online) {
        String path = CPU_BASE + "/cpu" + core + "/online";
        if (!Sysfs.exists(path)) {
            return false;
        }
        mBackup.capture(path);
        return Sysfs.write(path, online ? "1" : "0");
    }
}
