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

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

// same grouped rows as Settings, no separate glass-tile universe
public class HubDashboardFragment extends PetalPreferenceFragment {

    private static final String KEY_PETAL_SETTINGS_DEFAULT = "petal_settings_default";
    private SwitchPreferenceCompat mDefaultSettings;

    private static final class Entry {
        final int titleRes;
        final int summaryRes;
        final int iconRes;
        final String fragmentClass;
        final String targetPackage;
        final String targetClass;
        final String screen;

        Entry(int titleRes, int summaryRes, int iconRes, String fragmentClass) {
            this(titleRes, summaryRes, iconRes, fragmentClass, null, null, null);
        }

        Entry(int titleRes, int summaryRes, int iconRes, String fragmentClass,
                String targetPackage, String targetClass) {
            this(titleRes, summaryRes, iconRes, fragmentClass, targetPackage, targetClass, null);
        }

        Entry(int titleRes, int summaryRes, int iconRes, String fragmentClass,
                String targetPackage, String targetClass, String screen) {
            this.titleRes = titleRes;
            this.summaryRes = summaryRes;
            this.iconRes = iconRes;
            this.fragmentClass = fragmentClass;
            this.targetPackage = targetPackage;
            this.targetClass = targetClass;
            this.screen = screen;
        }
    }

    private static final Entry[] PERSONALIZATION = {
            new Entry(R.string.category_quick_settings, R.string.quick_settings_summary,
                    R.drawable.ic_hub_qs, QuickSettingsFragment.class.getName()),
            new Entry(R.string.category_launcher, R.string.launcher_summary,
                    R.drawable.ic_hub_launcher, LauncherFragment.class.getName()),
            new Entry(R.string.category_dialogs, R.string.dialogs_summary,
                    R.drawable.ic_hub_dialogs, DialogsFragment.class.getName()),
            new Entry(R.string.category_island, R.string.island_summary,
                    R.drawable.ic_hub_island, IslandFragment.class.getName()),
            new Entry(R.string.depth_studio_title, R.string.depth_studio_summary,
                    R.drawable.ic_hub_lockscreen, null,
                    "org.petalos.depth", "org.petalos.depth.DepthStudioActivity"),
    };

    /** the advanced corner. one tile, the app has the rest inside. */
    private static final Entry[] ADVANCED = {
            new Entry(R.string.category_blocker, R.string.blocker_summary,
                    R.drawable.ic_hub_advanced, null,
                    "org.petalos.advanced", "org.petalos.advanced.AdvancedActivity",
                    "home"),
            new Entry(R.string.proton4a_title, R.string.proton4a_summary,
                    R.drawable.ic_hub_advanced, Proton4AFragment.class.getName()),
    };

    /** privacy stuff. the ad id page lives inside GMS, so it's only here if gms is. */
    private static final Entry[] PRIVACY = {
            new Entry(R.string.category_work_mode, R.string.work_mode_summary,
                    R.drawable.ic_hub_privacy, WorkModeFragment.class.getName()),
            new Entry(R.string.ads_privacy_title, R.string.ads_privacy_summary,
                    R.drawable.ic_hub_privacy, null,
                    "com.google.android.gms",
                    "com.google.android.gms.adid.settings.AdsSettingsActivity"),
    };

    private static final Entry[] DEVICE = {
            new Entry(R.string.category_power, R.string.power_summary,
                    R.drawable.ic_hub_power, PowerFragment.class.getName()),
            new Entry(R.string.category_game_mode, R.string.game_mode_summary,
                    R.drawable.ic_hub_game_mode, GameModeFragment.class.getName()),
            new Entry(R.string.category_bridge, R.string.bridge_summary,
                    R.drawable.ic_hub_qs, BridgeFragment.class.getName()),
    };

    /** optional petalOS apps, install/uninstall at will */
    private static final Entry[] APPS = {
            new Entry(R.string.services_title, R.string.services_summary,
                    R.drawable.ic_hub_appdrawer, ServicesFragment.class.getName()),
            new Entry(R.string.category_app_center, R.string.app_center_summary,
                    R.drawable.ic_hub_appdrawer, PetalAppCenterFragment.class.getName()),
    };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);
        addEntry(screen, new Entry(R.string.category_lockscreen, R.string.lockscreen_summary,
                R.drawable.ic_hub_lockscreen, LockscreenFragment.class.getName()));
        mDefaultSettings = new SwitchPreferenceCompat(requireContext());
        mDefaultSettings.setKey(KEY_PETAL_SETTINGS_DEFAULT);
        mDefaultSettings.setPersistent(false);
        mDefaultSettings.setTitle(R.string.use_petal_settings_default);
        mDefaultSettings.setSummaryOn(R.string.petal_settings_default_on);
        mDefaultSettings.setSummaryOff(R.string.petal_settings_default_off);
        mDefaultSettings.setChecked(getInt(KEY_PETAL_SETTINGS_DEFAULT, 1) != 0);
        mDefaultSettings.setOnPreferenceChangeListener((preference, value) ->
                Settings.System.putInt(getContentResolver(), KEY_PETAL_SETTINGS_DEFAULT,
                        (Boolean) value ? 1 : 0));
        screen.addPreference(mDefaultSettings);
        buildSection(screen, R.string.dashboard_section_personalization, PERSONALIZATION);
        buildSection(screen, R.string.dashboard_section_advanced, ADVANCED);
        buildSection(screen, R.string.dashboard_section_privacy, PRIVACY);
        buildSection(screen, R.string.dashboard_section_device, DEVICE);
        buildSection(screen, R.string.dashboard_section_apps, APPS);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mDefaultSettings != null) {
            mDefaultSettings.setChecked(getInt(KEY_PETAL_SETTINGS_DEFAULT, 1) != 0);
        }
    }

    private void buildSection(PreferenceScreen screen, int titleRes, Entry[] entries) {
        PreferenceCategory category = new PreferenceCategory(requireContext());
        category.setTitle(titleRes);
        screen.addPreference(category);
        for (Entry entry : entries) addEntry(category, entry);
    }

    private void addEntry(androidx.preference.PreferenceGroup group, Entry entry) {
        Preference preference = new Preference(requireContext());
        preference.setTitle(entry.titleRes);
        preference.setSummary(entry.summaryRes);
        android.graphics.drawable.Drawable icon = requireContext().getDrawable(entry.iconRes).mutate();
        icon.setTint(requireContext().getColor(R.color.petal_settings_accent));
        preference.setIcon(icon);
        group.addPreference(preference);
        preference.setOnPreferenceClickListener(unused -> {
            if (entry.fragmentClass != null) {
                openFragment(entry.fragmentClass, getString(entry.titleRes));
            } else if (entry.targetPackage != null) {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.setClassName(entry.targetPackage, entry.targetClass);
                if (entry.screen != null) {
                    intent.putExtra(":petal:screen", entry.screen);
                }
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    // app behind the tile got yanked, cry about it nicely
                    new AlertDialog.Builder(requireContext())
                            .setTitle(entry.titleRes)
                            .setMessage(R.string.hub_target_missing)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
            }
            return true;
        });
    }

    private void openFragment(String fragmentClass, CharSequence title) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClass(requireContext(), HubActivity.class);
        intent.putExtra(HubActivity.EXTRA_SHOW_FRAGMENT, fragmentClass);
        if (title != null) {
            intent.putExtra(HubActivity.EXTRA_SHOW_FRAGMENT_TITLE, title);
        }
        startActivity(intent);
    }
}
