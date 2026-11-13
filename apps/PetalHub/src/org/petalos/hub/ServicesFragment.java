/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.hub;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

// Google + petal microG are bundled, official microG stays a manual download
public final class ServicesFragment extends PetalPreferenceFragment {
    private static final String KEY_PROVIDER = "petal_services_setup_provider";
    // setup wizard writes this one too
    private static final String KEY_MICROG_FLAVOR = "petal_services_microg_flavor";
    private static final String FLAVOR_OFFICIAL = "official";
    private static final String FLAVOR_PETAL = "petal";
    private static final File MICROG_BUNDLE = new File("/product/etc/petal/services/microg");
    private static final String ACTION_STATUS = "org.petalos.hub.action.SERVICES_INSTALL_STATUS";
    private static final File BUNDLE = new File("/product/etc/petal/services/google");
    private final ArrayDeque<String> mRemoveQueue = new ArrayDeque<>();
    private AppPackageInstaller mInstaller;
    private BroadcastReceiver mReceiver;
    private Map<String, File[]> mGooglePackages;
    private boolean mBusy;
    private boolean mInstalling;
    private String mPendingProvider;
    private String mFlavor;
    private boolean mPendingPetal;

    @Override public void onCreatePreferences(Bundle state, String rootKey) {
        setPreferencesFromResource(R.xml.services, rootKey);
        mInstaller = new AppPackageInstaller(requireContext(), this::onResult, ACTION_STATUS);
        mReceiver = mInstaller.receiver();
        requireContext().registerReceiver(mReceiver, new IntentFilter(ACTION_STATUS),
                Context.RECEIVER_NOT_EXPORTED);
        ListPreference provider = findPreference(KEY_PROVIDER);
        String chosen = getStringSetting(KEY_PROVIDER);
        provider.setValue("microg".equals(chosen) ? "microg" : "google");
        mFlavor = FLAVOR_PETAL.equals(getStringSetting(KEY_MICROG_FLAVOR)) ? FLAVOR_PETAL
                : FLAVOR_OFFICIAL;
        provider.setSummaryProvider(preference -> "microg".equals(provider.getValue())
                ? getString(FLAVOR_PETAL.equals(mFlavor) ? R.string.services_microg_petal
                        : R.string.services_microg_official)
                : provider.getEntry());
        provider.setOnPreferenceChangeListener((preference, value) -> {
            if ("microg".equals(value)) {
                // hold the change til they pick a flavor, cancel = nothing moved
                pickFlavor(provider);
                return false;
            }
            putString(KEY_PROVIDER, (String) value);
            updateGuide((String) value);
            return true;
        });
        updateGuide(provider.getValue());
        findPreference("services_apply_provider").setOnPreferenceClickListener(preference -> {
            applyProvider(provider.getValue());
            return true;
        });
        link("services_microg_download", "https://github.com/microg/GmsCore/releases");
        link("services_google_guide", "https://grapheneos.org/usage#sandboxed-google-play");
        findPreference("services_gmscompat").setOnPreferenceClickListener(preference -> {
            open(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:app.grapheneos.gmscompat")));
            return true;
        });
        bindPackage("services_gms", "com.google.android.gms");
        bindPackage("services_store", "com.android.vending");
        bindPackage("services_gsf", "com.google.android.gsf");
    }

    private void pickFlavor(ListPreference provider) {
        CharSequence[] items = {
                twoLine(R.string.services_microg_official, R.string.services_microg_official_summary),
                twoLine(R.string.services_microg_petal, R.string.services_microg_petal_summary),
        };
        int[] picked = {FLAVOR_PETAL.equals(mFlavor) ? 1 : 0};
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.services_microg_flavor_title)
                .setSingleChoiceItems(items, picked[0], (dialog, which) -> picked[0] = which)
                .setPositiveButton(R.string.services_microg_pick, (dialog, which) -> {
                    mFlavor = picked[0] == 1 ? FLAVOR_PETAL : FLAVOR_OFFICIAL;
                    putString(KEY_MICROG_FLAVOR, mFlavor);
                    putString(KEY_PROVIDER, "microg");
                    provider.setValue("microg");
                    // setValue skips the redraw if it was already microg, poke the summary
                    provider.setSummaryProvider(provider.getSummaryProvider());
                    updateGuide("microg");
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private CharSequence twoLine(int titleId, int summaryId) {
        String title = getString(titleId);
        SpannableString text = new SpannableString(title + "\n" + getString(summaryId));
        text.setSpan(new StyleSpan(Typeface.BOLD), 0, title.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    @Override public void onResume() {
        super.onResume();
        bindPackage("services_gms", "com.google.android.gms");
        bindPackage("services_store", "com.android.vending");
        bindPackage("services_gsf", "com.google.android.gsf");
    }

    @Override public void onDestroy() {
        mBusy = false;
        if (mReceiver != null) {
            requireContext().unregisterReceiver(mReceiver);
            mReceiver = null;
        }
        super.onDestroy();
    }

    private void applyProvider(String provider) {
        if (mBusy) return;
        if (isProviderActive(provider)) {
            toast(getString(R.string.services_already_active));
            return;
        }
        mGooglePackages = null;
        mPendingPetal = "microg".equals(provider) && FLAVOR_PETAL.equals(mFlavor);
        if (mPendingPetal) {
            File gms = new File(MICROG_BUNDLE, "PetalMicrogGms.apk");
            File companion = new File(MICROG_BUNDLE, "PetalMicrogCompanion.apk");
            if (!gms.isFile() || !companion.isFile()) {
                toast(getString(R.string.services_microg_petal_missing));
                return;
            }
            // companion has to match the key or microG's settings perms lock it out
            mGooglePackages = new LinkedHashMap<>();
            mGooglePackages.put("com.google.android.gms", new File[]{gms});
            mGooglePackages.put("com.android.vending", new File[]{companion});
        } else if ("google".equals(provider)) {
            File[] gms = new File(BUNDLE, "gms").listFiles(
                    file -> file.isFile() && file.getName().endsWith(".apk"));
            File store = new File(BUNDLE, "store/gps.apk");
            File gsf = new File(BUNDLE, "gsf/gsf.apk");
            if (gms == null || gms.length != 11 || !new File(BUNDLE, "gms/base.apk").isFile()
                    || !store.isFile() || !gsf.isFile()) {
                toast(getString(R.string.services_bundle_missing));
                return;
            }
            Arrays.sort(gms, (a, b) -> a.getName().compareTo(b.getName()));
            mGooglePackages = new LinkedHashMap<>();
            mGooglePackages.put("com.google.android.gms", gms);
            mGooglePackages.put("com.android.vending", new File[]{store});
            mGooglePackages.put("com.google.android.gsf", new File[]{gsf});
        }
        mPendingProvider = provider;
        mRemoveQueue.clear();
        // gsf goes too, microG doesnt want google's sitting there
        for (String pkg : new String[]{"com.android.vending", "com.google.android.gsf",
                "com.google.android.gms"}) {
            try {
                PackageInfo info = requireContext().getPackageManager().getPackageInfo(pkg,
                        PackageManager.GET_META_DATA);
                Bundle metadata = info.applicationInfo.metaData;
                if (metadata == null || !metadata.getBoolean(
                        "org.petalos.services.PLACEHOLDER", false)
                        || info.getLongVersionCode() != 1) mRemoveQueue.add(pkg);
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        mBusy = true;
        mInstalling = false;
        findPreference("services_apply_provider").setEnabled(false);
        findPreference(KEY_PROVIDER).setEnabled(false);
        advance();
    }

    private void advance() {
        if (!mRemoveQueue.isEmpty()) {
            mInstaller.uninstall(mRemoveQueue.removeFirst());
        } else if (mGooglePackages != null) {
            mInstalling = true;
            mInstaller.installPackages(mGooglePackages);
        } else {
            finish(true, null);
        }
    }

    private boolean isProviderActive(String provider) {
        PackageManager pm = requireContext().getPackageManager();
        if ("microg".equals(provider) && FLAVOR_PETAL.equals(mFlavor)) {
            // petal build is signed like the OS
            return pm.checkSignatures("android", "com.google.android.gms")
                    == PackageManager.SIGNATURE_MATCH;
        }
        if ("microg".equals(provider)) {
            return pm.hasSigningCertificate("com.google.android.gms", com.android.internal.util.HexDump.hexStringToByteArray(
                    "9bd06727e62796c0130eb6dab39b73157451582cbd138e86c468acc395d14165"),
                    PackageManager.CERT_INPUT_SHA256);
        }
        return android.app.compat.gms.GmsCompat.isEnabledFor("com.google.android.gms",
                android.os.UserHandle.myUserId())
                && android.app.compat.gms.GmsCompat.isEnabledFor("com.android.vending",
                        android.os.UserHandle.myUserId());
    }

    private void onResult(boolean success, String message) {
        if (!mBusy) return;
        if (!success || mInstalling) finish(success, message);
        else advance();
    }

    private void finish(boolean success, String message) {
        mBusy = false;
        mInstalling = false;
        findPreference("services_apply_provider").setEnabled(true);
        findPreference(KEY_PROVIDER).setEnabled(true);
        bindPackage("services_gms", "com.google.android.gms");
        bindPackage("services_store", "com.android.vending");
        bindPackage("services_gsf", "com.google.android.gsf");
        toast(success ? getString("google".equals(mPendingProvider)
                ? R.string.services_google_installed : mPendingPetal
                        ? R.string.services_microg_petal_installed : R.string.services_microg_ready)
                : getString(R.string.services_install_failed, message));
    }

    private void updateGuide(String provider) {
        findPreference("services_setup_note").setSummary(!"microg".equals(provider)
                ? R.string.services_google_steps : FLAVOR_PETAL.equals(mFlavor)
                        ? R.string.services_microg_petal_steps : R.string.services_microg_steps);
        // official link is useless noise for the bundled build
        findPreference("services_microg_download").setVisible(
                !"microg".equals(provider) || !FLAVOR_PETAL.equals(mFlavor));
    }

    private void link(String key, String url) {
        findPreference(key).setOnPreferenceClickListener(preference -> {
            open(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            return true;
        });
    }

    private void bindPackage(String key, String packageName) {
        Preference preference = findPreference(key);
        if (preference == null) return;
        try {
            PackageManager pm = requireContext().getPackageManager();
            PackageInfo info = pm.getPackageInfo(packageName, PackageManager.GET_META_DATA);
            Bundle metadata = info.applicationInfo.metaData;
            boolean stub = info.getLongVersionCode() == 1 && metadata != null
                    && metadata.getBoolean("org.petalos.services.PLACEHOLDER", false);
            preference.setSummary(stub ? getString(R.string.services_placeholder)
                    : getString(R.string.services_package_installed,
                            info.applicationInfo.loadLabel(pm), info.versionName));
            preference.setEnabled(true);
        } catch (PackageManager.NameNotFoundException e) {
            preference.setSummary(R.string.services_package_missing);
            preference.setEnabled(false);
        }
        preference.setOnPreferenceClickListener(unused -> {
            open(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + packageName)));
            return true;
        });
    }

    private void open(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException e) { toast(getString(R.string.services_no_handler)); }
    }
}
