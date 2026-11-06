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

import org.petalos.petaltuner.core.Prefs;
import org.petalos.petaltuner.core.Profile;
import org.petalos.petaltuner.core.StateBackup;
import org.petalos.petaltuner.core.Sysfs;

public final class PerformanceController {

    private final CpuController mCpu;
    private final GpuController mGpu;
    private final IoController mIo;
    private final MemoryController mMemory;
    private final ThermalController mThermal;
    private final Prefs mPrefs;
    private final StateBackup mBackup;

    public PerformanceController(Context context) {
        mBackup = new StateBackup(context);
        mCpu = new CpuController(context);
        mGpu = new GpuController(context);
        mIo = new IoController(context);
        mMemory = new MemoryController(context);
        mThermal = new ThermalController(context);
        mPrefs = new Prefs(context);
    }

    public CpuController cpu() {
        return mCpu;
    }

    public GpuController gpu() {
        return mGpu;
    }

    public IoController io() {
        return mIo;
    }

    public MemoryController memory() {
        return mMemory;
    }

    public ThermalController thermal() {
        return mThermal;
    }

    // Everything a profile may touch; restored before each profile so they never stack.
    private static final String[] PROFILE_NODES = {
            "/sys/devices/system/cpu/", "/proc/sys/kernel/", "/sys/kernel/msm_performance/",
            "/sys/class/kgsl/", "/sys/class/devfreq/", "/sys/block/", "/sys/class/thermal/",
            "/proc/sys/vm/",
    };

    /**
     * Back to the device's captured stock values, then only this profile's changes on top.
     * Balanced is therefore "stock", not a set of guessed default tunables.
     */
    public boolean apply(Profile profile) {
        Sysfs.beginBatch();
        try {
            mBackup.restoreUnder(PROFILE_NODES);
            // the user's own memory and thermal choices outlive profile switches
            if (mPrefs.getBool(Prefs.THERMAL_DISABLE, false)) mThermal.setThrottlingEnabled(false);
            if ("custom".equals(profile.key)) {
                applyCustomValues();
            } else {
                if (profile.cpuGovernor != null) setCpuGovernorIfAvailable(profile.cpuGovernor);
                if (profile.cpuMaxLock) {
                    for (String policy : mCpu.policies()) {
                        mCpu.setMaxFreq(policy, mCpu.getCpuInfoMax(policy));
                    }
                }
                if (profile.gpuGovernor != null) setGpuGovernorIfAvailable(profile.gpuGovernor);
                if (profile.gpuMaxLock) {
                    int max = maxOf(mGpu.getAvailableFreqs());
                    if (max > 0) mGpu.setMaxFreq(max);
                }
                if (profile.schedulerBoost) mCpu.applyScheduler(true);
                if (profile.ioBurst) {
                    mIo.applyScheduler("none");
                    mIo.applyReadAhead(2048);
                }
                if (profile.reduceMemoryPressure) mMemory.applyGamingProfile();
                if (profile.thermalOverride) mThermal.setThrottlingEnabled(false);
            }
        } catch (RuntimeException e) {
            android.util.Log.w(Sysfs.TAG, "profile " + profile.key + " partly applied", e);
        }
        return Sysfs.endBatch();
    }

    private void applyCustomValues() {
        String cpuGovernor = mPrefs.getString(Prefs.CUSTOM_CPU_GOVERNOR, null);
        if (cpuGovernor != null) setCpuGovernorIfAvailable(cpuGovernor);
        for (String policy : mCpu.policies()) {
            int min = mPrefs.getInt(customKey(Prefs.CUSTOM_CPU_MIN, policy), 0);
            int max = mPrefs.getInt(customKey(Prefs.CUSTOM_CPU_MAX, policy), 0);
            if (max > 0) mCpu.setMaxFreq(policy, max);
            if (min > 0) mCpu.setMinFreq(policy, min);
        }
        String gpuGovernor = mPrefs.getString(Prefs.CUSTOM_GPU_GOVERNOR, null);
        if (gpuGovernor != null) setGpuGovernorIfAvailable(gpuGovernor);
        int gpuMax = mPrefs.getInt(Prefs.CUSTOM_GPU_MAX, 0);
        if (gpuMax > 0) mGpu.setMaxFreq(gpuMax);
        int gpuMin = mPrefs.getInt(Prefs.CUSTOM_GPU_MIN, 0);
        if (gpuMin > 0) mGpu.setMinFreq(gpuMin);
        String io = mPrefs.getString(Prefs.CUSTOM_IO_SCHEDULER, null);
        if (io != null) mIo.applyScheduler(io);
        int readAhead = mPrefs.getInt(Prefs.CUSTOM_IO_READAHEAD, 0);
        if (readAhead > 0) mIo.applyReadAhead(readAhead);
    }

