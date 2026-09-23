/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.hub;

import android.app.TimePickerDialog;
import android.os.Bundle;
import org.petalos.config.PetalConfig;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// idle power controls, of course
public final class PowerFragment extends PetalPreferenceFragment {
    private static final String KEY_ENABLED = "petal_idle_power_enabled";
    private static final String KEY_PROFILE = "petal_idle_power_profile";
    private static final String KEY_PACKAGES = "petal_idle_power_packages";
    private static final String KEY_GRACE = "petal_idle_power_grace_minutes";
    private static final String KEY_WIFI_SCAN = "petal_idle_disable_wifi_scan";
    private static final String KEY_BLE_SCAN = "petal_idle_disable_ble_scan";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.power, rootKey);

        SwitchPreferenceCompat enabled = findPreference(KEY_ENABLED);
        enabled.setChecked(getInt(KEY_ENABLED, 1) != 0);
        enabled.setOnPreferenceChangeListener((preference, value) -> {
            putInt(KEY_ENABLED, (Boolean) value ? 1 : 0);
            return true;
        });

        bindSwitch(PetalConfig.KEY_DITHERING_ENABLED, true);
        bindSwitch(PetalConfig.KEY_DITHERING_BATTERY_SAVER, true);
        bindSwitch(PetalConfig.KEY_DITHERING_SCHEDULE, false);
        bindTime(PetalConfig.KEY_DITHERING_START_MIN, PetalConfig.DEFAULT_DITHERING_START_MIN);
        bindTime(PetalConfig.KEY_DITHERING_END_MIN, PetalConfig.DEFAULT_DITHERING_END_MIN);
        bindList(PetalConfig.KEY_DITHERING_PERCENT,
                Integer.toString(PetalConfig.DEFAULT_DITHERING_PERCENT));

        bindList(KEY_PROFILE, "1");
        bindList(KEY_GRACE, "10");

        MultiSelectListPreference packages = findPreference(KEY_PACKAGES);
        populatePackages(packages);
        packages.setOnPreferenceChangeListener((preference, value) -> {
            @SuppressWarnings("unchecked") Set<String> selected = (Set<String>) value;
            putString(KEY_PACKAGES, String.join(",", selected));
            updatePackageSummary(packages, selected);
            return true;
        });

        bindSwitch(KEY_WIFI_SCAN, false);
        bindSwitch(KEY_BLE_SCAN, false);
        updateAudit();
    }

    private void bindList(String key, String defaultValue) {
        ListPreference preference = findPreference(key);
        String value = getStringSetting(key);
        if (value == null) value = Integer.toString(getInt(key, Integer.parseInt(defaultValue)));
        preference.setValue(value);
        preference.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        preference.setOnPreferenceChangeListener((unused, newValue) -> {
            putInt(key, Integer.parseInt((String) newValue));
            return true;
        });
    }

    private void bindSwitch(String key, boolean defaultOn) {
        SwitchPreferenceCompat preference = findPreference(key);
        preference.setChecked(getInt(key, defaultOn ? 1 : 0) != 0);
        preference.setOnPreferenceChangeListener((unused, value) -> {
            putInt(key, (Boolean) value ? 1 : 0);
            return true;
        });
    }

    private void bindTime(String key, int defaultMinutes) {
        Preference preference = findPreference(key);
        int minutes = getInt(key, defaultMinutes);
        preference.setSummary(formatTime(minutes));
        preference.setOnPreferenceClickListener(pref -> {
            int current = clamp(getInt(key, defaultMinutes), 0, 24 * 60);
            int hour = current / 60 % 24;
            int minute = current % 60;
            TimePickerDialog dialog = new TimePickerDialog(requireContext(),
                    (view, pickedHour, pickedMinute) -> {
                        int picked = clamp(pickedHour * 60 + pickedMinute, 0, 24 * 60);
                        putInt(key, picked);
                        pref.setSummary(formatTime(picked));
                    }, hour, minute, true);
            dialog.show();
            return true;
        });
    }

    private static String formatTime(int minutes) {
        return String.format(java.util.Locale.US, "%02d:%02d", minutes / 60 % 24, minutes % 60);
    }

    private void populatePackages(MultiSelectListPreference preference) {
        PackageManager pm = requireContext().getPackageManager();
        List<ApplicationInfo> apps = new ArrayList<>(pm.getInstalledApplications(0));
        apps.removeIf(app -> (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                || app.packageName.equals(requireContext().getPackageName()));
        apps.sort(Comparator.comparing(app -> app.loadLabel(pm).toString(),
                String.CASE_INSENSITIVE_ORDER));
        CharSequence[] labels = new CharSequence[apps.size()];
        CharSequence[] values = new CharSequence[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            ApplicationInfo app = apps.get(i);
            labels[i] = app.loadLabel(pm) + "\n" + app.packageName;
            values[i] = app.packageName;
        }
        preference.setEntries(labels);
        preference.setEntryValues(values);
        String raw = getStringSetting(KEY_PACKAGES);
        Set<String> selected = new LinkedHashSet<>();
        if (raw != null && !raw.trim().isEmpty()) selected.addAll(Arrays.asList(raw.split(",")));
        preference.setValues(selected);
        updatePackageSummary(preference, selected);
    }

    private void updatePackageSummary(Preference preference, Set<String> selected) {
        preference.setSummary(selected.isEmpty() ? getString(R.string.power_packages_none)
                : getResources().getQuantityString(R.plurals.power_packages_count,
                        selected.size(), selected.size()));
    }

    private void updateAudit() {
        Preference audit = findPreference("power_policy_audit");
        String raw = getStringSetting(KEY_PACKAGES);
        if (raw == null || raw.trim().isEmpty()) {
            audit.setSummary(R.string.power_audit_empty);
            return;
        }
        PowerManager power = requireContext().getSystemService(PowerManager.class);
        int allowlisted = 0;
        int selected = 0;
        for (String pkg : raw.split(",")) {
            if (pkg.trim().isEmpty()) continue;
            selected++;
            if (power != null && power.isIgnoringBatteryOptimizations(pkg.trim())) allowlisted++;
        }
        audit.setSummary(getString(R.string.power_audit_summary, selected, allowlisted));
    }
}
