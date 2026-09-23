/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.petalos.hub;

import android.os.Bundle;

import androidx.preference.ListPreference;
import androidx.preference.SwitchPreferenceCompat;

// toggles the system crossfade engine
public final class BridgeFragment extends PetalPreferenceFragment {
    public static final String KEY_ENABLED = "petal_bridge_enabled";
    public static final String KEY_STYLE = "petal_bridge_style";
    public static final String KEY_ASK = "petal_bridge_ask";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.bridge, rootKey);

        SwitchPreferenceCompat enabled = findPreference(KEY_ENABLED);
        enabled.setChecked(getInt(KEY_ENABLED, 0) != 0); // off by default
        enabled.setOnPreferenceChangeListener((preference, value) -> {
            putInt(KEY_ENABLED, (Boolean) value ? 1 : 0);
            return true;
        });

        SwitchPreferenceCompat ask = findPreference(KEY_ASK);
        ask.setChecked(getInt(KEY_ASK, 0) != 0); // opt-in
        ask.setOnPreferenceChangeListener((preference, value) -> {
            putInt(KEY_ASK, (Boolean) value ? 1 : 0);
            return true;
        });

        ListPreference style = findPreference(KEY_STYLE);
        String curStyle = Integer.toString(getInt(KEY_STYLE, 3));
        CharSequence[] styleVals = style.getEntryValues();
        style.setValue(styleVals[0].toString());
        for (CharSequence v : styleVals) {
            if (v.toString().equals(curStyle)) style.setValue(v.toString());
        }
        updateStyleSummary(style);
        style.setOnPreferenceChangeListener((preference, value) -> {
            putInt(KEY_STYLE, Integer.parseInt((String) value));
            style.setValue((String) value);
            updateStyleSummary(style);
            return true;
        });
        SwitchPreferenceCompat album = findPreference("petal_bridge_preserve_album");
        album.setChecked(getInt("petal_bridge_preserve_album", 1) != 0);
        album.setOnPreferenceChangeListener((preference, value) -> {
            putInt("petal_bridge_preserve_album", (Boolean) value ? 1 : 0);
            return true;
        });
    }

    private void updateStyleSummary(ListPreference style) {
        style.setSummary(getString(R.string.bridge_style_summary, style.getEntry() != null
                ? style.getEntry() : "?"));
    }
}
