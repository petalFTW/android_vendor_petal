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
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.Set;

public final class Prefs {

    public static final String FILE = "petal_tuner";
    public static final String BACKUP_FILE = "petal_tuner_backup";

    public static final String ACTIVE_PROFILE = "active_profile";
    public static final String EXPERT_UNLOCKED = "expert_unlocked";
    public static final String OVERLAY_ENABLED = "overlay_enabled";
    public static final String OVERLAY_X = "overlay_x";
    public static final String OVERLAY_Y = "overlay_y";
    public static final String AUTO_BOOST_ON_GAME = "auto_boost_on_game";
    public static final String GAME_PACKAGES = "game_packages";
    public static final String USE_GAME_MANAGER = "use_game_manager";
    public static final String RESTORE_ON_BOOT = "restore_on_boot";

    public static final String GM_ACTIVE = "gm_active";
    public static final String GM_PREV_PROFILE = "gm_prev_profile";
    public static final String GM_BYPASS_BY_GM = "gm_bypass_by_gm";
    public static final String GM_FOCUS_BY_GM = "gm_focus_by_gm";
    public static final String GM_FOCUS_PREV = "gm_focus_prev";
    public static final String GM_HZ_BY_GM = "gm_hz_by_gm";
    public static final String GM_ORIG_PEAK = "gm_orig_peak";
    public static final String GM_ORIG_MIN = "gm_orig_min";
    public static final String GM_RES_BY_GM = "gm_res_by_gm";

    public static final String BYPASS_ENABLED = "bypass_enabled";
    public static final String BYPASS_AUTO = "bypass_auto";
    public static final String CHARGE_LIMIT_ENABLED = "charge_limit_enabled";
    public static final String CHARGE_LIMIT = "charge_limit";
    public static final String CHARGE_RESUME = "charge_resume";
    public static final String COOLDOWN = "cooldown";
    public static final String SLOW_CHARGE = "slow_charge";
    public static final String ENDURANCE = "endurance";

    public static final String FOCUS_ENABLED = "focus_enabled";
    public static final String FOCUS_DND = "focus_dnd";
    public static final String FOCUS_HEADS_UP = "focus_heads_up";
    public static final String FOCUS_MUTE = "focus_mute";
    public static final String FOCUS_BRIGHTNESS = "focus_brightness";
    public static final String FOCUS_ROTATION = "focus_rotation";

    public static final String REFRESH_RATE = "refresh_rate";
    public static final String MIN_REFRESH_RATE = "min_refresh_rate";
    public static final String ANIMATION_SCALE = "animation_scale";
    public static final String FORCE_GPU = "force_gpu";
    public static final String DISABLE_HW_OVERLAYS = "disable_hw_overlays";
    public static final String IMMERSIVE = "immersive";

    public static final String NETWORK_CONGESTION = "network_congestion";
    public static final String NETWORK_BUFFERS = "network_buffers";
    public static final String NETWORK_SUPPRESS_SCANS = "network_suppress_scans";

    public static final String MEMORY_SWAPPINESS = "memory_swappiness";
    public static final String MEMORY_EXTRA_FREE_KB = "memory_extra_free_kb";
    public static final String MEMORY_APPLY = "memory_apply";

    public static final String THERMAL_DISABLE = "thermal_disable";
    public static final String THERMAL_PROFILE = "thermal_profile";
    public static final String TOUCH_BOOST = "touch_boost";

    public static final String EXPERT_FSYNC = "expert_fsync";

    public static final String CUSTOM_CPU_GOVERNOR = "custom_cpu_governor";
    public static final String CUSTOM_CPU_MIN = "custom_cpu_min";
    public static final String CUSTOM_CPU_MAX = "custom_cpu_max";
    public static final String CUSTOM_GPU_GOVERNOR = "custom_gpu_governor";
    public static final String CUSTOM_GPU_MIN = "custom_gpu_min";
    public static final String CUSTOM_GPU_MAX = "custom_gpu_max";
    public static final String CUSTOM_IO_SCHEDULER = "custom_io_scheduler";
    public static final String CUSTOM_IO_READAHEAD = "custom_io_readahead";

    private final SharedPreferences mPrefs;

    public Prefs(Context context) {
        mPrefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public SharedPreferences raw() {
        return mPrefs;
    }

    public String getString(String key, String def) {
        return mPrefs.getString(key, def);
    }

    public void putString(String key, String value) {
        mPrefs.edit().putString(key, value).apply();
    }

    public int getInt(String key, int def) {
        return mPrefs.getInt(key, def);
    }

    public void putInt(String key, int value) {
        mPrefs.edit().putInt(key, value).apply();
    }

    public boolean getBool(String key, boolean def) {
        return mPrefs.getBoolean(key, def);
    }

    public void remove(String key) {
        mPrefs.edit().remove(key).apply();
    }

    public void putBool(String key, boolean value) {
        mPrefs.edit().putBoolean(key, value).apply();
    }

    public long getLong(String key, long def) {
        return mPrefs.getLong(key, def);
    }

    public void putLong(String key, long value) {
        mPrefs.edit().putLong(key, value).apply();
    }

    public Set<String> getStringSet(String key) {
        Set<String> value = mPrefs.getStringSet(key, null);
        return value == null ? Collections.emptySet() : value;
    }

    public void putStringSet(String key, Set<String> value) {
        mPrefs.edit().putStringSet(key, value).apply();
    }
}
