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

import org.petalos.config.PetalConfig;

/** status bar height, sigh */
public class StatusBarFragment extends PetalPreferenceFragment {

    private static final int MIN_HEIGHT_DP = 0;
    private static final int MAX_HEIGHT_DP = 60;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.status_bar, rootKey);

        SeekBarPreference height = findPreference("status_bar_height");
        height.setMin(MIN_HEIGHT_DP);
        height.setMax(MAX_HEIGHT_DP);

        int value = getInt(PetalConfig.KEY_STATUS_BAR_HEIGHT, PetalConfig.DISABLED);
        height.setValue(Math.max(0, value));
        height.setSummary(value <= 0
                ? getString(R.string.status_bar_height_summary_default)
                : getString(R.string.status_bar_height_summary, value));

        height.setOnPreferenceChangeListener((pref, newValue) -> {
            int newHeight = (int) newValue;
            putInt(PetalConfig.KEY_STATUS_BAR_HEIGHT, newHeight <= 0 ? PetalConfig.DISABLED : newHeight);
            height.setSummary(newHeight <= 0
                    ? getString(R.string.status_bar_height_summary_default)
                    : getString(R.string.status_bar_height_summary, newHeight));
            return true;
        });
    }
}
