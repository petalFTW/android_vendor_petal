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

import androidx.preference.SwitchPreferenceCompat;

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
    }

    private int getSecureInt(String key, int defaultValue) {
        return Settings.Secure.getInt(getContentResolver(), key, defaultValue);
    }

    private void putSecureInt(String key, int value) {
        Settings.Secure.putInt(getContentResolver(), key, value);
    }
}
