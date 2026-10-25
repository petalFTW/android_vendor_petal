/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.petalos.hub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import org.petalos.proton4a.DebugReport;
import org.petalos.proton4a.RuntimeManager;
import org.petalos.proton4a.runtime.ProgramApk;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** Settings entry for the independently developed Proton4A runtime. */
public class Proton4AFragment extends PreferenceFragmentCompat {
    private Preference importPreference;
    private Preference detailsPreference;
    private Preference runtimePreference;
    private Preference debugPreference;
    private boolean installingRuntime;
    private boolean buildingProgram;

    private final ActivityResultLauncher<Intent> exePicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
                Uri uri = result.getData().getData();
                if (uri == null) return;
                String name = displayName(uri);
                if (!name.toLowerCase(Locale.ROOT).endsWith(".exe")) {
                    detailsPreference.setSummary(R.string.proton4a_invalid_file);
                    return;
                }
                buildProgram(uri, name);
            });

    private final ActivityResultLauncher<Intent> apkInstaller = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                detailsPreference.setSummary(result.getResultCode() == Activity.RESULT_OK
                        ? R.string.proton4a_apk_installed : R.string.proton4a_apk_not_installed);
            });

    private final ActivityResultLauncher<Intent> runtimePicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
                Uri uri = result.getData().getData();
                if (uri == null) return;
                String name = displayName(uri);
                if (!name.toLowerCase(Locale.ROOT).endsWith(".tar.gz")) {
                    runtimePreference.setSummary(R.string.proton4a_runtime_invalid_file);
                    return;
                }
                installRuntime(uri);
            });

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);

        importPreference = new Preference(requireContext());
        importPreference.setTitle(R.string.proton4a_import);
        importPreference.setSummary(R.string.proton4a_import_summary);
        importPreference.setOnPreferenceClickListener(preference -> {
            Intent request = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            request.addCategory(Intent.CATEGORY_OPENABLE);
            request.setType("*/*");
            exePicker.launch(request);
            return true;
        });
        screen.addPreference(importPreference);

        detailsPreference = new Preference(requireContext());
        detailsPreference.setTitle(R.string.proton4a_program);
        detailsPreference.setSummary(R.string.proton4a_empty);
        detailsPreference.setSelectable(false);
        screen.addPreference(detailsPreference);

        runtimePreference = new Preference(requireContext());
        runtimePreference.setTitle(R.string.proton4a_runtime);
        screen.addPreference(runtimePreference);
        runtimePreference.setOnPreferenceClickListener(preference -> {
            if (new File(RuntimeManager.STAGED_PAYLOAD).isFile()) {
                installRuntime(null);
            } else {
                Intent request = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                request.addCategory(Intent.CATEGORY_OPENABLE);
                request.setType("*/*");
                runtimePicker.launch(request);
            }
            return true;
        });
        refreshRuntime();

        debugPreference = new Preference(requireContext());
        debugPreference.setTitle(R.string.proton4a_debug_title);
        debugPreference.setSummary(R.string.proton4a_debug_summary);
        debugPreference.setOnPreferenceClickListener(preference -> {
            writeDebugReport();
            return true;
        });
        screen.addPreference(debugPreference);
    }

    private void writeDebugReport() {
        debugPreference.setSummary(R.string.proton4a_debug_running);
        Context context = requireContext().getApplicationContext();
        new Thread(() -> {
            String result;
            try {
                result = getString(R.string.proton4a_debug_saved,
                        DebugReport.write(context, true).getAbsolutePath());
            } catch (IOException | SecurityException exception) {
                result = getString(R.string.proton4a_debug_failed, exception.getMessage());
            }
            String text = result;
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (isAdded()) debugPreference.setSummary(text);
            });
        }, "Proton4A-debug").start();
    }

    /** Build in Hub, then hand the signed APK to Android's installer. */
    private void buildProgram(Uri uri, String name) {
        if (buildingProgram) return;
        final Activity activity = requireActivity();
        final Context context = activity.getApplicationContext();
        final long size = displaySize(uri);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        TextView message = new TextView(activity);
        message.setText(getString(R.string.proton4a_build_copy, name, 0L));
        content.addView(message);
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(size <= 0);
        content.addView(bar);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.proton4a_build_title).setView(content)
                .setCancelable(false).create();
        dialog.show();
        buildingProgram = true;
        importPreference.setEnabled(false);

        new Thread(() -> {
            try {
                Context assets = context.createPackageContext("org.petalos.proton4a", 0);
                final long[] reported = {0};
                ProgramApk.Result result;
                try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IOException("Cannot open selected EXE");
                    result = ProgramApk.build(context, assets, input, name, (step, bytes) -> {
                        if ("copy".equals(step) && bytes - reported[0] < 1024 * 1024) return;
                        reported[0] = bytes;
                        activity.runOnUiThread(() -> {
                            if (!isAdded()) return;
                            if ("copy".equals(step)) {
                                message.setText(getString(R.string.proton4a_build_copy, name,
                                        bytes / (1024 * 1024)));
                                if (size > 0) bar.setProgress((int) Math.min(1000,
                                        bytes * 1000 / size));
                            } else {
                                bar.setIndeterminate(true);
                                message.setText("sign".equals(step)
                                        ? R.string.proton4a_build_sign
                                        : R.string.proton4a_build_package);
                            }
                        });
                    });
                }
                activity.runOnUiThread(() -> {
                    dialog.dismiss();
                    if (!isAdded()) return;
                    buildingProgram = false;
                    importPreference.setEnabled(true);
                    detailsPreference.setSummary(getString(R.string.proton4a_apk_ready, name));
                    installProgram(result.apk);
                });
            } catch (Exception exception) {
                android.util.Log.e("Proton4A", "Failed to package program", exception);
                Throwable cause = exception;
                while (cause.getCause() != null && cause.getCause() != cause) {
                    cause = cause.getCause();
                }
                String failure = cause.getMessage() != null
                        ? cause.getMessage() : exception.toString();
                activity.runOnUiThread(() -> {
                    dialog.dismiss();
                    if (!isAdded()) return;
                    buildingProgram = false;
                    importPreference.setEnabled(true);
                    detailsPreference.setSummary(getString(R.string.proton4a_import_failed,
                            failure));
                });
            }
        }, "Proton4A-build-apk").start();
    }

    private void installProgram(File apk) {
        Uri uri = Proton4AApkProvider.uriFor(apk);
        Intent request = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        request.setDataAndType(uri, "application/vnd.android.package-archive");
        request.setClipData(ClipData.newUri(requireContext().getContentResolver(),
                apk.getName(), uri));
        request.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        request.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        try {
            apkInstaller.launch(request);
        } catch (RuntimeException exception) {
            detailsPreference.setSummary(getString(R.string.proton4a_import_failed,
                    exception.getMessage()));
        }
    }

    private long displaySize(Uri uri) {
        try (Cursor cursor = requireContext().getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        } catch (RuntimeException ignored) {
            // The indeterminate progress bar works for providers without a file size.
        }
        return -1;
    }

    private void refreshRuntime() {
        RuntimeManager.Status status = RuntimeManager.status(requireContext());
        if (status.installed) {
            runtimePreference.setSummary(getString(R.string.proton4a_runtime_installed,
                    status.protonVersion, status.box64Revision));
        } else if (new File(RuntimeManager.STAGED_PAYLOAD).isFile()) {
            runtimePreference.setSummary(R.string.proton4a_runtime_install);
        } else {
            runtimePreference.setSummary(R.string.proton4a_runtime_pick);
        }
        runtimePreference.setEnabled(!installingRuntime);
    }

    private void installRuntime(Uri payload) {
        if (installingRuntime) return;
        installingRuntime = true;
        runtimePreference.setSummary(R.string.proton4a_runtime_installing);
        runtimePreference.setEnabled(false);
        Context context = requireContext().getApplicationContext();
        new Thread(() -> {
            RuntimeManager.Status result = null;
            String error = null;
            try {
                result = payload == null
                        ? RuntimeManager.installFromStaged(context)
                        : RuntimeManager.install(context, payload);
            } catch (IOException | SecurityException exception) {
                error = exception.getMessage();
            }
            RuntimeManager.Status installed = result;
            String failure = error;
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                installingRuntime = false;
                if (installed != null && installed.installed) {
                    refreshRuntime();
                } else {
                    runtimePreference.setSummary(
                            getString(R.string.proton4a_runtime_failed, failure));
                    runtimePreference.setEnabled(new File(RuntimeManager.STAGED_PAYLOAD).isFile());
                }
            });
        }, "Proton4A-runtime").start();
    }

    private String displayName(@NonNull Uri uri) {
        try (Cursor cursor = requireContext().getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String value = cursor.getString(0);
                if (value != null) return value;
            }
        } catch (RuntimeException ignored) {
            // Some document providers omit display-name metadata.
        }
        return uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
    }
}
