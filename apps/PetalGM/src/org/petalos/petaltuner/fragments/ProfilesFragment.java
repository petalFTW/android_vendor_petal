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

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.preference.MultiSelectListPreference;
import androidx.preference.SwitchPreferenceCompat;

import org.petalos.petaltuner.R;
import org.petalos.petaltuner.core.GmConfig;
import org.petalos.petaltuner.core.Prefs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class ProfilesFragment extends BasePreferenceFragment {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        createScreen();

        category(R.string.category_profiles);

        SwitchPreferenceCompat auto = switchPref(R.string.profile_auto_title,
                R.string.profile_auto_summary);
        auto.setChecked(engine.prefs().getBool(Prefs.AUTO_BOOST_ON_GAME, true));
        auto.setOnPreferenceChangeListener((preference, value) -> {
            engine.prefs().putBool(Prefs.AUTO_BOOST_ON_GAME, (Boolean) value);
            return true;
        });

        SwitchPreferenceCompat manager = switchPref(R.string.profile_game_manager_title,
                R.string.profile_game_manager_summary);
        manager.setChecked(engine.prefs().getBool(Prefs.USE_GAME_MANAGER, true));
        manager.setOnPreferenceChangeListener((preference, value) -> {
            engine.prefs().putBool(Prefs.USE_GAME_MANAGER, (Boolean) value);
            if (!(Boolean) value) {
                engine.run(() -> engine.gameMode().resetGames(engine.gm().games()));
            }
            return true;
        });

        MultiSelectListPreference games = new MultiSelectListPreference(requireContext());
        games.setTitle(R.string.profile_games_title);
        PackageManager packageManager = requireContext().getPackageManager();
        // Launchable apps only, games first; services and libraries never belong in this list.
        List<ApplicationInfo> apps = new ArrayList<>();
        android.content.Intent launcher = new android.content.Intent(
                android.content.Intent.ACTION_MAIN).addCategory(
                android.content.Intent.CATEGORY_LAUNCHER);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (android.content.pm.ResolveInfo info
                : packageManager.queryIntentActivities(launcher, 0)) {
            ApplicationInfo app = info.activityInfo.applicationInfo;
            if (seen.add(app.packageName)
                    && !app.packageName.equals(requireContext().getPackageName())) {
                apps.add(app);
            }
        }
        java.util.Map<String, String> names = new java.util.HashMap<>();
        for (ApplicationInfo app : apps) {
            names.put(app.packageName, app.loadLabel(packageManager).toString());
        }
        apps.sort(Comparator.<ApplicationInfo, Boolean>comparing(
                        app -> app.category != ApplicationInfo.CATEGORY_GAME)
                .thenComparing(app -> names.get(app.packageName),
                        String.CASE_INSENSITIVE_ORDER));
        CharSequence[] labels = new CharSequence[apps.size()];
        CharSequence[] values = new CharSequence[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            ApplicationInfo app = apps.get(i);
            labels[i] = names.get(app.packageName) + "\n" + app.packageName;
            values[i] = app.packageName;
        }
        games.setEntries(labels);
        games.setEntryValues(values);
        GmConfig gm = new GmConfig(ctx);
        games.setValues(new java.util.LinkedHashSet<>(gm.games()));
        games.setSummary(getString(R.string.profile_games_summary, gm.games().size()));
        games.setOnPreferenceChangeListener((preference, value) -> {
            @SuppressWarnings("unchecked")
            Set<String> chosen = (Set<String>) value;
            gm.setGames(chosen);
            gm.requestGrants();
            games.setSummary(getString(R.string.profile_games_summary, chosen.size()));
            if (!chosen.isEmpty()) {
                org.petalos.petaltuner.OverlayService.start(ctx);
            }
            return true;
        });
        screen.addPreference(games);

        SwitchPreferenceCompat bypassAuto = switchPref(R.string.bypass_auto_title,
                R.string.bypass_auto_summary);
        bypassAuto.setChecked(engine.prefs().getBool(Prefs.BYPASS_AUTO, false));
        bypassAuto.setOnPreferenceChangeListener((preference, value) -> {
            engine.prefs().putBool(Prefs.BYPASS_AUTO, (Boolean) value);
            return true;
        });
    }
}
