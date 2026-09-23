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
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.SeekBarPreference;

import org.petalos.config.PetalConfig;

import java.util.ArrayList;
import java.util.List;

/** Launcher icon pack and app drawer appearance. */
public class LauncherFragment extends PetalPreferenceFragment {

    private Preference mIconPack;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.launcher, rootKey);

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
}
