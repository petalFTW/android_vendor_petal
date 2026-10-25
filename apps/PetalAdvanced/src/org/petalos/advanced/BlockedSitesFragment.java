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

import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import java.util.Set;
import java.util.regex.Pattern;

/** add and remove custom domains */
public class BlockedSitesFragment extends PetalPreferenceFragment {

    private static final Pattern DOMAIN = Pattern.compile(
            "^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?"
                    + "(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$");

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.blocked_sites, rootKey);

        findPreference("add_site").setOnPreferenceClickListener(pref -> {
            showAddDialog();
            return true;
        });

        rebuild();
    }

    private void rebuild() {
        final PreferenceCategory category = findPreference("custom_category");
        if (category == null) {
            return;
        }
        category.removeAll();
        final Set<String> domains = Prefs.customDomains(requireContext());
        if (domains.isEmpty()) {
            final Preference empty = new Preference(requireContext());
            empty.setPersistent(false);
            empty.setSelectable(false);
            empty.setTitle(R.string.custom_sites_empty);
            category.addPreference(empty);
            return;
        }
        for (String domain : domains) {
            final Preference row = new Preference(requireContext());
            row.setPersistent(false);
            row.setTitle(domain);
            row.setSummary(R.string.remove);
            row.setOnPreferenceClickListener(pref -> {
                confirmRemove(domain);
                return true;
            });
            category.addPreference(row);
        }
    }

    private void showAddDialog() {
        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.add_site_summary);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.add_site_title)
                .setView(input)
                .setPositiveButton(R.string.add, (dialog, which) -> {
                    final String raw = input.getText().toString().trim().toLowerCase();
                    final String domain = raw.replaceAll("^https?://", "").replaceAll("/.*$", "");
                    if (!DOMAIN.matcher(domain).matches()) {
                        Toast.makeText(requireContext(), R.string.bad_domain,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Prefs.addCustomDomain(requireContext(), domain);
                    rebuild();
                    reapply();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmRemove(String domain) {
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.remove_site_title, domain))
                .setPositiveButton(R.string.remove, (dialog, which) -> {
                    Prefs.removeCustomDomain(requireContext(), domain);
                    rebuild();
                    reapply();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** custom changes only matter once the merged list gets rewritten */
    private void reapply() {
        if (!Prefs.isEnabled(requireContext())) {
            return;
        }
        final android.content.Context ctx = requireContext().getApplicationContext();
        BlocklistManager.run(() -> {
            if (!BlocklistManager.applyCache(ctx)) {
                BlocklistManager.update(ctx);
            }
        });
    }
}
