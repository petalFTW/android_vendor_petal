/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.hub;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-app network access. The user picks apps that may NOT use the network;
 * PetalPower enforces it with the netd firewall (chain OEM_DENY_3).
 */
public final class NetworkFragment extends PetalPreferenceFragment {
    private static final String KEY_PACKAGES = "petal_network_denied_packages";
    private static final String APPLY_ACTION = "org.petalos.power.action.APPLY_NETWORK_POLICY";
    private static final String APPLY_COMPONENT = "org.petalos.power";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.network, rootKey);

        MultiSelectListPreference packages = findPreference(KEY_PACKAGES);
        populatePackages(packages);
        packages.setOnPreferenceChangeListener((preference, value) -> {
            @SuppressWarnings("unchecked") Set<String> selected = (Set<String>) value;
            putString(KEY_PACKAGES, String.join(",", selected));
            updateSummary(packages, selected);
            apply();
            return true;
        });
    }

    /** Ask PetalPower to push the updated deny list down to netd. */
    private void apply() {
        Intent intent = new Intent(APPLY_ACTION);
        intent.setPackage(APPLY_COMPONENT);
        requireContext().sendBroadcast(intent);
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
        updateSummary(preference, selected);
    }

    private void updateSummary(Preference preference, Set<String> selected) {
        preference.setSummary(selected.isEmpty() ? getString(R.string.network_packages_none)
                : getResources().getQuantityString(R.plurals.network_packages_count,
                        selected.size(), selected.size()));
    }
}