    /** Per-cluster key: a little-core frequency must never cap the big cores. */
    public static String customKey(String base, String policy) {
        return base + "_" + policy.substring(policy.lastIndexOf('/') + 1);
    }

    private void setCpuGovernorIfAvailable(String governor) {
        for (String policy : mCpu.policies()) {
            if (java.util.Arrays.asList(mCpu.getAvailableGovernors(policy)).contains(governor)) {
                mCpu.setGovernor(policy, governor);
            }
        }
    }

    private void setGpuGovernorIfAvailable(String governor) {
        if (java.util.Arrays.asList(mGpu.getAvailableGovernors()).contains(governor)) {
            mGpu.setGovernor(governor);
        }
    }

    private static int maxOf(int[] values) {
        int max = 0;
        for (int value : values) max = Math.max(max, value);
        return max;
    }

    public boolean applyCustom() {
        return apply(org.petalos.petaltuner.core.ProfileManager.get("custom"));
    }

    public void setCpuGovernor(String governor) {
        mPrefs.putString(Prefs.CUSTOM_CPU_GOVERNOR, governor);
        Sysfs.beginBatch();
        setCpuGovernorIfAvailable(governor);
        Sysfs.endBatch();
    }

    public void setCpuMaxFreq(String policy, int khz) {
        mPrefs.putInt(customKey(Prefs.CUSTOM_CPU_MAX, policy), khz);
        mCpu.setMaxFreq(policy, khz);
    }

    public void setCpuMinFreq(String policy, int khz) {
        mPrefs.putInt(customKey(Prefs.CUSTOM_CPU_MIN, policy), khz);
        mCpu.setMinFreq(policy, khz);
    }

    public void setGpuGovernor(String governor) {
        mPrefs.putString(Prefs.CUSTOM_GPU_GOVERNOR, governor);
        setGpuGovernorIfAvailable(governor);
    }

    public void setGpuMaxFreq(int hz) {
        mPrefs.putInt(Prefs.CUSTOM_GPU_MAX, hz);
        mGpu.setMaxFreq(hz);
    }

    public void setGpuMinFreq(int hz) {
        mPrefs.putInt(Prefs.CUSTOM_GPU_MIN, hz);
        mGpu.setMinFreq(hz);
    }

    public void setIoScheduler(String scheduler) {
        mPrefs.putString(Prefs.CUSTOM_IO_SCHEDULER, scheduler);
        mIo.applyScheduler(scheduler);
    }

    public void setIoReadAhead(int kb) {
        mPrefs.putInt(Prefs.CUSTOM_IO_READAHEAD, kb);
        mIo.applyReadAhead(kb);
    }

    public void setThermalOverride(boolean override) {
        mPrefs.putBool(Prefs.THERMAL_DISABLE, override);
        mThermal.setThrottlingEnabled(!override);
    }

    public void setTouchBoost(boolean enabled) {
        mPrefs.putBool(Prefs.TOUCH_BOOST, enabled);
        String node = Sysfs.firstExisting(
                "/sys/kernel/msm_performance/parameters/touchboost",
                "/proc/touchpanel/game_switch_enable",
                "/sys/touchpanel/game_switch_enable");
        if (node != null) {
            Sysfs.write(node, enabled ? "1" : "0");
        }
    }
}
