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
import androidx.preference.SwitchPreferenceCompat;

import org.petalos.petaltuner.R;
import org.petalos.petaltuner.core.Prefs;

public final class DisplayFragment extends BasePreferenceFragment {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        createScreen();

        category(R.string.category_display);

        ListPreference refresh = listPref(R.string.display_refresh_title);
        refresh.setEntries(R.array.refresh_entries);
        refresh.setEntryValues(R.array.refresh_values);
        int current = engine.prefs().getInt(Prefs.REFRESH_RATE, 0);
        if (current > 0) {
            refresh.setValue(Integer.toString(current));
        }
        refresh.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        refresh.setOnPreferenceChangeListener((preference, value) -> {
            int rate = Integer.parseInt((String) value);
            engine.prefs().putInt(Prefs.REFRESH_RATE, rate);
            engine.prefs().putInt(Prefs.MIN_REFRESH_RATE, 60);
            engine.display().applyRefreshRate(rate, 60);
            return true;
        });

        ListPreference animation = listPref(R.string.display_animations_title);
        animation.setEntries(R.array.animation_entries);
        animation.setEntryValues(R.array.animation_values);
        animation.setValue(engine.prefs().getString(Prefs.ANIMATION_SCALE, "1.0"));
        animation.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        animation.setOnPreferenceChangeListener((preference, value) -> {
            engine.prefs().putString(Prefs.ANIMATION_SCALE, (String) value);
            engine.display().applyAnimationScale(Float.parseFloat((String) value));
            return true;
        });

        SwitchPreferenceCompat forceGpu = switchPref(R.string.display_force_gpu_title, 0);
        forceGpu.setChecked(engine.prefs().getBool(Prefs.FORCE_GPU, false));
        forceGpu.setOnPreferenceChangeListener((preference, value) -> {
            engine.prefs().putBool(Prefs.FORCE_GPU, (Boolean) value);
            engine.display().setForceGpuRendering((Boolean) value);
            return true;
        });

        SwitchPreferenceCompat hwOverlays = switchPref(R.string.display_disable_hw_overlays_title,
                0);
        hwOverlays.setChecked(engine.prefs().getBool(Prefs.DISABLE_HW_OVERLAYS, false));
        hwOverlays.setOnPreferenceChangeListener((preference, value) -> {
            engine.prefs().putBool(Prefs.DISABLE_HW_OVERLAYS, (Boolean) value);
            engine.display().setDisableHwOverlays((Boolean) value);
            return true;
        });

        SwitchPreferenceCompat immersive = switchPref(R.string.display_immersive_title,
                R.string.display_immersive_summary);
        immersive.setChecked(engine.prefs().getBool(Prefs.IMMERSIVE, false));
        immersive.setOnPreferenceChangeListener((preference, value) -> {
            engine.prefs().putBool(Prefs.IMMERSIVE, (Boolean) value);
            return true;
        });
    }
}
