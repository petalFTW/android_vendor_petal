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

package org.petalos.petaltuner.fragments;

import android.os.Bundle;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import org.petalos.petaltuner.R;
import org.petalos.petaltuner.core.Prefs;
import org.petalos.petaltuner.core.ProfileManager;
import org.petalos.petaltuner.controllers.CpuController;
import org.petalos.petaltuner.controllers.GpuController;
import org.petalos.petaltuner.controllers.IoController;
import org.petalos.petaltuner.core.Sysfs;

import java.util.List;

public final class PerformanceFragment extends BasePreferenceFragment {

    // "CPU 0-5 · up to 3.5 GHz" reads better than "policy0".
    private static String clusterLabel(CpuController cpu, String policy) {
        String cpus = Sysfs.read(policy + "/related_cpus");
        String range = cpus == null ? policy.substring(policy.lastIndexOf('/') + 1) : "CPU "
                + cpus.trim().replaceAll("\\s+", ",");
        return range + " · up to " + Sysfs.mhz(cpu.getCpuInfoMax(policy)) + " MHz";
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        createScreen();

        category(R.string.dashboard_section_perf);

        ListPreference profile = listPref(R.string.profile_active_title);
        profile.setEntries(ProfileManager.labels());
        profile.setEntryValues(ProfileManager.keys());
        profile.setValue(engine.getActiveProfile());
        profile.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        profile.setOnPreferenceChangeListener((preference, value) -> {
            String key = (String) value;
            engine.run(() -> engine.applyProfile(key));
            toast(getString(R.string.toast_applied, ProfileManager.get(key).label));
            return true;
        });

        CpuController cpu = engine.performance().cpu();
        List<String> policies = cpu.policies();
        if (!policies.isEmpty()) {
            category(R.string.cpu_category);
            String[] governors = cpu.getAvailableGovernors(policies.get(0));
            if (governors.length > 0) {
                ListPreference governor = listPref(R.string.cpu_governor_title);
                governor.setEntries(governors);
                governor.setEntryValues(governors);
                governor.setValue(cpu.getGovernor(policies.get(0)));
                governor.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
                governor.setOnPreferenceChangeListener((preference, value) -> {
                    engine.run(() -> engine.performance().setCpuGovernor((String) value));
                    return true;
                });
            }
            // One pair per cluster: each has its own frequency table.
            for (String policy : policies) {
                int[] freqs = cpu.getAvailableFreqs(policy);
                if (freqs.length == 0) continue;
                CharSequence[] labels = new CharSequence[freqs.length];
                CharSequence[] values = new CharSequence[freqs.length];
                for (int i = 0; i < freqs.length; i++) {
                    labels[i] = Sysfs.mhz(freqs[i]) + " MHz";
                    values[i] = Integer.toString(freqs[i]);
                }
                String cluster = clusterLabel(cpu, policy);
                ListPreference min = listPref(R.string.cpu_min_freq_title);
                min.setTitle(getString(R.string.cpu_min_freq_title) + " · " + cluster);
                min.setEntries(labels);
                min.setEntryValues(values);
                min.setValue(Integer.toString(cpu.getMinFreq(policy)));
                min.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
                min.setOnPreferenceChangeListener((preference, value) -> {
                    int khz = Integer.parseInt((String) value);
                    engine.run(() -> engine.performance().setCpuMinFreq(policy, khz));
                    return true;
                });
                ListPreference max = listPref(R.string.cpu_max_freq_title);
                max.setTitle(getString(R.string.cpu_max_freq_title) + " · " + cluster);
                max.setEntries(labels);
                max.setEntryValues(values);
                max.setValue(Integer.toString(cpu.getMaxFreq(policy)));
                max.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
                max.setOnPreferenceChangeListener((preference, value) -> {
                    int khz = Integer.parseInt((String) value);
                    engine.run(() -> engine.performance().setCpuMaxFreq(policy, khz));
                    return true;
                });
            }
        }

        GpuController gpu = engine.performance().gpu();
        if (gpu.isAvailable()) {
            category(R.string.gpu_category);
            String[] governors = gpu.getAvailableGovernors();
            if (governors.length > 0) {
                ListPreference governor = listPref(R.string.gpu_governor_title);
                governor.setEntries(governors);
                governor.setEntryValues(governors);
                governor.setValue(gpu.getGovernor());
                governor.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
                governor.setOnPreferenceChangeListener((preference, value) -> {
                    engine.run(() -> engine.performance().setGpuGovernor((String) value));
                    return true;
                });
            }
            int[] freqs = gpu.getAvailableFreqs();
            if (freqs.length > 0) {
                CharSequence[] labels = new CharSequence[freqs.length];
                CharSequence[] values = new CharSequence[freqs.length];
                for (int i = 0; i < freqs.length; i++) {
                    labels[i] = Math.round(freqs[i] / 1000000f) + " MHz";
                    values[i] = Integer.toString(freqs[i]);
                }
                ListPreference max = listPref(R.string.gpu_max_freq_title);
                max.setEntries(labels);
                max.setEntryValues(values);
                max.setValue(Integer.toString(gpu.getMaxFreq()));
                max.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
                max.setOnPreferenceChangeListener((preference, value) -> {
                    int hz = Integer.parseInt((String) value);
                    engine.run(() -> engine.performance().setGpuMaxFreq(hz));
                    return true;
                });
            }
        }

        IoController io = engine.performance().io();
        List<String> devices = io.devices();
        if (!devices.isEmpty()) {
            category(R.string.io_category);
            String[] schedulers = io.getAvailableSchedulers(devices.get(0));
            if (schedulers.length > 0) {
                ListPreference scheduler = listPref(R.string.io_scheduler_title);
                scheduler.setEntries(schedulers);
                scheduler.setEntryValues(schedulers);
                scheduler.setValue(io.getScheduler(devices.get(0)));
                scheduler.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
                scheduler.setOnPreferenceChangeListener((preference, value) -> {
                    engine.run(() -> engine.performance().setIoScheduler((String) value));
                    return true;
                });
            }
        }

        category(R.string.sched_category);
        SwitchPreferenceCompat touch = switchPref(R.string.touch_rate_title,
                R.string.touch_rate_summary);
        touch.setChecked(engine.prefs().getBool(Prefs.TOUCH_BOOST, false));
        touch.setOnPreferenceChangeListener((preference, value) -> {
            engine.run(() -> engine.performance().setTouchBoost((Boolean) value));
            return true;
        });

        boolean expert = engine.prefs().getBool(Prefs.EXPERT_UNLOCKED, false);
        SwitchPreferenceCompat thermal = switchPref(R.string.thermal_disable_title,
                R.string.thermal_disable_summary);
        thermal.setEnabled(expert);
        thermal.setChecked(engine.prefs().getBool(Prefs.THERMAL_DISABLE, false));
        thermal.setOnPreferenceChangeListener((preference, value) -> {
            engine.run(() -> engine.performance().setThermalOverride((Boolean) value));
            return true;
        });

        category(R.string.category_expert);
        Preference restore = action(R.string.profile_restore_title,
                R.string.profile_restore_summary);
        restore.setOnPreferenceClickListener(preference -> {
            engine.run(engine::restoreDefaults);
            toast(R.string.toast_restored);
            return true;
        });
    }
}
