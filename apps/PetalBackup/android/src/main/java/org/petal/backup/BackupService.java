/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petal.backup;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import org.petal.backup.core.BackupResult;
import org.petal.backup.core.OperationJournal;

public final class BackupService extends Service {
    static final String EXTRA_JOB = "job";
    private static final String CANCEL = "org.petal.backup.CANCEL";
    private static final String CHANNEL = "backup_operations";
    private static final int NOTIFICATION = 41;
    private boolean foreground;

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        BackupJobs jobs = BackupJobs.get(this);
        if (intent != null && CANCEL.equals(intent.getAction())) {
            jobs.cancel();
            if (!jobs.busy()) finishJob();
        } else if (intent != null) {
            jobs.start(this, intent.getStringExtra(EXTRA_JOB));
        } else {
            finishJob();
        }
        return START_NOT_STICKY;
    }

    void showProgress(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getService(this, 1,
                new Intent(this, BackupService.class).setAction(CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_backup).setContentTitle(getString(R.string.app_name))
                .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.cancel), cancel).build())
                .build();
        if (!foreground) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            foreground = true;
        } else if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION, notification);
        }
    }

    void finishJob() {
        if (BackupJobs.get(this).busy()) return;
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        stopSelf();
    }

    void finishJob(BackupResult result) {
        if (BackupJobs.get(this).busy()) return;
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                && getSharedPreferences("ui", MODE_PRIVATE).getBoolean("completion_alerts", true)) {
            PendingIntent open = PendingIntent.getActivity(this, result.id().hashCode(),
                    new Intent(this, MainActivity.class).putExtra("result_id", result.id())
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            getSystemService(NotificationManager.class).notify(42,
                    new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_backup)
                            .setContentTitle(getString(result.state() == OperationJournal.State.COMPLETE
                                    ? R.string.operation_complete : result.state() == OperationJournal.State.CANCELLED
                                    ? R.string.cancelled_title : R.string.needs_attention))
                            .setContentText(result.name()).setContentIntent(open).setAutoCancel(true).build());
        }
        stopSelf();
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) {
        BackupJobs.get(this).cancel();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (BackupJobs.get(this).busy()) BackupJobs.get(this).cancel();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
