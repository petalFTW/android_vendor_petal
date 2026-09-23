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

import org.petalos.config.PetalConfig;

/** where the bezel dialogs anchor */
public class DialogsFragment extends PetalPreferenceFragment {
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.dialogs, rootKey);
        configure("volume_placement", PetalConfig.KEY_VOLUME_EDGE,
                PetalConfig.KEY_VOLUME_ANCHOR_PCT, 0, 29, 0);
        configure("power_placement", PetalConfig.KEY_POWER_EDGE,
                PetalConfig.KEY_POWER_ANCHOR_PCT, 1, 33, 1);
        configure("extra_key_placement", PetalConfig.KEY_EXTRA_KEY_EDGE,
                PetalConfig.KEY_EXTRA_KEY_ANCHOR_PCT, 0, 25, 2);
    }

    private void configure(String preferenceKey, String edgeKey, String anchorKey,
            int edge, int anchor, int kind) {
        BezelPlacementPreference preference = findPreference(preferenceKey);
        preference.configure(edgeKey, anchorKey, edge, anchor, kind);
    }
}
