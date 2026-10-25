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

package org.petalos.petaltuner.core;

import android.content.Context;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ProfileManager {

    private static final Map<String, Profile> PROFILES = new LinkedHashMap<>();

    static {
        PROFILES.put("balanced", new Profile("balanced", "Balanced", "schedutil", false,
                "msm-adreno-tz", false, false, false, false, false));
        PROFILES.put("performance", new Profile("performance", "Performance", "performance", true,
                "performance", true, true, true, false, true));
        PROFILES.put("extreme", new Profile("extreme", "Extreme", "performance", true,
                "performance", true, true, true, true, true));
        PROFILES.put("battery", new Profile("battery", "Battery saver", "powersave", false,
                "powersave", false, false, false, false, false));
        PROFILES.put("custom", new Profile("custom", "Custom", null, false, null, false,
                false, false, false, false));
    }

    private ProfileManager() {}

    public static Profile get(String key) {
        Profile profile = key == null ? null : PROFILES.get(key);
        return profile != null ? profile : PROFILES.get("balanced");
    }

    public static Map<String, Profile> all() {
        return PROFILES;
    }

    public static String[] keys() {
        return PROFILES.keySet().toArray(new String[0]);
    }

    public static String[] labels() {
        String[] labels = new String[PROFILES.size()];
        int index = 0;
        for (Profile profile : PROFILES.values()) {
            labels[index++] = profile.label;
        }
        return labels;
    }

    public static String activeKey(Context context) {
        return new Prefs(context).getString(Prefs.ACTIVE_PROFILE, "balanced");
    }

    public static Profile active(Context context) {
        return get(activeKey(context));
    }
}
