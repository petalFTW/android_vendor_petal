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

import androidx.preference.SwitchPreferenceCompat;

import org.petalos.petaltuner.R;
import org.petalos.petaltuner.core.Prefs;

public final class DistractionFragment extends BasePreferenceFragment {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        createScreen();

        category(R.string.category_distraction);

        SwitchPreferenceCompat master = switchPref(R.string.focus_theme_title,
                R.string.focus_theme_summary);
        master.setChecked(engine.prefs().getBool(Prefs.FOCUS_ENABLED, false));
        master.setOnPreferenceChangeListener((preference, value) -> {
            engine.setFocusEnabled((Boolean) value);
            return true;
        });

        bindFocus(Prefs.FOCUS_DND, R.string.focus_dnd_title, R.string.focus_dnd_summary, true);
        bindFocus(Prefs.FOCUS_HEADS_UP, R.string.focus_heads_up_title,
                R.string.focus_heads_up_summary, true);
        bindFocus(Prefs.FOCUS_MUTE, R.string.focus_mute_title, R.string.focus_mute_summary, false);
        bindFocus(Prefs.FOCUS_BRIGHTNESS, R.string.focus_brightness_title,
                R.string.focus_brightness_summary, false);
        bindFocus(Prefs.FOCUS_ROTATION, R.string.focus_autorotate_title,
                R.string.focus_autorotate_summary, false);
    }

    private void bindFocus(String key, int titleRes, int summaryRes, boolean def) {
        SwitchPreferenceCompat preference = switchPref(titleRes, summaryRes);
        preference.setChecked(engine.prefs().getBool(key, def));
        preference.setOnPreferenceChangeListener((unused, value) -> {
            engine.prefs().putBool(key, (Boolean) value);
            if (engine.prefs().getBool(Prefs.FOCUS_ENABLED, false)) {
                engine.setFocusEnabled(true);
            }
            return true;
        });
    }
}
