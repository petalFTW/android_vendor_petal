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
import android.net.Uri;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreference;

import java.util.List;

/** the list picker plus the github refresh button */
public class FilterListsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.filter_lists, rootKey);

        findPreference("catalog_update").setOnPreferenceClickListener(pref -> {
            refreshCatalog(pref);
            return true;
        });
        findPreference("yokoffing_repo").setOnPreferenceClickListener(pref -> {
            openRepo();
            return true;
        });

        rebuildSources();
    }

    @Override
    public void onResume() {
        super.onResume();
        rebuildSources();
    }

    private void rebuildSources() {
        final PreferenceCategory category = findPreference("sources_category");
        if (category == null) {
            return;
        }
        category.removeAll();
        final List<Source> sources = Catalog.load(requireContext());
        for (Source s : sources) {
            final SwitchPreference sw = new SwitchPreference(requireContext());
            sw.setKey("src_" + s.id);
            sw.setPersistent(false);
            sw.setTitle(s.name);
            sw.setSummary(s.group + " · " + s.url);
            sw.setChecked(Prefs.isSourceEnabled(requireContext(), s.id));
            sw.setOnPreferenceChangeListener((pref, value) -> {
                Prefs.setSourceEnabled(requireContext(), s.id, (Boolean) value);
                return true;
            });
            category.addPreference(sw);
        }
    }

    private void refreshCatalog(Preference pref) {
        final android.content.Context ctx = requireContext().getApplicationContext();
        pref.setSummary(R.string.updating);
        BlocklistManager.run(() -> {
            final boolean ok = BlocklistManager.refreshCatalog(ctx);
            BlocklistManager.post(() -> {
                if (!isAdded()) {
                    return;
                }
                pref.setSummary(ok ? R.string.update_catalog_ok : R.string.update_catalog_fail);
                rebuildSources();
            });
        });
    }

    private void openRepo() {
        final Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse(getString(R.string.yokoffing_url)));
        startActivity(intent);
    }
}
