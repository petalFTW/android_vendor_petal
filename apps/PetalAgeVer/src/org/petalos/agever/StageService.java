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

package org.petalos.agever;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.petalos.agever.core.ControlMode;
import org.petalos.agever.core.ControlState;
import org.petalos.agever.output.FrameSink;
import org.petalos.agever.output.PreviewFrameSink;
import org.petalos.agever.output.ProviderFrameSink;
import org.petalos.agever.render.AvatarStage;
import org.petalos.agever.ui.JoystickOverlay;
import org.petalos.agever.ui.JoystickView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that owns the virtual-camera session: renderer, output
 * sink, optional face tracker and the floating joystick.
 */
public final class StageService extends Service {

    private static final String TAG = "AgeVerService";

    public static final String ACTION_START = "org.petalos.agever.action.START";
    public static final String ACTION_STOP = "org.petalos.agever.action.STOP";
    public static final String ACTION_TOGGLE = "org.petalos.agever.action.TOGGLE";
    public static final String ACTION_SYNC = "org.petalos.agever.action.SYNC";

    public static final String EXTRA_JOYSTICK = "joystick";

    private static final String CHANNEL_ID = "agever_stage";
    private static final int NOTIFICATION_ID = 4101;

    /** Render target ids: the in-app preview and the virtual-camera provider. */
    private static final String TARGET_PREVIEW = "preview";
    private static final String TARGET_PROVIDER = "provider";

    private static final AtomicBoolean sRunning = new AtomicBoolean(false);

    public interface StageObserver {
        void onRunningChanged(boolean running);

        void onStatus(@NonNull String status);
    }

    public final class LocalBinder extends Binder {
        public StageService service() {
            return StageService.this;
        }

        public void addObserver(StageObserver observer) {
            mObservers.addIfAbsent(observer);
        }

        public void removeObserver(StageObserver observer) {
            mObservers.remove(observer);
        }

        public boolean isRunning() {
            return sRunning.get();
        }
    }

    private final LocalBinder mBinder = new LocalBinder();
    private final CopyOnWriteArrayList<StageObserver> mObservers = new CopyOnWriteArrayList<>();
    private final ControlState mControl = new ControlState();
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private final JoystickView.Listener mJoystickListener = new JoystickView.Listener() {
        @Override
        public void onMove(float x, float y) {
            mControl.applyJoystick(x, y);
        }

        @Override
        public void onRelease() {
            // Hold the last pose so the avatar stays where the user left it.
        }
    };

    private final JoystickView.Listener mTurnListener = new JoystickView.Listener() {
        @Override
        public void onMove(float x, float y) {
            mControl.applyHeadYaw(x);
        }

        @Override
        public void onRelease() {
            // Hold the last angle.
        }
    };

    private AvatarStage mStage;
    private PreviewFrameSink mPreviewSink;
    private ProviderFrameSink mProviderSink;
    private boolean mProviderEverReady;
    private JoystickOverlay mJoystick;
    private SurfaceView mPreviewView;
    private String mStatus = "";
    private String mLoadedAvatar = "";

    private final Runnable mControlPump = new Runnable() {
        @Override
        public void run() {
            if (mStage != null && sRunning.get()) {
                mStage.setControl(mControl.snapshot());
                mMain.postDelayed(this, 33L);
            }
        }
    };

