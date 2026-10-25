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

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import org.petalos.petaltuner.core.TunerEngine;

public abstract class BasePreferenceFragment extends PreferenceFragmentCompat {

    protected TunerEngine engine;
    protected Context ctx;

    @Nullable
    protected PreferenceScreen screen;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        engine = TunerEngine.get(requireContext());
        ctx = requireContext();
    }

    protected PreferenceScreen createScreen() {
        screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);
        return screen;
    }

    protected PreferenceCategory category(int titleRes) {
        PreferenceCategory category = new PreferenceCategory(requireContext());
        category.setTitle(titleRes);
        screen.addPreference(category);
        return category;
    }

    protected Preference link(int titleRes, int summaryRes, String fragmentClass) {
        Preference preference = new Preference(requireContext());
        preference.setTitle(titleRes);
        if (summaryRes != 0) {
            preference.setSummary(summaryRes);
        }
        preference.setFragment(fragmentClass);
        screen.addPreference(preference);
        return preference;
    }

    protected SwitchPreferenceCompat switchPref(int titleRes, int summaryRes) {
        SwitchPreferenceCompat preference = new SwitchPreferenceCompat(requireContext());
        preference.setTitle(titleRes);
        if (summaryRes != 0) {
            preference.setSummary(summaryRes);
        }
        screen.addPreference(preference);
        return preference;
    }

    protected ListPreference listPref(int titleRes) {
        ListPreference preference = new ListPreference(requireContext());
        preference.setTitle(titleRes);
        screen.addPreference(preference);
        return preference;
    }

    protected SeekBarPreference seekPref(int titleRes, int max) {
        SeekBarPreference preference = new SeekBarPreference(requireContext());
        preference.setTitle(titleRes);
        preference.setMax(max);
        preference.setShowSeekBarValue(true);
        screen.addPreference(preference);
        return preference;
    }

    protected Preference action(int titleRes, int summaryRes) {
        Preference preference = new Preference(requireContext());
        preference.setTitle(titleRes);
        if (summaryRes != 0) {
            preference.setSummary(summaryRes);
        }
        screen.addPreference(preference);
        return preference;
    }

    protected void toast(int res) {
        android.widget.Toast.makeText(requireContext(), res, android.widget.Toast.LENGTH_SHORT)
                .show();
    }

    protected void toast(String message) {
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT)
                .show();
    }
}
