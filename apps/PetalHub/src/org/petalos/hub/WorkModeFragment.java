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

import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.database.ContentObserver;
import android.provider.Settings;
import android.provider.ContactsContract;
import android.text.TextUtils;

import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import org.petalos.config.PetalConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** work mode notification redaction settings */
public final class WorkModeFragment extends PetalPreferenceFragment {

    private static final String ADD_CONTACT_KEY = "petal_work_mode_add_contact";
    private static final int REQUEST_CONTACT = 4269;
    private final ExecutorService mLoader = Executors.newSingleThreadExecutor();
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private boolean mDestroyed;
    private final ContentObserver mModeObserver = new ContentObserver(mMain) {
        @Override
        public void onChange(boolean selfChange) {
            syncMode();
        }
    };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.work_mode, rootKey);

        ListPreference mode = findPreference(PetalConfig.KEY_WORK_MODE);
        mode.setPersistent(false);
        mode.setValue(String.valueOf(PetalConfig.getWorkModeLast(requireContext())));
        mode.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        mode.setOnPreferenceChangeListener((preference, value) -> {
            return PetalConfig.setWorkModePreference(requireContext(), Integer.parseInt((String) value));
        });

        MultiSelectListPreference apps = findPreference(PetalConfig.KEY_WORK_MODE_APPS);
        apps.setPersistent(false);
        populateApps(apps);
        apps.setOnPreferenceChangeListener((preference, value) -> {
            @SuppressWarnings("unchecked") Set<String> selected = (Set<String>) value;
            PetalConfig.setWorkModeApps(requireContext(), selected);
            updateAppsSummary(apps, selected);
            return true;
        });

        findPreference(ADD_CONTACT_KEY).setOnPreferenceClickListener(preference -> {
            // contacts app hands back one contact, no contacts permission needed
            try {
                startActivityForResult(new Intent(Intent.ACTION_PICK,
                        ContactsContract.Contacts.CONTENT_URI), REQUEST_CONTACT);
            } catch (ActivityNotFoundException unavailable) {
                toast(getString(R.string.wm_picker_missing));
            }
            return true;
        });
        rebuildContacts();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CONTACT && resultCode == android.app.Activity.RESULT_OK
                && data != null && data.getData() != null) {
            addContact(data.getData());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        getContentResolver().registerContentObserver(
                Settings.System.getUriFor(PetalConfig.KEY_WORK_MODE_LAST), false, mModeObserver);
        syncMode();
        rebuildContacts();
    }

    @Override
    public void onPause() {
        getContentResolver().unregisterContentObserver(mModeObserver);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        mDestroyed = true;
        mLoader.shutdownNow();
        super.onDestroy();
    }

    private void syncMode() {
        if (!isAdded()) return;
        ListPreference mode = findPreference(PetalConfig.KEY_WORK_MODE);
        if (mode != null) {
            mode.setValue(String.valueOf(PetalConfig.getWorkModeLast(requireContext())));
        }
    }

    private void populateApps(MultiSelectListPreference preference) {
        Context context = requireContext();
        preference.setEnabled(false);
        mLoader.execute(() -> {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> appsList = new ArrayList<>();
            // Notification senders don't all have launcher icons, genius.
            for (ApplicationInfo app : pm.getInstalledApplications(0)) {
                if (app.enabled) appsList.add(app);
            }
            appsList.sort(Comparator.comparing(
                    app -> app.loadLabel(pm).toString(), String.CASE_INSENSITIVE_ORDER));
            CharSequence[] labels = new CharSequence[appsList.size()];
            CharSequence[] values = new CharSequence[appsList.size()];
            for (int i = 0; i < appsList.size(); i++) {
                labels[i] = appsList.get(i).loadLabel(pm) + "\n" + appsList.get(i).packageName;
                values[i] = appsList.get(i).packageName;
            }
            mMain.post(() -> {
                if (mDestroyed || !isAdded()) return;
                preference.setEntries(labels);
                preference.setEntryValues(values);
                Set<String> selected = PetalConfig.getWorkModeApps(requireContext());
                preference.setValues(selected);
                updateAppsSummary(preference, selected);
                preference.setEnabled(true);
            });
        });
    }

    private void updateAppsSummary(Preference preference, Set<String> selected) {
        preference.setSummary(selected.isEmpty() ? getString(R.string.wm_apps_none)
                : getResources().getQuantityString(R.plurals.wm_apps_count,
                        selected.size(), selected.size()));
    }

    private void addContact(Uri contactUri) {
        String lookup = null;
        String name = null;
        try (Cursor cursor = requireContext().getContentResolver().query(contactUri,
                new String[]{ContactsContract.Contacts.LOOKUP_KEY,
                        ContactsContract.Contacts.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                lookup = cursor.getString(0);
                name = cursor.getString(1);
            }
        } catch (Exception e) {
            toast(getString(R.string.wm_contact_read_failed));
            return;
        }
        if (TextUtils.isEmpty(lookup) || TextUtils.isEmpty(name)) return;
        List<String[]> contacts = PetalConfig.getWorkModeContacts(requireContext());
        for (String[] contact : contacts) {
            if (lookup.equals(contact[0])) {
                toast(name);
                return;
            }
        }
        contacts.add(new String[]{lookup, name});
        PetalConfig.setWorkModeContacts(requireContext(), contacts);
        rebuildContacts();
    }

    private void rebuildContacts() {
        PreferenceCategory category = findPreference("petal_work_mode_contacts_list");
        if (category == null) return;
        category.removeAll();
        List<String[]> contacts = PetalConfig.getWorkModeContacts(requireContext());
        Preference add = findPreference(ADD_CONTACT_KEY);
        add.setSummary(contacts.isEmpty() ? getString(R.string.wm_contacts_none)
                : getResources().getQuantityString(R.plurals.wm_contacts_count,
                        contacts.size(), contacts.size()));
        for (String[] contact : contacts) {
            Preference entry = new Preference(requireContext());
            entry.setKey("contact:" + contact[0]);
            entry.setTitle(contact[1]);
            entry.setSummary(getString(R.string.wm_remove_contact));
            entry.setOnPreferenceClickListener(preference -> {
                List<String[]> keep = PetalConfig.getWorkModeContacts(requireContext());
                keep.removeIf(item -> item[0].equals(contact[0]));
                PetalConfig.setWorkModeContacts(requireContext(), keep);
                rebuildContacts();
                return true;
            });
            category.addPreference(entry);
        }
    }
}
