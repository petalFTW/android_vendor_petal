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
import android.content.om.OverlayManager;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;

import androidx.preference.Preference;
import androidx.preference.SeekBarPreference;
import androidx.preference.ListPreference;

import org.petalos.config.PetalConfig;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Launcher icon pack and app drawer appearance. */
public class LauncherFragment extends PetalPreferenceFragment {

    private static final String COMMISSIONER_OVERLAY =
            "org.petalos.overlay.font.commissioner";
    private static final String FONT_CATEGORY = "android.theme.customization.font";
    private static final String KEY_FONT_DEFAULT_APPLIED = "petal_font_default_applied";

    private Preference mIconPack;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.launcher, rootKey);

        ListPreference font = findPreference("system_font");
        refreshFontPreference(font);
        font.setOnPreferenceChangeListener((pref, value) -> {
            setSystemFont("commissioner".equals(value));
            refreshFontPreference((ListPreference) pref);
            return true;
        });

        SeekBarPreference blur = findPreference("apps_blur_intensity");
        blur.setMin(0);
        blur.setMax(100);
        int blurValue = Math.round(PetalConfig.getAppsBlurScale(requireContext()) * 100);
        blur.setValue(blurValue);
        blur.setSummary(getString(R.string.apps_blur_summary, blurValue));
        blur.setOnPreferenceChangeListener((pref, newValue) -> {
            int value = (int) newValue;
            putInt(PetalConfig.KEY_APPS_BLUR_INTENSITY, value);
            blur.setSummary(getString(R.string.apps_blur_summary, value));
            return true;
        });

        mIconPack = findPreference("icon_pack");
        updateIconPackSummary();
        mIconPack.setOnPreferenceClickListener(pref -> {
            showIconPackDialog();
            return true;
        });
    }

    private void showIconPackDialog() {
        List<IconPackHelper.Entry> packs = IconPackHelper.getInstalledIconPacks(requireContext());

        List<String> labels = new ArrayList<>();
        List<String> packages = new ArrayList<>();
        labels.add(getString(R.string.icon_pack_none));
        packages.add("");

        for (IconPackHelper.Entry entry : packs) {
            labels.add(entry.label);
            packages.add(entry.packageName);
        }

        String current = PetalConfig.getIconPack(requireContext());
        int selected = packages.indexOf(current == null ? "" : current);
        if (selected < 0) {
            selected = 0;
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.icon_pack_title)
                .setSingleChoiceItems(
                        labels.toArray(new String[0]),
                        selected,
                        (dialog, which) -> {
                            PetalConfig.setIconPack(requireContext(), packages.get(which));
                            updateIconPackSummary();
                            dialog.dismiss();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateIconPackSummary() {
        String current = PetalConfig.getIconPack(requireContext());
        if (current == null || current.isEmpty()) {
            mIconPack.setSummary(R.string.icon_pack_none);
            return;
        }
        String label = IconPackHelper.labelFor(requireContext(), current);
        mIconPack.setSummary(label != null ? label : getString(R.string.icon_pack_disabled));
    }

    /** Font overlay currently recorded in the system theme setting, or "". */
    private String readFontOverlay() {
        try {
            String setting = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES);
            if (setting != null) {
                return new JSONObject(setting).optString(FONT_CATEGORY, "");
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private void refreshFontPreference(ListPreference font) {
        if (font == null) {
            return;
        }
        boolean commissioner = COMMISSIONER_OVERLAY.equals(readFontOverlay());
        font.setValue(commissioner ? "commissioner" : "default");
        font.setSummary(font.getEntry());
    }

    private void setSystemFont(boolean commissioner) {
        // The theme framework reconciles font overlays against
        // THEME_CUSTOMIZATION_OVERLAY_PACKAGES, so write the setting first and flip
        // the overlay as a best effort for immediate feedback.
        try {
            String setting = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES);
            JSONObject theme = new JSONObject(setting == null ? "{}" : setting);
            if (commissioner) {
                theme.put(FONT_CATEGORY, COMMISSIONER_OVERLAY);
            } else {
                theme.remove(FONT_CATEGORY);
            }
            theme.put("_applied_timestamp", System.currentTimeMillis());
            Settings.Secure.putString(getContentResolver(),
                    Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES, theme.toString());
            Settings.Secure.putInt(getContentResolver(), KEY_FONT_DEFAULT_APPLIED, 1);
        } catch (Exception ignored) {
        }

        OverlayManager overlays = requireContext().getSystemService(OverlayManager.class);
        if (overlays == null) {
            return;
        }
        try {
            if (commissioner) {
                overlays.setEnabledExclusiveInCategory(COMMISSIONER_OVERLAY,
                        Process.myUserHandle());
            } else {
                overlays.setEnabled(COMMISSIONER_OVERLAY, false, Process.myUserHandle());
            }
        } catch (Exception ignored) {
            // the theme framework will apply it from the setting
        }
    }
}
