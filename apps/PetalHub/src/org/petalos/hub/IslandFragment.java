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
import android.provider.Settings;

import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import org.petalos.config.PetalConfig;

// island settings, stored in secure
public class IslandFragment extends PetalPreferenceFragment {

    private static final String KEY_ISLAND_ENABLED = "island_enabled";
    private static final String KEY_GLASS_NOTIFICATIONS = "island_glass_notifications";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.island, rootKey);

        SwitchPreferenceCompat enabled = findPreference("island_enabled");
        enabled.setChecked(getSecureInt(KEY_ISLAND_ENABLED, 1) == 1);
        enabled.setOnPreferenceChangeListener((pref, newValue) -> {
            putSecureInt(KEY_ISLAND_ENABLED, (Boolean) newValue ? 1 : 0);
            return true;
        });

        SwitchPreferenceCompat glass = findPreference("island_glass_notifications");
        glass.setChecked(getSecureInt(KEY_GLASS_NOTIFICATIONS, 1) == 1);
        glass.setOnPreferenceChangeListener((pref, newValue) -> {
            putSecureInt(KEY_GLASS_NOTIFICATIONS, (Boolean) newValue ? 1 : 0);
            return true;
        });

        SeekBarPreference blur = findPreference("island_blur_intensity");
        blur.setMin(0);
        blur.setMax(100);
        int blurValue = getInt(PetalConfig.KEY_ISLAND_BLUR_INTENSITY,
                PetalConfig.DEFAULT_ISLAND_BLUR_INTENSITY);
        blur.setValue(blurValue);
        blur.setSummary(getString(R.string.island_blur_summary, blurValue));
        blur.setOnPreferenceChangeListener((pref, newValue) -> {
            int value = (int) newValue;
            putInt(PetalConfig.KEY_ISLAND_BLUR_INTENSITY, value);
            blur.setSummary(getString(R.string.island_blur_summary, value));
            return true;
        });

        SwitchPreferenceCompat bubble = findPreference("island_bubble");
        bubble.setChecked(PetalConfig.isIslandBubbleEnabled(requireContext()));
        bubble.setOnPreferenceChangeListener((pref, newValue) -> {
            putInt(PetalConfig.KEY_ISLAND_BUBBLE, (Boolean) newValue ? 1 : 0);
            return true;
        });

        SeekBarPreference popout = findPreference("island_popout");
        popout.setMin(0);
        popout.setMax(100);
        int popoutValue = getInt(PetalConfig.KEY_ISLAND_POPOUT,
                PetalConfig.DEFAULT_ISLAND_POPOUT);
        popout.setValue(popoutValue);
        popout.setSummary(getString(R.string.island_popout_summary, popoutValue));
        popout.setOnPreferenceChangeListener((pref, newValue) -> {
            int value = (int) newValue;
            putInt(PetalConfig.KEY_ISLAND_POPOUT, value);
            popout.setSummary(getString(R.string.island_popout_summary, value));
            return true;
        });

        SeekBarPreference chroma = findPreference("island_chroma");
        chroma.setMin(0);
        chroma.setMax(100);
        int chromaValue = getInt(PetalConfig.KEY_ISLAND_CHROMA,
                PetalConfig.DEFAULT_ISLAND_CHROMA);
        chroma.setValue(chromaValue);
        chroma.setSummary(getString(R.string.island_chroma_summary, chromaValue));
        chroma.setOnPreferenceChangeListener((pref, newValue) -> {
            int value = (int) newValue;
            putInt(PetalConfig.KEY_ISLAND_CHROMA, value);
            chroma.setSummary(getString(R.string.island_chroma_summary, value));
            return true;
        });
    }

    private int getSecureInt(String key, int defaultValue) {
        return Settings.Secure.getInt(getContentResolver(), key, defaultValue);
    }

    private void putSecureInt(String key, int value) {
        Settings.Secure.putInt(getContentResolver(), key, value);
    }
}