    public static boolean isRunning() {
        return sRunning.get();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        createChannel();
        mPreviewSink = new PreviewFrameSink();
        mProviderSink = new ProviderFrameSink(this);
        mJoystick = new JoystickOverlay(this);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_SYNC : intent.getAction();
        if (action == null) {
            action = ACTION_SYNC;
        }
        switch (action) {
            case ACTION_START:
                startSession(true);
                break;
            case ACTION_STOP:
                stopSession(true);
                break;
            case ACTION_TOGGLE:
                if (sRunning.get()) {
                    stopSession(true);
                } else {
                    startSession(true);
                }
                break;
            case ACTION_SYNC:
                if (Prefs.get().getEnabled() && !sRunning.get()) {
                    startSession(false);
                } else if (!Prefs.get().getEnabled() && sRunning.get()) {
                    stopSession(false);
                }
                break;
            default:
                break;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        // petalOS: swiping the app out of recents tears the session down, so no
        // foreground service, render loop or armed override lingers.
        stopSession(true);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        stopSession(false);
        mJoystick.hide();
        super.onDestroy();
    }

    // ------------------------------------------------------------- public API

    public void attachPreview(@Nullable SurfaceView view) {
        mPreviewView = view;
        mPreviewSink.attach(view);
    }

    public void detachPreview() {
        attachPreview(null);
    }

    public ControlMode mode() {
        return Prefs.get().getMode();
    }

    /** Re-applies mirror/idle/mode/avatar changes without dropping the session. */
    public void onConfigChanged() {
        if (!sRunning.get()) {
            return;
        }
        if (mStage != null) {
            mStage.setMirror(Prefs.get().getMirror());
            mStage.setIdleMotion(Prefs.get().getIdleMotion());
        }
        ControlMode mode = Prefs.get().getMode();
        if (mode == ControlMode.JOYSTICK && Prefs.get().getJoystickOverlay()) {
            mJoystick.show(mJoystickListener, mTurnListener);
        } else {
            mJoystick.hide();
        }
        String ref = Prefs.get().getAvatar();
        if (!ref.equals(mLoadedAvatar)) {
            loadConfiguredAvatar();
        }
    }

    // ------------------------------------------------------------- session

    private void startSession(boolean persist) {
        if (sRunning.get()) {
            return;
        }
        if (persist) {
            Prefs.get().setEnabled(true);
        }
        sRunning.set(true);
        startAsForeground();
        notifyRunning(true);
        setStatus(getString(R.string.status_running));
        // The override arms on the register thread; refresh the tile once it has.
        mMain.postDelayed(() -> StageTile.requestRefresh(this), 1200L);

        mStage = new AvatarStage();
        mStage.setMirror(Prefs.get().getMirror());
        mStage.setIdleMotion(Prefs.get().getIdleMotion());
        mStage.start(new AvatarStage.Listener() {
            @Override
            public void onStageReady() {
                loadConfiguredAvatar();
            }

            @Override
            public void onStageError(@NonNull String message) {
                Log.e(TAG, message);
                setStatus(message);
            }
        });
        mStage.setControl(mControl.snapshot());

        chooseSink();
        startControlMode();
        mMain.removeCallbacks(mControlPump);
        mMain.post(mControlPump);
    }

    private void stopSession(boolean persist) {
        if (persist) {
            Prefs.get().setEnabled(false);
        }
        if (!sRunning.getAndSet(false)) {
            if (mStage != null) {
                mStage.destroy();
                mStage = null;
            }
            mProviderSink.stop();
            mJoystick.hide();
            StageTile.requestRefresh(this);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        mMain.removeCallbacks(mControlPump);
        mProviderSink.stop();
        mPreviewSink.stop();
        mJoystick.hide();
        if (mStage != null) {
            mStage.destroy();
            mStage = null;
        }
        notifyRunning(false);
        setStatus(getString(R.string.status_stopped));
        StageTile.requestRefresh(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void chooseSink() {
        // The in-app preview is always live so the user can see the avatar.
        mPreviewSink.attach(mPreviewView);
        mPreviewSink.start(new FrameSink.Callback() {
            @Override
            public void onSurfaceReady(@NonNull android.view.Surface surface, int width, int height) {
                if (mStage != null) {
                    mStage.setSurface(TARGET_PREVIEW, surface, width, height);
                }
            }

            @Override
            public void onSurfaceLost() {
                if (mStage != null) {
                    mStage.clearSurface(TARGET_PREVIEW);
                }
            }
        });

        // Additionally publish the avatar as a real front camera when the
        // petalOS virtual-camera provider is installed and permitted.
        if (!ProviderFrameSink.isAvailable(this)) {
            setStatus(getString(R.string.status_injection_missing));
            return;
        }
        mProviderEverReady = false;
        mProviderSink.start(new FrameSink.Callback() {
            @Override
            public void onSurfaceReady(@NonNull android.view.Surface surface, int width, int height) {
                mProviderEverReady = true;
                if (mStage != null) {
                    mStage.setSurface(TARGET_PROVIDER, surface, width, height);
                }
            }

            @Override
            public void onSurfaceLost() {
                if (!mProviderEverReady) {
                    Log.w(TAG, "camera provider unavailable, preview only");
                }
                if (mStage != null) {
                    mStage.clearSurface(TARGET_PROVIDER);
                }
            }
        });
        setStatus(getString(R.string.status_injection_ready));
    }

    private void startControlMode() {
        if (Prefs.get().getMode() == ControlMode.JOYSTICK
                && Prefs.get().getJoystickOverlay()) {
            mJoystick.show(mJoystickListener, mTurnListener);
        }
    }

    // ------------------------------------------------------------- avatar

    private void loadConfiguredAvatar() {
        final String ref = Prefs.get().getAvatar();
        mLoadedAvatar = ref;
        new Thread(() -> {
            try {
                byte[] bytes = readAvatar(ref);
                if (bytes == null) {
                    setStatus(getString(R.string.status_import_failed));
                    return;
                }
                mMain.post(() -> {
                    if (mStage != null) {
                        mStage.setAvatar(bytes, ref);
                    }
                });
            } catch (IOException e) {
                Log.e(TAG, "cannot read avatar " + ref, e);
                setStatus(getString(R.string.status_import_failed));
            }
        }, "agever-avatar-load").start();
    }

    private byte[] readAvatar(String ref) throws IOException {
        if (ref == null || ref.isEmpty()) {
            ref = Prefs.DEFAULT_AVATAR;
        }
        if (ref.startsWith("file:")) {
            ref = ref.substring("file:".length());
        }
        if (ref.startsWith("/")) {
            try (InputStream in = new FileInputStream(new File(ref))) {
                return readAll(in);
            }
        }
        try (InputStream in = getAssets().open(ref)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int read;
        while ((read = in.read(buf)) != -1) {
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------- plumbing

    private void startAsForeground() {
        Notification notification = buildNotification();
        startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }

    private Notification buildNotification() {
        Intent stop = new Intent(this, StageService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 0, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_agever)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(0, getString(R.string.notification_stop), stopPi)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(channel);
    }

    private void setStatus(String status) {
        mStatus = status;
        mMain.post(() -> {
            for (StageObserver observer : mObservers) {
                observer.onStatus(status);
            }
        });
    }

    private void notifyRunning(boolean running) {
        mMain.post(() -> {
            for (StageObserver observer : mObservers) {
                observer.onRunningChanged(running);
            }
        });
    }

    public String status() {
        return mStatus;
    }
}
