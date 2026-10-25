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

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import org.petalos.petaltuner.R;
import org.petalos.petaltuner.core.Prefs;
import org.petalos.petaltuner.core.Sysfs;

public final class ExpertFragment extends BasePreferenceFragment {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        createScreen();

        SwitchPreferenceCompat unlock = switchPref(R.string.expert_unlock_title,
                R.string.expert_unlock_summary);
        unlock.setChecked(engine.prefs().getBool(Prefs.EXPERT_UNLOCKED, false));
        unlock.setOnPreferenceChangeListener((preference, value) -> {
            boolean enabled = (Boolean) value;
            if (enabled) {
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.expert_unlock_title)
                        .setMessage(R.string.expert_warning)
                        .setPositiveButton(R.string.action_apply, (dialog, which) -> {
                            engine.prefs().putBool(Prefs.EXPERT_UNLOCKED, true);
                            refresh();
                        })
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
                return false;
            }
            engine.prefs().putBool(Prefs.EXPERT_UNLOCKED, false);
            refresh();
            return true;
        });

        boolean expert = engine.prefs().getBool(Prefs.EXPERT_UNLOCKED, false);
        category(R.string.category_expert);

        SwitchPreferenceCompat fsync = switchPref(R.string.expert_fsync_title,
                R.string.expert_fsync_summary);
        fsync.setEnabled(expert);
        fsync.setChecked(engine.prefs().getBool(Prefs.EXPERT_FSYNC, false));
        fsync.setOnPreferenceChangeListener((preference, value) -> {
            boolean enabled = (Boolean) value;
            engine.prefs().putBool(Prefs.EXPERT_FSYNC, enabled);
            String node = Sysfs.firstExisting(
                    "/sys/module/sync/parameters/fsync_enabled",
                    "/sys/kernel/fsync/fsync_enabled");
            if (node != null) {
                engine.backup().capture(node);
                Sysfs.write(node, enabled ? "0" : "1");
            } else {
                toast(R.string.bypass_unavailable);
            }
            return true;
        });

        SwitchPreferenceCompat thermal = switchPref(R.string.expert_thermal_title,
                R.string.expert_thermal_summary);
        thermal.setEnabled(expert);
        thermal.setChecked(engine.prefs().getBool(Prefs.THERMAL_DISABLE, false));
        thermal.setOnPreferenceChangeListener((preference, value) -> {
            engine.performance().setThermalOverride((Boolean) value);
            return true;
        });

        Preference raw = action(R.string.expert_raw_title, R.string.expert_raw_summary);
        raw.setOnPreferenceClickListener(preference -> {
            showRawEditor();
            return true;
        });

        Preference restore = action(R.string.profile_restore_title,
                R.string.profile_restore_summary);
        restore.setOnPreferenceClickListener(preference -> {
            engine.restoreDefaults();
            toast(R.string.toast_restored);
            return true;
        });
    }

    private void refresh() {
        getPreferenceScreen().removeAll();
        onCreatePreferences(null, null);
    }

    private void showRawEditor() {
        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding / 2, padding, 0);

        EditText pathField = new EditText(requireContext());
        pathField.setHint("sysfs path");
        pathField.setInputType(InputType.TYPE_CLASS_TEXT);
        container.addView(pathField);

        EditText valueField = new EditText(requireContext());
        valueField.setHint("value");
        valueField.setInputType(InputType.TYPE_CLASS_TEXT);
        container.addView(valueField);

        TextView result = new TextView(requireContext());
        result.setPadding(0, padding / 2, 0, 0);
        container.addView(result, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.expert_raw_title)
                .setView(container)
                .setPositiveButton(R.string.action_apply, (dialog, which) -> {
                    String path = pathField.getText().toString().trim();
                    String value = valueField.getText().toString().trim();
                    if (path.isEmpty()) {
                        toast(R.string.value_unknown);
                        return;
                    }
                    if (value.isEmpty()) {
                        String read = Sysfs.read(path);
                        toast(path + " = " + (read == null ? "?" : read));
                    } else {
                        engine.backup().capture(path);
                        boolean ok = Sysfs.write(path, value);
                        toast(path + (ok ? " ✓" : " ✗"));
                    }
                })
                .setNeutralButton("Read", (dialog, which) -> {
                    String path = pathField.getText().toString().trim();
                    String read = Sysfs.read(path);
                    if (read != null) {
                        valueField.setText(read);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
