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

import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import org.petalos.petaltuner.OverlayService;
import org.petalos.petaltuner.R;
import org.petalos.petaltuner.core.GmConfig;
import org.petalos.petaltuner.core.Prefs;
import org.petalos.petaltuner.core.ProfileManager;

public final class DashboardFragment extends BasePreferenceFragment {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        createScreen();

        category(R.string.dashboard_section_quick);

        Preference status = action(R.string.bypass_title, R.string.battery_status_title);
        String level = engine.charging().getLevel() >= 0
                ? engine.charging().getLevel() + "%" : getString(R.string.value_unknown);
        status.setSummary(getString(R.string.profile_active_title) + ": "
                + ProfileManager.active(ctx).label + " · " + level);
        status.setFragment(ChargingFragment.class.getName());

        SwitchPreferenceCompat overlay = switchPref(R.string.tile_game_mode,
                R.string.tuner_summary);
        GmConfig gm = new GmConfig(ctx);
        overlay.setChecked(gm.overlayEnabled());
        overlay.setOnPreferenceChangeListener((preference, value) -> {
            boolean enabled = (Boolean) value;
            gm.setOverlayEnabled(enabled);
            if (enabled || gm.hasWork()) {
                OverlayService.start(ctx);
            } else {
                OverlayService.stop(ctx);
            }
            return true;
        });

        SwitchPreferenceCompat focus = switchPref(R.string.focus_theme_title,
                R.string.focus_theme_summary);
        focus.setChecked(engine.prefs().getBool(Prefs.FOCUS_ENABLED, false));
        focus.setOnPreferenceChangeListener((preference, value) -> {
            engine.setFocusEnabled((Boolean) value);
            return true;
        });

        Preference boost = action(R.string.profile_boost_title, R.string.profile_boost_summary);
        boost.setOnPreferenceClickListener(preference -> {
            engine.applyActiveProfile();
            toast(getString(R.string.toast_applied, ProfileManager.active(ctx).label));
            return true;
        });

        category(R.string.dashboard_section_power);
        link(R.string.category_charging, R.string.charging_summary,
                ChargingFragment.class.getName());

        category(R.string.dashboard_section_perf);
        link(R.string.category_performance, R.string.performance_summary,
                PerformanceFragment.class.getName());
        link(R.string.category_memory, R.string.memory_summary,
                MemoryFragment.class.getName());
        link(R.string.category_network, R.string.network_summary,
                NetworkFragment.class.getName());

        category(R.string.dashboard_section_display);
        link(R.string.category_display, R.string.display_summary,
                DisplayFragment.class.getName());
        link(R.string.category_distraction, R.string.distraction_summary,
                DistractionFragment.class.getName());

        category(R.string.dashboard_section_system);
        link(R.string.category_profiles, R.string.profiles_summary,
                ProfilesFragment.class.getName());
        link(R.string.category_expert, R.string.expert_summary,
                ExpertFragment.class.getName());
        link(R.string.category_about, R.string.about_summary,
                AboutFragment.class.getName());
    }
}
