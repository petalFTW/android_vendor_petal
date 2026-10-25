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

import android.os.Bundle;
import android.provider.Settings;
import android.graphics.Color;

import androidx.preference.SeekBarPreference;
import androidx.preference.ListPreference;
import androidx.preference.SwitchPreferenceCompat;
import androidx.preference.Preference;

import org.json.JSONException;
import org.json.JSONObject;

import org.petalos.config.PetalConfig;

/** Shared shade blur and Quick Settings tile style. */
public class QuickSettingsFragment extends PetalPreferenceFragment {
    private static final String SYSTEM_PALETTE = "android.theme.customization.system_palette";
    private static final String ACCENT_COLOR = "android.theme.customization.accent_color";
    private static final String COLOR_SOURCE = "android.theme.customization.color_source";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.quick_settings, rootKey);

        ColorPickerPreference palette = findPreference("petal_monet_palette");
        palette.setColor(readPaletteColor());
        palette.setOnPreferenceChangeListener((pref, value) -> {
            writePaletteColor((int) value);
            return true;
        });
        Preference reset = findPreference("petal_monet_reset");
        reset.setOnPreferenceClickListener(pref -> {
            writePaletteColor(null);
            palette.setColor(requireContext().getColor(android.R.color.system_accent1_500));
            return true;
        });

        SwitchPreferenceCompat skin = findPreference("qs_skin_enabled");
        skin.setChecked(PetalConfig.isQsSkinEnabled(requireContext()));
        skin.setOnPreferenceChangeListener((pref, newValue) -> {
            putInt(PetalConfig.KEY_QS_SKIN_ENABLED, (Boolean) newValue ? 1 : 0);
            return true;
        });

        ListPreference reach = findPreference("qs_reach_mode");
        reach.setValue(PetalConfig.getQsReachMode(requireContext()));
        reach.setOnPreferenceChangeListener((pref, value) -> {
            Settings.System.putString(getContentResolver(), PetalConfig.KEY_QS_REACH_MODE,
                    (String) value);
            return true;
        });

        SeekBarPreference emission = findPreference("qs_emission");
        emission.setMin(0);
        emission.setMax(100);
        int emissionValue = PetalConfig.getQsEmission(requireContext());
        emission.setValue(emissionValue);
        emission.setSummary(getString(R.string.qs_emission_summary, emissionValue));
        emission.setOnPreferenceChangeListener((pref, newValue) -> {
            int value = (int) newValue;
            putInt(PetalConfig.KEY_QS_EMISSION, value);
            emission.setSummary(getString(R.string.qs_emission_summary, value));
            return true;
        });

        SeekBarPreference blur = findPreference("qs_blur_intensity");
        blur.setMin(0);
        blur.setMax(100);
        int blurValue = getInt(PetalConfig.KEY_QS_BLUR_INTENSITY, PetalConfig.DEFAULT_BLUR_INTENSITY);
        blur.setValue(blurValue);
        blur.setSummary(getString(R.string.qs_blur_summary, blurValue));
        blur.setOnPreferenceChangeListener((pref, newValue) -> {
            int value = (int) newValue;
            putInt(PetalConfig.KEY_QS_BLUR_INTENSITY, value);
            blur.setSummary(getString(R.string.qs_blur_summary, value));
            return true;
        });

        SeekBarPreference mediaArtBlur = findPreference("qs_media_art_blur");
        mediaArtBlur.setMin(0);
        mediaArtBlur.setMax(100);
        int mediaArtBlurValue = Math.round(
                PetalConfig.getQsMediaArtBlurScale(requireContext()) * 100);
        mediaArtBlur.setValue(mediaArtBlurValue);
        mediaArtBlur.setSummary(getString(R.string.qs_media_art_blur_summary, mediaArtBlurValue));
        mediaArtBlur.setOnPreferenceChangeListener((pref, newValue) -> {
            int value = (int) newValue;
            putInt(PetalConfig.KEY_QS_MEDIA_ART_BLUR, value);
            mediaArtBlur.setSummary(getString(R.string.qs_media_art_blur_summary, value));
            return true;
        });

        SeekBarPreference refraction = findPreference("qs_media_refraction");
        refraction.setMin(0);
        refraction.setMax(100);
        int refractionValue = Math.round(
                PetalConfig.getQsMediaRefractionScale(requireContext()) * 100);
        refraction.setValue(refractionValue);
        refraction.setSummary(getString(R.string.qs_media_refraction_summary, refractionValue));
        refraction.setOnPreferenceChangeListener((pref, newValue) -> {
            int value = (int) newValue;
            putInt(PetalConfig.KEY_QS_MEDIA_REFRACTION, value);
            refraction.setSummary(getString(R.string.qs_media_refraction_summary, value));
            return true;
        });
    }

    private int readPaletteColor() {
        try {
            String setting = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES);
            if (setting != null) {
                String color = new JSONObject(setting).optString(SYSTEM_PALETTE);
                if (!color.isEmpty()) return Color.parseColor("#" + color.replace("#", ""));
            }
        } catch (JSONException | IllegalArgumentException ignored) {
        }
        return requireContext().getColor(android.R.color.system_accent1_500);
    }

    private void writePaletteColor(Integer color) {
        try {
            String setting = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES);
            JSONObject theme = new JSONObject(setting == null ? "{}" : setting);
            if (color == null) {
                theme.remove(SYSTEM_PALETTE);
                theme.remove(ACCENT_COLOR);
                theme.put(COLOR_SOURCE, "home_wallpaper");
            } else {
                String hex = String.format(java.util.Locale.ROOT, "%06X", color & 0xFFFFFF);
                theme.put(SYSTEM_PALETTE, hex);
                theme.put(ACCENT_COLOR, hex);
                theme.put(COLOR_SOURCE, "preset");
            }
            theme.put("_applied_timestamp", System.currentTimeMillis());
            Settings.Secure.putString(getContentResolver(),
                    Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES, theme.toString());
        } catch (JSONException ignored) {
        }
    }
}
