/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.petalos.hub;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// optional apps go over their shells; removing an update brings the shell back
public class PetalAppCenterFragment extends PetalPreferenceFragment {

    // ROM payloads first, root's testing stash last
    private static final File[] APK_DIRS = {
            new File("/system_ext/etc/petal/appcenter"),
            new File("/product/etc/petal/appcenter"),
            new File("/data/petal/appcenter"),
    };

    private AppPackageInstaller mInstaller;
    private boolean mReceiverRegistered;

    private final BroadcastReceiver mStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(AppPackageInstaller.EXTRA_STATUS,
                    android.content.pm.PackageInstaller.STATUS_FAILURE);
            if (AppPackageInstaller.handlePendingAction(context, intent, status)) return;
            boolean success = status == android.content.pm.PackageInstaller.STATUS_SUCCESS;
            String message = AppPackageInstaller.resultMessage(intent, status);
            toast(success ? getString(R.string.app_center_done)
                    : getString(R.string.app_center_failed_reason, message));
            rebuild();
        }
    };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        mInstaller = new AppPackageInstaller(requireContext(),
                (success, message) -> {
                    if (!isAdded()) return;
                    toast(success ? getString(R.string.app_center_done)
                            : getString(R.string.app_center_failed_reason, message));
                    rebuild();
                });
        rebuild();
    }

    @Override
    public void onResume() {
        super.onResume();
        rebuild();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!mReceiverRegistered) {
            requireContext().registerReceiver(mStatusReceiver,
                    new IntentFilter(AppPackageInstaller.ACTION_STATUS),
                    Context.RECEIVER_NOT_EXPORTED);
            mReceiverRegistered = true;
        }
    }

    @Override
    public void onStop() {
        if (mReceiverRegistered) {
            requireContext().unregisterReceiver(mStatusReceiver);
            mReceiverRegistered = false;
        }
        super.onStop();
    }

    private void rebuild() {
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);

        Preference note = new Preference(requireContext());
        note.setTitle(R.string.app_center_note_title);
        note.setSummary(R.string.app_center_note_summary);
        note.setSelectable(false);
        screen.addPreference(note);

        List<BundledApp> apps = scan();
        if (apps.isEmpty()) {
            Preference empty = new Preference(requireContext());
            empty.setTitle(R.string.app_center_empty);
            empty.setSelectable(false);
            screen.addPreference(empty);
            return;
        }

        PackageManager pm = requireContext().getPackageManager();
        for (BundledApp app : apps) {
            addApp(screen, pm, app);
        }
    }

    private void addApp(PreferenceScreen screen, PackageManager pm, BundledApp app) {
        Context context = requireContext();
        boolean installed = isFullAppInstalled(pm, app.packageName);

        PreferenceCategory category = new PreferenceCategory(context);
        category.setTitle(app.label);
        screen.addPreference(category);

        Preference info = new Preference(context);
        info.setSelectable(false);
        info.setIcon(app.icon);
        info.setTitle(app.label);
        info.setSummary(getString(R.string.app_center_version,
                app.versionName, installed ? getString(R.string.app_center_installed)
                        : getString(R.string.app_center_not_installed)));
        screen.addPreference(info);

        Preference install = new Preference(context);
        install.setTitle(installed ? R.string.app_center_reinstall : R.string.app_center_install);
        install.setSummary(R.string.app_center_install_summary);
        install.setOnPreferenceClickListener(preference -> {
            toast(getString(R.string.app_center_installing, app.label));
            mInstaller.install(app.apk);
            return true;
        });
        screen.addPreference(install);

        Preference uninstall = new Preference(context);
        uninstall.setTitle(R.string.app_center_uninstall);
        uninstall.setSummary(R.string.app_center_uninstall_summary);
        uninstall.setEnabled(installed);
        uninstall.setOnPreferenceClickListener(preference -> {
            confirmUninstall(app);
            return true;
        });
        screen.addPreference(uninstall);

        Preference open = new Preference(context);
        open.setTitle(R.string.app_center_open);
        open.setEnabled(installed);
        open.setOnPreferenceClickListener(preference -> {
            Intent launch = pm.getLaunchIntentForPackage(app.packageName);
            if (launch != null) {
                startActivity(launch);
            }
            return true;
        });
        screen.addPreference(open);
    }

    private void confirmUninstall(BundledApp app) {
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.app_center_uninstall_title, app.label))
                .setMessage(R.string.app_center_uninstall_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.app_center_uninstall, (dialog, which) -> {
                    toast(getString(R.string.app_center_uninstalling, app.label));
                    mInstaller.uninstall(app.packageName);
                })
                .show();
    }

    private boolean isFullAppInstalled(PackageManager pm, String packageName) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
            if (info.metaData != null && info.metaData.getBoolean(
                    "org.petalos.appcenter.PLACEHOLDER", false)) return false;
            // older shells didn't have the marker, so check their actual APK path too
            String path = info.sourceDir;
            return path != null && !path.contains("/PetalTunerStub/")
                    && !path.contains("/PetalAssistantStub/");
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private List<BundledApp> scan() {
        List<BundledApp> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        PackageManager pm = requireContext().getPackageManager();
        for (File dir : APK_DIRS) {
            File[] files = dir.listFiles();
            if (files == null) {
                continue;
            }
            Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File file : files) {
                if (!file.isFile() || !file.getName().endsWith(".apk")) {
                    continue;
                }
                PackageInfo info = pm.getPackageArchiveInfo(file.getAbsolutePath(), 0);
                if (info == null || info.applicationInfo == null || info.packageName == null
                        || !seen.add(info.packageName)) {
                    continue;
                }
                ApplicationInfo application = info.applicationInfo;
                application.sourceDir = file.getAbsolutePath();
                application.publicSourceDir = file.getAbsolutePath();
                BundledApp app = new BundledApp();
                app.apk = file;
                app.packageName = info.packageName;
                app.versionName = info.versionName != null ? info.versionName : "";
                app.label = application.loadLabel(pm);
                app.icon = application.loadIcon(pm);
                result.add(app);
            }
        }
        return result;
    }

    private static final class BundledApp {
        File apk;
        String packageName;
        String versionName;
        CharSequence label;
        Drawable icon;
    }
}
