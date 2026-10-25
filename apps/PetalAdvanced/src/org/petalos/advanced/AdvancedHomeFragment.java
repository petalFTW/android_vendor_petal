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

package org.petalos.advanced;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.SwitchPreference;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** the front door: master switch and quick links */
public class AdvancedHomeFragment extends PetalPreferenceFragment {

    private SwitchPreference mEnabled;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.advanced_home, rootKey);

        mEnabled = findPreference("blocker_enabled");
        final Preference status = findPreference("blocker_status");
        final Preference lists = findPreference("filter_lists");
        final Preference sites = findPreference("blocked_sites");
        final Preference update = findPreference("update_now");

        mEnabled.setChecked(SystemStatus.isOn(requireContext()));
        mEnabled.setOnPreferenceChangeListener((pref, value) -> {
            applyToggle((Boolean) value);
            return true;
        });

        lists.setOnPreferenceClickListener(pref -> {
            openScreen("sources");
            return true;
        });
        sites.setOnPreferenceClickListener(pref -> {
            openScreen("sites");
            return true;
        });
        update.setOnPreferenceClickListener(pref -> {
            runUpdate(pref);
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // match the switch and the icon/tile to what the system actually has
        SystemStatus.refresh(requireContext());
        mEnabled.setChecked(SystemStatus.isOn(requireContext()));
        refreshStatus();
    }

    private void applyToggle(boolean on) {
        final android.content.Context ctx = requireContext().getApplicationContext();
        Prefs.setEnabled(ctx, on);
        mEnabled.setChecked(on);
        BlocklistManager.run(() -> {
            BlocklistManager.setEnabled(ctx, on);
            BlocklistManager.post(this::refreshStatus);
        });
    }

    private void runUpdate(Preference pref) {
        final android.content.Context ctx = requireContext().getApplicationContext();
        pref.setSummary(R.string.updating);
        BlocklistManager.run(() -> {
            final BlocklistManager.Result result = BlocklistManager.update(ctx);
            BlocklistManager.post(() -> {
                if (isAdded()) {
                    Toast.makeText(requireContext(),
                            getString(R.string.update_ok, result.count, result.sources),
                            Toast.LENGTH_SHORT).show();
                    pref.setSummary(R.string.update_now_summary);
                    refreshStatus();
                }
            });
        });
    }

    private void refreshStatus() {
        if (!isAdded()) {
            return;
        }
        final android.content.Context ctx = requireContext();
        final Preference status = findPreference("blocker_status");
        if (status == null) {
            return;
        }
        if (!SystemStatus.isOn(ctx)) {
            status.setSummary(R.string.status_disabled);
            return;
        }
        final boolean helper = HostsWriter.helperUsable();
        final int count = Prefs.lastCount(ctx);
        final int sources = Prefs.lastSources(ctx);
        final int enabledSources = Prefs.enabledSources(ctx).size();
        if (count == 0) {
            String s;
            if (enabledSources == 0) {
                s = getString(R.string.status_no_sources);
            } else if (sources == 0) {
                s = getString(R.string.status_sources_pending, enabledSources);
            } else {
                s = getString(R.string.status_no_sources);
            }
            if (!helper) {
                s = s + "\n" + getString(R.string.status_helper_missing);
            }
            status.setSummary(s);
            return;
        }
        final String when = Prefs.lastUpdate(ctx) == 0
                ? getString(R.string.status_never)
                : new SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
                        .format(new Date(Prefs.lastUpdate(ctx)));
        String summary = getString(R.string.status_blocked, count, when);
        if (!helper) {
            summary = summary + "\n" + getString(R.string.status_helper_missing);
        }
        status.setSummary(summary);
    }

    private void openScreen(String screen) {
        final Intent intent = new Intent(requireContext(), AdvancedActivity.class);
        intent.putExtra(AdvancedActivity.EXTRA_SCREEN, screen);
        startActivity(intent);
    }
}
