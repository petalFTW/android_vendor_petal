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

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import org.petalos.config.PetalConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** manual game list and session rules for petalOS game mode */
public final class GameModeFragment extends PetalPreferenceFragment {

    private static final String GM_PACKAGE = GameModeReceiver.GM_PACKAGE;
    private static final String GM_ACTIVITY = GM_PACKAGE + ".TunerActivity";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.game_mode, rootKey);

        MultiSelectListPreference games = findPreference(PetalConfig.KEY_GM_GAMES);
        populateGames(games);
        games.setOnPreferenceChangeListener((preference, value) -> {
            @SuppressWarnings("unchecked") Set<String> selected = (Set<String>) value;
            putString(PetalConfig.KEY_GM_GAMES, String.join(",", selected));
            updateGamesSummary(games, selected);
            GameModeReceiver.grant(requireContext());
            startEngine();
            return true;
        });

        bindSwitch(PetalConfig.KEY_GM_OVERLAY, true);
        bindSwitch(PetalConfig.KEY_GM_BYPASS, true);
        bindSwitch(PetalConfig.KEY_GM_MAX_HZ, true);
        bindSwitch(PetalConfig.KEY_GM_MAX_RES, true);
        bindSwitch(PetalConfig.KEY_GM_FOCUS, true);
        bindSwitch(PetalConfig.KEY_GM_FREE_RAM, true);

        ListPreference profile = findPreference(PetalConfig.KEY_GM_PROFILE);
        String current = getStringSetting(PetalConfig.KEY_GM_PROFILE);
        profile.setValue(current == null || current.isEmpty() ? "performance" : current);
        profile.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        profile.setOnPreferenceChangeListener((preference, value) -> {
            putString(PetalConfig.KEY_GM_PROFILE, (String) value);
            return true;
        });

        Preference open = findPreference("petal_gm_open_tuner");
        open.setOnPreferenceClickListener(preference -> {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(GM_PACKAGE, GM_ACTIVITY);
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(requireContext(), R.string.hub_target_missing,
                        Toast.LENGTH_SHORT).show();
            }
            return true;
        });
    }

    private void bindSwitch(String key, boolean defaultOn) {
        SwitchPreferenceCompat preference = findPreference(key);
        preference.setChecked(getInt(key, defaultOn ? 1 : 0) != 0);
        preference.setOnPreferenceChangeListener((unused, value) -> {
            putInt(key, (Boolean) value ? 1 : 0);
            if ((Boolean) value) {
                startEngine();
            }
            return true;
        });
    }

    private void startEngine() {
        Intent intent = new Intent(GM_PACKAGE + ".action.GM_REFRESH");
        intent.setPackage(GM_PACKAGE);
        requireContext().sendBroadcast(intent);
    }

    private void populateGames(MultiSelectListPreference preference) {
        PackageManager pm = requireContext().getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> launchable = new LinkedHashSet<>();
        for (android.content.pm.ResolveInfo info : pm.queryIntentActivities(launcher, 0)) {
            launchable.add(info.activityInfo.packageName);
        }
        List<ApplicationInfo> apps = new ArrayList<>();
        for (ApplicationInfo app : pm.getInstalledApplications(0)) {
            if (!launchable.contains(app.packageName)
                    || app.packageName.equals(requireContext().getPackageName())
                    || app.packageName.equals(GM_PACKAGE)) {
                continue;
            }
            boolean system = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (system && app.category != ApplicationInfo.CATEGORY_GAME) {
                continue;
            }
            apps.add(app);
        }
        apps.sort(Comparator
                .comparing((ApplicationInfo app) -> app.category != ApplicationInfo.CATEGORY_GAME)
                .thenComparing(app -> app.loadLabel(pm).toString(),
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
        String raw = getStringSetting(PetalConfig.KEY_GM_GAMES);
        Set<String> selected = new LinkedHashSet<>();
        if (raw != null && !raw.trim().isEmpty()) {
            selected.addAll(Arrays.asList(raw.split(",")));
        }
        preference.setValues(selected);
        updateGamesSummary(preference, selected);
    }

    private void updateGamesSummary(Preference preference, Set<String> selected) {
        preference.setSummary(selected.isEmpty() ? getString(R.string.gm_games_none)
                : getResources().getQuantityString(R.plurals.gm_games_count,
                        selected.size(), selected.size()));
    }
}
