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

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import org.petalos.config.PetalConfig;

/** lockscreen clock and media art */
public class LockscreenFragment extends PetalPreferenceFragment {

    private ListPreference mClockStyle;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.lockscreen, rootKey);

        SwitchPreferenceCompat cover = findPreference("lockscreen_media_cover");
        SwitchPreferenceCompat cd = findPreference("lockscreen_cd_player");
        cover.setChecked(PetalConfig.isLockMediaCoverEnabled(requireContext()));
        cover.setOnPreferenceChangeListener((pref, newValue) -> {
            boolean on = (Boolean) newValue;
            putInt(PetalConfig.KEY_LOCK_MEDIA_COVER, on ? 1 : 0);
            // cover art and cd player are mutually exclusive
            if (on) {
                putInt(PetalConfig.KEY_LOCK_CD_PLAYER, 0);
                cd.setChecked(false);
            }
            return true;
        });

        cd.setChecked(PetalConfig.isLockCdPlayerEnabled(requireContext()));
        cd.setOnPreferenceChangeListener((pref, newValue) -> {
            boolean on = (Boolean) newValue;
            putInt(PetalConfig.KEY_LOCK_CD_PLAYER, on ? 1 : 0);
            if (on) {
                putInt(PetalConfig.KEY_LOCK_MEDIA_COVER, 0);
                cover.setChecked(false);
            }
            return true;
        });

        SwitchPreferenceCompat seamless = findPreference("lockscreen_seamless_unlock");
        seamless.setChecked(PetalConfig.isSeamlessUnlockEnabled(requireContext()));
        seamless.setOnPreferenceChangeListener((pref, newValue) -> {
            putInt(PetalConfig.KEY_SEAMLESS_UNLOCK, (Boolean) newValue ? 1 : 0);
            return true;
        });

        SwitchPreferenceCompat ripple = findPreference("lockscreen_fingerprint_ripple");
        ripple.setChecked(android.provider.Settings.System.getInt(
                requireContext().getContentResolver(), PetalConfig.KEY_UDFPS_RIPPLE, 1) == 1);
        ripple.setOnPreferenceChangeListener((pref, newValue) -> {
            putInt(PetalConfig.KEY_UDFPS_RIPPLE, (Boolean) newValue ? 1 : 0);
            return true;
        });

        mClockStyle = findPreference("lockscreen_clock_style");
        updateClockStyleSummary();
        // custom dialog so you can preview the fonts
        mClockStyle.setOnPreferenceClickListener(pref -> {
            ClockStylePickerDialog.show(requireContext());
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        updateClockStyleSummary();
    }

    private void updateClockStyleSummary() {
        if (mClockStyle == null) return;
        String[] entries = getResources().getStringArray(R.array.clock_style_entries);
        int style = PetalConfig.getLockClockStyle(requireContext());
        mClockStyle.setSummary(style >= 0 && style < entries.length
                ? entries[style] : entries[0]);
    }
}
