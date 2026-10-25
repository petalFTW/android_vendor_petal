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

public final class NetworkFragment extends BasePreferenceFragment {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        createScreen();

        category(R.string.category_network);

        String[] algorithms = engine.network().getAvailableCongestions();
        if (algorithms.length > 0) {
            ListPreference congestion = listPref(R.string.network_tcp_title);
            congestion.setEntries(algorithms);
            congestion.setEntryValues(algorithms);
            congestion.setValue(engine.prefs().getString(Prefs.NETWORK_CONGESTION,
                    engine.network().getCongestion()));
            congestion.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
            congestion.setOnPreferenceChangeListener((preference, value) -> {
                engine.prefs().putString(Prefs.NETWORK_CONGESTION, (String) value);
                engine.network().setCongestion((String) value);
                return true;
            });
        }

        SwitchPreferenceCompat buffers = switchPref(R.string.network_buffer_title,
                R.string.network_summary);
        buffers.setChecked(engine.prefs().getBool(Prefs.NETWORK_BUFFERS, false));
        buffers.setOnPreferenceChangeListener((preference, value) -> {
            engine.prefs().putBool(Prefs.NETWORK_BUFFERS, (Boolean) value);
            if ((Boolean) value) {
                engine.network().applyLowLatency();
            }
            return true;
        });

        SwitchPreferenceCompat scans = switchPref(R.string.network_disable_scans_title,
                R.string.network_disable_scans_summary);
        scans.setChecked(engine.prefs().getBool(Prefs.NETWORK_SUPPRESS_SCANS, false));
        scans.setOnPreferenceChangeListener((preference, value) -> {
            engine.prefs().putBool(Prefs.NETWORK_SUPPRESS_SCANS, (Boolean) value);
            engine.applyNetworkState();
            return true;
        });
    }
}
