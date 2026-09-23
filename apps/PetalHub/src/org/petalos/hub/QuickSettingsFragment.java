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

package org.petalos.hub;

import android.os.Bundle;

import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import org.petalos.config.PetalConfig;

/** Shared shade blur and Quick Settings tile style. */
public class QuickSettingsFragment extends PetalPreferenceFragment {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.quick_settings, rootKey);

        SwitchPreferenceCompat skin = findPreference("qs_skin_enabled");
        skin.setChecked(PetalConfig.isQsSkinEnabled(requireContext()));
        skin.setOnPreferenceChangeListener((pref, newValue) -> {
            putInt(PetalConfig.KEY_QS_SKIN_ENABLED, (Boolean) newValue ? 1 : 0);
            return true;
        });

        SeekBarPreference emission = findPreference("qs_emission");
        emission.setMin(0);
        emission.setMax(100);
        int emissionValue = PetalConfig.getQsEmission(requireContext());
        emission.setValue(emissionValue);
        emission.setSummary(getString(R.string.qs_emission_summary, emissionValue));
        emission.setOnPreferenceChangeListener((pref, newValue) -> {
            int value = (int) newValue;
            putInt(PetalConfig.KEY_QS_EMISSION, value);
            emission.setSummary(getString(R.string.qs_emission_summary, value));
            return true;
        });

        SeekBarPreference blur = findPreference("qs_blur_intensity");
        blur.setMin(0);
        blur.setMax(100);
        int blurValue = getInt(PetalConfig.KEY_QS_BLUR_INTENSITY, PetalConfig.DEFAULT_BLUR_INTENSITY);
        blur.setValue(blurValue);
        blur.setSummary(getString(R.string.qs_blur_summary, blurValue));
        blur.setOnPreferenceChangeListener((pref, newValue) -> {
            int value = (int) newValue;
            putInt(PetalConfig.KEY_QS_BLUR_INTENSITY, value);
            blur.setSummary(getString(R.string.qs_blur_summary, value));
            return true;
        });
    }
}
