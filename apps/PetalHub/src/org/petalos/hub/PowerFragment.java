/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.hub;

import android.app.TimePickerDialog;
import android.os.Bundle;
import org.petalos.config.PetalConfig;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.PowerExemptionManager;
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
    private static final String KEY_AUTO = "petal_idle_power_auto";
    private static final String KEY_STATUS = "petal_idle_power_status";
    private static final String KEY_APPLIED = "petal_idle_power_applied";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.power, rootKey);

        SwitchPreferenceCompat enabled = findPreference(KEY_ENABLED);
        enabled.setChecked(getInt(KEY_ENABLED, 1) != 0);
        enabled.setOnPreferenceChangeListener((preference, value) -> {
            putInt(KEY_ENABLED, (Boolean) value ? 1 : 0);
            idlePolicyChanged(KEY_ENABLED);
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
        // profile summary actually says what it does now instead of that wall of text
        ListPreference profile = findPreference(KEY_PROFILE);
        profile.setSummaryProvider(p -> getString("2".equals(((ListPreference) p).getValue())
                ? R.string.power_profile_deep_summary : R.string.power_profile_optimized_summary));
        bindSwitch(KEY_AUTO, true);

        MultiSelectListPreference packages = findPreference(KEY_PACKAGES);
        populatePackages(packages);
        packages.setOnPreferenceChangeListener((preference, value) -> {
            @SuppressWarnings("unchecked") Set<String> selected = (Set<String>) value;
            putString(KEY_PACKAGES, String.join(",", selected));
            updatePackageSummary(packages, selected);
            idlePolicyChanged(KEY_PACKAGES);
            return true;
        });

        bindSwitch(KEY_WIFI_SCAN, false);
        bindSwitch(KEY_BLE_SCAN, false);

        Preference exempt = findPreference("power_audit_exempt");
        exempt.setOnPreferenceClickListener(p -> {
            try {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (RuntimeException e) {
                android.util.Log.w("PetalHub", "battery opt screen missing??", e);
            }
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // the service writes status on every screen cycle, re-read it whenever we come back
        updateStatus();
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
            idlePolicyChanged(key);
            return true;
        });
    }

    private void bindSwitch(String key, boolean defaultOn) {
        SwitchPreferenceCompat preference = findPreference(key);
        preference.setChecked(getInt(key, defaultOn ? 1 : 0) != 0);
        preference.setOnPreferenceChangeListener((unused, value) -> {
            putInt(key, (Boolean) value ? 1 : 0);
            idlePolicyChanged(key);
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

    // status string from PetalPower: state;wallclock;count;profile
    private void updateStatus() {
        Preference status = findPreference("power_status");
        if (status == null) return;
        if (getInt(KEY_ENABLED, 1) == 0) {
            status.setSummary(R.string.power_status_off);
            return;
        }
        int grace = getInt(KEY_GRACE, 10);
        String graceText = grace <= 0 ? getString(R.string.power_grace_now)
                : getString(R.string.power_grace_minutes, grace);
        String raw = getStringSetting(KEY_STATUS);
        String[] parts = raw == null ? new String[0] : raw.split(";");
        String state = parts.length > 0 ? parts[0] : "";
        long since = parts.length > 1 ? parseLong(parts[1]) : 0L;
        int count = parts.length > 2 ? (int) parseLong(parts[2]) : 0;
        switch (state) {
            case "active":
                status.setSummary(getString(R.string.power_status_active,
                        android.text.format.DateFormat.getTimeFormat(requireContext())
                                .format(new java.util.Date(since)), count));
                break;
            case "waiting":
                status.setSummary(getString(R.string.power_status_waiting, graceText));
                break;
            case "empty":
                status.setSummary(R.string.power_status_empty);
                break;
            case "ready":
            case "off":
                status.setSummary(getString(R.string.power_status_ready, graceText));
                break;
            default:
                status.setSummary(R.string.power_status_unknown);
        }
    }

    // the old audit only counted picked apps, which is why it looked dead. this shows the real culprits
    private void updateAudit() {
        PackageManager pm = requireContext().getPackageManager();
        Preference exempt = findPreference("power_audit_exempt");
        PowerExemptionManager exemptions = requireContext().getSystemService(PowerExemptionManager.class);
        List<String> names = new ArrayList<>();
        Set<Integer> allowed = new java.util.HashSet<>();
        try {
            if (exemptions != null) {
                for (int id : exemptions.getAllowListedAppIds(false)) allowed.add(id);
            }
        } catch (RuntimeException e) {
            android.util.Log.w("PetalHub", "allowlist read failed", e);
        }
        // one binder call, then match locally. the per-app version lagged the page
        for (ApplicationInfo app : pm.getInstalledApplications(0)) {
            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (allowed.contains(android.os.UserHandle.getAppId(app.uid))) {
                names.add(app.loadLabel(pm).toString());
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        exempt.setSummary(names.isEmpty() ? getString(R.string.power_audit_none)
                : getString(R.string.power_audit_list, String.join(", ", names)));

        Preference last = findPreference("power_audit_last");
        last.setSummary(labelsOrNull(pm, getStringSetting(KEY_APPLIED)) == null
                ? getString(R.string.power_last_none)
                : getString(R.string.power_last_list, labelsOrNull(pm, getStringSetting(KEY_APPLIED))));

        Preference errors = findPreference("power_audit_errors");
        String failed = labelsOrNull(pm, android.provider.Settings.Secure.getString(
                requireContext().getContentResolver(), "petal_spoof_power_errors"));
        errors.setVisible(failed != null);
        if (failed != null) errors.setSummary(getString(R.string.power_errors_summary, failed));
    }

    private static String labelsOrNull(PackageManager pm, String csv) {
        if (csv == null || csv.trim().isEmpty()) return null;
        List<String> out = new ArrayList<>();
        for (String pkg : csv.split(",")) {
            String p = pkg.trim();
            if (p.isEmpty()) continue;
            try {
                out.add(pm.getApplicationInfo(p, 0).loadLabel(pm).toString());
            } catch (PackageManager.NameNotFoundException e) {
                out.add(p);
            }
        }
        if (out.isEmpty()) return null;
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return String.join(", ", out);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void idlePolicyChanged(String key) {
        if (!key.startsWith("petal_idle_")) return;
        requireContext().sendBroadcast(new android.content.Intent(
                "org.petalos.power.action.UPDATE_IDLE_POLICY").setPackage("org.petalos.power"));
        updateStatus();
    }
}
