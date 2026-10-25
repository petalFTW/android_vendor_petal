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
import androidx.preference.Preference;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import org.petalos.petaltuner.R;
import org.petalos.petaltuner.core.Prefs;

public final class ChargingFragment extends BasePreferenceFragment {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        createScreen();
        boolean supported = engine.charging().isSupported();

        category(R.string.charging_controls_title);

        SwitchPreferenceCompat bypass = switchPref(R.string.bypass_title, R.string.bypass_summary);
        bypass.setEnabled(supported);
        bypass.setChecked(engine.charging().isBypassActive()
                || engine.prefs().getBool(Prefs.BYPASS_ENABLED, false));
        if (!supported) {
            bypass.setSummary(R.string.bypass_unavailable);
        } else if (!engine.charging().isPlugged()) {
            bypass.setSummary(R.string.bypass_unplugged);
        }
        bypass.setOnPreferenceChangeListener((preference, value) -> {
            boolean enabled = (Boolean) value;
            boolean ok = engine.charging().setBypass(enabled);
            engine.prefs().putBool(Prefs.BYPASS_ENABLED, enabled);
            if (!ok) {
                toast(R.string.bypass_unavailable);
                return false;
            }
            return true;
        });

        SwitchPreferenceCompat auto = switchPref(R.string.bypass_auto_title,
                R.string.bypass_auto_summary);
        auto.setChecked(engine.prefs().getBool(Prefs.BYPASS_AUTO, false));
        auto.setOnPreferenceChangeListener((preference, value) -> {
            engine.prefs().putBool(Prefs.BYPASS_AUTO, (Boolean) value);
            return true;
        });

        category(R.string.charging_advanced_category);

        SwitchPreferenceCompat limit = switchPref(R.string.charge_limit_title,
                R.string.charge_limit_summary);
        limit.setChecked(engine.prefs().getBool(Prefs.CHARGE_LIMIT_ENABLED, false));
        limit.setOnPreferenceChangeListener((preference, value) -> {
            engine.prefs().putBool(Prefs.CHARGE_LIMIT_ENABLED, (Boolean) value);
            engine.enforceCharging();
            return true;
        });

        SeekBarPreference limitValue = seekPref(R.string.charge_limit_value_title, 100);
        limitValue.setValue(engine.prefs().getInt(Prefs.CHARGE_LIMIT, 80));
        limitValue.setOnPreferenceChangeListener((preference, value) -> {
            int limitPct = (Integer) value;
            engine.prefs().putInt(Prefs.CHARGE_LIMIT, limitPct);
            engine.enforceCharging();
            return true;
        });

        SeekBarPreference resumeValue = seekPref(R.string.charge_resume_value_title, 100);
        resumeValue.setValue(engine.prefs().getInt(Prefs.CHARGE_RESUME, 75));
        resumeValue.setOnPreferenceChangeListener((preference, value) -> {
            engine.prefs().putInt(Prefs.CHARGE_RESUME, (Integer) value);
            return true;
        });

        ListPreference cooldown = listPref(R.string.cooldown_title);
        cooldown.setEntries(R.array.cooldown_entries);
        cooldown.setEntryValues(R.array.cooldown_values);
        cooldown.setValue(Integer.toString(engine.prefs().getInt(Prefs.COOLDOWN, 0)));
        cooldown.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        cooldown.setOnPreferenceChangeListener((preference, value) -> {
            int level = Integer.parseInt((String) value);
            engine.prefs().putInt(Prefs.COOLDOWN, level);
            engine.charging().setCoolDown(level);
            return true;
        });

        SwitchPreferenceCompat slow = switchPref(R.string.slow_charge_title,
                R.string.slow_charge_summary);
        slow.setChecked(engine.prefs().getBool(Prefs.SLOW_CHARGE, false));
        slow.setOnPreferenceChangeListener((preference, value) -> {
            engine.prefs().putBool(Prefs.SLOW_CHARGE, (Boolean) value);
            engine.charging().setSlowCharge((Boolean) value);
            return true;
        });

        SwitchPreferenceCompat endurance = switchPref(R.string.endurance_title,
                R.string.endurance_summary);
        endurance.setChecked(engine.prefs().getBool(Prefs.ENDURANCE, false));
        endurance.setOnPreferenceChangeListener((preference, value) -> {
            engine.prefs().putBool(Prefs.ENDURANCE, (Boolean) value);
            boolean ok = engine.charging().setEndurance((Boolean) value);
            if (!ok) {
                toast(R.string.bypass_unavailable);
                return false;
            }
            return true;
        });

        category(R.string.charging_state_category);
        Preference level = action(R.string.battery_level_title, 0);
        int batteryLevel = engine.charging().getLevel();
        level.setSummary(batteryLevel >= 0 ? batteryLevel + "%"
                : getString(R.string.value_unknown));

        int temperature = engine.charging().getTemperature();
        Preference temp = action(R.string.battery_temp_title, 0);
        temp.setSummary(temperature == Integer.MIN_VALUE ? getString(R.string.value_unknown)
                : String.format(java.util.Locale.US, "%.1f °C", temperature / 10f));

        int current = engine.charging().getCurrentNow();
        Preference currentPref = action(R.string.battery_current_title, 0);
        currentPref.setSummary(current == Integer.MIN_VALUE ? getString(R.string.value_unknown)
                : current + " mA");

        Preference health = action(R.string.battery_health_title, 0);
        health.setSummary(engine.charging().getHealth() == null ? getString(R.string.value_unknown)
                : engine.charging().getHealth());

        Preference technology = action(R.string.battery_tech_title, 0);
        technology.setSummary(engine.charging().getTechnology() == null
                ? getString(R.string.value_unknown) : engine.charging().getTechnology());
    }
}
