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

import android.app.ActivityManager;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import org.petalos.petaltuner.R;
import org.petalos.petaltuner.core.Prefs;

public final class MemoryFragment extends BasePreferenceFragment {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        createScreen();

        category(R.string.category_memory);

        ActivityManager.MemoryInfo info = engine.memory().getMemoryInfo();
        Preference summary = action(R.string.battery_status_title, 0);
        if (info != null) {
            summary.setTitle(R.string.category_memory);
            summary.setSummary(String.format(java.util.Locale.US,
                    "Available %.1f GB · Low memory: %s",
                    info.availMem / 1073741824f, info.lowMemory ? "yes" : "no"));
        }

        SwitchPreferenceCompat apply = switchPref(R.string.memory_apply_title,
                R.string.memory_state_summary);
        apply.setChecked(engine.prefs().getBool(Prefs.MEMORY_APPLY, false));
        apply.setOnPreferenceChangeListener((preference, value) -> {
            engine.prefs().putBool(Prefs.MEMORY_APPLY, (Boolean) value);
            if ((Boolean) value) {
                engine.applyMemoryState();
            } else {
                engine.backup().restore("/proc/sys/vm/swappiness");
                engine.backup().restore("/proc/sys/vm/extra_free_kbytes");
            }
            return true;
        });

        SeekBarPreference swappiness = seekPref(R.string.memory_swap_title, 200);
        swappiness.setValue(engine.prefs().getInt(Prefs.MEMORY_SWAPPINESS, 100));
        swappiness.setOnPreferenceChangeListener((preference, value) -> {
            engine.prefs().putInt(Prefs.MEMORY_SWAPPINESS, (Integer) value);
            engine.memory().setSwappiness((Integer) value);
            return true;
        });

        SeekBarPreference freeKb = seekPref(R.string.memory_free_kb_title, 65536);
        freeKb.setValue(engine.prefs().getInt(Prefs.MEMORY_EXTRA_FREE_KB, 32768));
        freeKb.setOnPreferenceChangeListener((preference, value) -> {
            engine.prefs().putInt(Prefs.MEMORY_EXTRA_FREE_KB, (Integer) value);
            engine.memory().setExtraFreeKbytes((Integer) value);
            return true;
        });

        Preference kill = action(R.string.memory_kill_bg_title, R.string.memory_kill_bg_summary);
        kill.setOnPreferenceClickListener(preference -> {
            engine.memory().killBackgroundProcesses();
            toast(R.string.memory_kill_bg_title);
            return true;
        });
    }
}
