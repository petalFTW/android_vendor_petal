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
import org.petalos.petaltuner.core.Sysfs;

public final class PerformanceController {

    private final CpuController mCpu;
    private final GpuController mGpu;
    private final IoController mIo;
    private final MemoryController mMemory;
    private final ThermalController mThermal;
    private final Prefs mPrefs;

    public PerformanceController(Context context) {
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

    public boolean apply(Profile profile) {
        boolean ok = false;
        String cpuGovernor = profile.cpuGovernor;
        if ("custom".equals(profile.key)) {
            cpuGovernor = mPrefs.getString(Prefs.CUSTOM_CPU_GOVERNOR, "schedutil");
        }
        if (cpuGovernor != null) {
            mCpu.setAllGovernors(cpuGovernor);
            ok = true;
        }
        if (profile.cpuMaxLock) {
            for (String policy : mCpu.policies()) {
                mCpu.setMinFreq(policy, mCpu.getCpuInfoMin(policy));
                mCpu.setMaxFreq(policy, mCpu.getCpuInfoMax(policy));
            }
            ok = true;
        }
        if (profile.gpuGovernor != null && mGpu.isAvailable()) {
            mGpu.setGovernor(profile.gpuGovernor);
            ok = true;
        }
        if (profile.gpuMaxLock && mGpu.devfreqBase() != null) {
            int[] freqs = mGpu.getAvailableFreqs();
            if (freqs.length > 0) {
                mGpu.setMaxFreq(freqs[freqs.length - 1]);
            }
            ok = true;
        }
        if (profile.schedulerBoost) {
            mCpu.applyScheduler(true);
            ok = true;
        } else {
            mCpu.applyScheduler(false);
        }
        if (profile.ioBurst) {
            mIo.applyScheduler("none");
            mIo.applyReadAhead(2048);
            ok = true;
        } else {
            mIo.applyScheduler("mq-deadline");
            mIo.applyReadAhead(512);
        }
        if (profile.reduceMemoryPressure) {
            mMemory.applyGamingProfile();
            ok = true;
        }
        if (profile.thermalOverride) {
            mThermal.setThrottlingEnabled(false);
            ok = true;
        }
        return ok;
    }

    public boolean applyCustom() {
        return apply(org.petalos.petaltuner.core.ProfileManager.get("custom"));
    }

    public void setCpuGovernor(String governor) {
        mPrefs.putString(Prefs.CUSTOM_CPU_GOVERNOR, governor);
        mCpu.setAllGovernors(governor);
    }

    public void setCpuMaxFreq(int khz) {
        mPrefs.putInt(Prefs.CUSTOM_CPU_MAX, khz);
        for (String policy : mCpu.policies()) {
            mCpu.setMaxFreq(policy, khz);
        }
    }

    public void setCpuMinFreq(int khz) {
        mPrefs.putInt(Prefs.CUSTOM_CPU_MIN, khz);
        for (String policy : mCpu.policies()) {
            mCpu.setMinFreq(policy, khz);
        }
    }

    public void setGpuGovernor(String governor) {
        mPrefs.putString(Prefs.CUSTOM_GPU_GOVERNOR, governor);
        mGpu.setGovernor(governor);
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
