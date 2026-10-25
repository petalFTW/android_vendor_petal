/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.petalos.hub;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Map;

// Hub has the install permissions, no shell command nonsense
final class AppPackageInstaller {

    static final String ACTION_STATUS = "org.petalos.hub.action.APP_INSTALL_STATUS";
    static final String EXTRA_STATUS = PackageInstaller.EXTRA_STATUS;
    static final String EXTRA_MESSAGE = PackageInstaller.EXTRA_STATUS_MESSAGE;

    interface Callback {
        void onFinished(boolean success, String message);
    }

    private final Context mContext;
    private final Callback mCallback;
    private final String mAction;

    AppPackageInstaller(Context context, Callback callback) {
        this(context, callback, ACTION_STATUS);
    }

    AppPackageInstaller(Context context, Callback callback, String action) {
        mContext = context.getApplicationContext();
        mCallback = callback;
        mAction = action;
    }

    BroadcastReceiver receiver() {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int status = intent.getIntExtra(EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (handlePendingAction(context, intent, status)) return;
                mCallback.onFinished(status == PackageInstaller.STATUS_SUCCESS,
                        resultMessage(intent, status));
            }
        };
    }

    void install(File apk) {
        new Thread(() -> installInternal(apk), "PetalAppCenterInstall").start();
    }

    private void installInternal(File apk) {
        PackageInstaller installer = mContext.getPackageManager().getPackageInstaller();
        int id = -1;
        try {
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setInstallerPackageName(mContext.getPackageName());
            id = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(id);
            try {
                try (InputStream input = new FileInputStream(apk);
                        OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                    byte[] buffer = new byte[1 << 16];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                    session.fsync(output);
                }
                session.commit(statusIntent(id).getIntentSender());
            } finally {
                session.close();
            }
        } catch (Exception exception) {
            if (id >= 0) {
                try { installer.abandonSession(id); } catch (RuntimeException ignored) { }
            }
            mContext.getMainExecutor().execute(() ->
                    mCallback.onFinished(false, exception.toString()));
        }
    }

    void uninstall(String packageName) {
        try {
            PackageInstaller installer = mContext.getPackageManager().getPackageInstaller();
            installer.uninstall(packageName, statusIntent(packageName.hashCode()).getIntentSender());
        } catch (Exception exception) {
            mCallback.onFinished(false, exception.toString());
        }
    }

    // both packages commit together; dropping a dynamite split makes gms shit itself
    void installPackages(Map<String, File[]> packages) {
        new Thread(() -> installPackagesInternal(packages), "PetalServicesInstall").start();
    }

    private void installPackagesInternal(Map<String, File[]> packages) {
        PackageInstaller installer = mContext.getPackageManager().getPackageInstaller();
        ArrayList<Integer> sessions = new ArrayList<>();
        try {
            PackageInstaller.SessionParams parentParams = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            parentParams.setMultiPackage();
            int parentId = installer.createSession(parentParams);
            sessions.add(parentId);
            try (PackageInstaller.Session parent = installer.openSession(parentId)) {
                for (Map.Entry<String, File[]> entry : packages.entrySet()) {
                    PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    params.setAppPackageName(entry.getKey());
                    params.setInstallerPackageName(mContext.getPackageName());
                    int id = installer.createSession(params);
                    sessions.add(id);
                    try (PackageInstaller.Session child = installer.openSession(id)) {
                        for (File apk : entry.getValue()) {
                            try (InputStream input = new FileInputStream(apk);
                                    OutputStream output = child.openWrite(apk.getName(), 0,
                                            apk.length())) {
                                byte[] buffer = new byte[1 << 16];
                                int read;
                                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                                child.fsync(output);
                            }
                        }
                    }
                    parent.addChildSessionId(id);
                }
                if (packages.containsKey("com.google.android.gms")) {
                    // wake the shim before the freshly installed Google process starts
                    mContext.getPackageManager().setApplicationEnabledSetting(
                            "app.grapheneos.gmscompat", PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0);
                }
                parent.commit(statusIntent(parentId).getIntentSender());
            }
        } catch (Exception e) {
            for (int id : sessions) {
                try { installer.abandonSession(id); } catch (RuntimeException ignored) { }
            }
            mContext.getMainExecutor().execute(() -> mCallback.onFinished(false, e.toString()));
        }
    }

    private PendingIntent statusIntent(int requestCode) {
        Intent intent = new Intent(mAction).setPackage(mContext.getPackageName());
        return PendingIntent.getBroadcast(mContext, requestCode, intent,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static boolean handlePendingAction(Context context, Intent intent, int status) {
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return false;
        Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        if (confirmation == null) return false;
        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(confirmation);
        return true;
    }

    static String resultMessage(Intent intent, int status) {
        String message = intent.getStringExtra(EXTRA_MESSAGE);
        return message == null || message.isBlank() ? "PackageInstaller status " + status : message;
    }
}
