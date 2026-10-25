/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.agever;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Surface;
import android.view.SurfaceView;
import androidx.core.app.NotificationCompat;
import org.petalos.agever.core.ControlMode;
import org.petalos.agever.core.ControlState;
import org.petalos.agever.output.FrameSink;
import org.petalos.agever.output.PreviewFrameSink;
import org.petalos.agever.output.ProviderFrameSink;
import org.petalos.agever.render.AvatarStage;
import org.petalos.agever.ui.JoystickOverlay;
import org.petalos.agever.ui.JoystickView;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class StageService extends Service {
    public static final String ACTION_START = "org.petalos.agever.action.START";
    public static final String ACTION_PREVIEW = "org.petalos.agever.action.PREVIEW";
    public static final String ACTION_STOP = "org.petalos.agever.action.STOP";
    public static final String ACTION_SYNC = "org.petalos.agever.action.SYNC";
    private static final String CHANNEL = "agever_stage";
    private static final int NOTIFICATION = 4101;
    private static volatile boolean sRunning;
    private static volatile boolean sCameraRequested;
    private static volatile SessionState sState = SessionState.OFF;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private static final ExecutorService sIo = Executors.newSingleThreadExecutor();
    private final ExecutorService mIo = sIo;
    private final CopyOnWriteArrayList<StageObserver> mObservers = new CopyOnWriteArrayList<>();
    private final ControlState mControl = new ControlState();
    private PreviewFrameSink mPreview;
    private ProviderFrameSink mProvider;
    private JoystickOverlay mPanel;
    private AvatarStage mStage;
    private SurfaceView mPreviewView;
    private Future<?> mLoad;
    private int mGeneration;
    private int mLoadGeneration;
    private int mCameraState = -1;
    private boolean mDestroyed;
    private boolean mForeground;
    private boolean mNotifiedCamera;
    private boolean mModelReady;
    private boolean mEngineReady;
    private boolean mLoading;
    private boolean mAvatarError;
    private int mExpressionMask;
    private boolean mProviderHealthy;
    private String mLoadedAvatar = "";
    private String mRequestedAvatar = "";
    private String mDetail = "";

    public interface StageObserver {
        void onSessionChanged(SessionState state, String detail, boolean cameraRequested);
    }
    public final class LocalBinder extends Binder {
        public StageService service() { return StageService.this; }
        public void addObserver(StageObserver observer) {
            mObservers.addIfAbsent(observer);
            observer.onSessionChanged(sState, mDetail, sCameraRequested);
        }
        public void removeObserver(StageObserver observer) { mObservers.remove(observer); }
    }
    private final LocalBinder mBinder = new LocalBinder();
    public static boolean isRunning() { return sRunning; }
    public static boolean isCameraRequested() { return sCameraRequested; }
    public static SessionState state() { return sState; }

    private JoystickView.Listener axis(int axis) {
        return new JoystickView.Listener() {
            @Override public void onMove(float x, float y) {
                if (axis == 0) mControl.applyJoystick(x, y);
                else if (axis == 1) mControl.applyHeadYaw(x);
                else mControl.applyDepth(y);
            }
            @Override public void onRelease() { }
        };
    }

    @Override public void onCreate() {
        super.onCreate();
        sState = SessionState.OFF;
        sRunning = false;
        sCameraRequested = false;
        Prefs.init(this);
        mDetail = getString(R.string.detail_off);
        mControl.setExpressionSpeed(Prefs.get().getExpressionSpeed());
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, getString(R.string.notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW));
        mPreview = new PreviewFrameSink();
        mPanel = new JoystickOverlay(this);
        refreshAuthority();
    }
    @Override public IBinder onBind(Intent intent) { return mBinder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_SYNC : intent.getAction();
        try {
            if (ACTION_STOP.equals(action)) stopSession(true);
            else if (ACTION_PREVIEW.equals(action)) startSession(false);
            else if (ACTION_START.equals(action)) startSession(true);
            else if (ACTION_SYNC.equals(action) && Prefs.get().getEnabled()
                    && Prefs.get().getRestoreOnBoot()) startSession(true);
        } catch (RuntimeException e) {
            Prefs.get().setEnabled(false);
            sCameraRequested = false;
            change(SessionState.ERROR, getString(R.string.detail_action_failed, e.getMessage()));
            if (mForeground) stopForeground(STOP_FOREGROUND_REMOVE);
            mForeground = false;
        }
        return START_NOT_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        stopSession(true);
        super.onTaskRemoved(rootIntent);
    }
    @Override public void onDestroy() {
        mDestroyed = true;
        // an explicitly opted-in reboot restore keeps only the user's intent
        boolean restore = Prefs.get().getRestoreOnBoot() && Prefs.get().getEnabled();
        stopSession(false);
        if (!restore) Prefs.get().setEnabled(false);
        sState = SessionState.OFF;
        super.onDestroy();
    }

    public void attachPreview(SurfaceView view) {
        mPreviewView = view;
        mPreview.attach(view);
    }
    public void detachPreview() {
        attachPreview(null);
        if (!sCameraRequested && mStage != null) stopSession(false);
    }
    public void onConfigChanged() {
        if (mStage == null) return;
        mStage.setMirror(Prefs.get().getMirror());
        mStage.setIdleMotion(Prefs.get().getIdleMotion());
        mStage.setFrameRate(Prefs.get().getFrameRate());
        updatePanel();
        if (mEngineReady && !Prefs.get().getAvatar().equals(mRequestedAvatar)) loadAvatar();
    }
    public boolean selectedAvatarReady() {
        return mModelReady && mLoadedAvatar.equals(Prefs.get().getAvatar());
    }

    private void startSession(boolean camera) {
        if (sState == SessionState.STOPPING) return;
        if (camera) {
            sCameraRequested = true;
            startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            mForeground = true;
            Prefs.get().setEnabled(true);
        }
        if (mStage != null) {
            change(mModelReady ? SessionState.PREVIEW_READY : SessionState.STARTING,
                    getString(mModelReady ? R.string.detail_preview : R.string.detail_loading));
            if (mEngineReady && !mModelReady && !mLoading) loadAvatar();
            else if (camera && mModelReady) startProvider();
            else if (mProvider != null) refreshAuthority();
            return;
        }
        sRunning = true;
        mModelReady = false;
        mEngineReady = false;
        final int generation = ++mGeneration;
        change(SessionState.STARTING, getString(R.string.detail_loading));
        final AvatarStage stage = new AvatarStage();
        mStage = stage;
        stage.setMirror(Prefs.get().getMirror());
        stage.setIdleMotion(Prefs.get().getIdleMotion());
        stage.setFrameRate(Prefs.get().getFrameRate());
        stage.setControlSource(mControl::snapshot);
        stage.start(new AvatarStage.Listener() {
            @Override public void onStageReady() {
                if (current(generation, stage)) {
                    mEngineReady = true;
                    loadAvatar();
                }
            }
            @Override public void onAvatarReady(String ref, int expressions) {
                if (!current(generation, stage) || !ref.equals(mRequestedAvatar)) return;
                mLoadedAvatar = ref;
                Prefs.get().setLastGoodAvatar(ref);
                mModelReady = true;
                mLoading = false;
                mAvatarError = false;
                mExpressionMask = expressions;
                change(mProvider == null ? SessionState.PREVIEW_READY : SessionState.CAMERA_STARTING,
                        getString(mProvider == null ? R.string.detail_preview : R.string.detail_connecting));
                if (sCameraRequested) startProvider();
                updatePanel();
                refreshAuthority();
            }
            @Override public void onAvatarError(String message) {
                if (!current(generation, stage)) return;
                mLoading = false;
                mAvatarError = true;
                mModelReady = !mLoadedAvatar.isEmpty();
                String fallback = mModelReady ? mLoadedAvatar : Prefs.get().getLastGoodAvatar();
                Prefs.get().setAvatar(fallback);
                mRequestedAvatar = fallback;
                change(SessionState.ERROR, message);
                if (mProvider == null && mForeground) {
                    Prefs.get().setEnabled(false);
                    sCameraRequested = false;
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    mForeground = false;
                }
            }
            @Override public void onStageError(String message) {
                if (!current(generation, stage)) return;
                mModelReady = false;
                mEngineReady = false;
                mLoading = false;
                stage.destroy();
                mStage = null;
                sRunning = false;
                change(SessionState.ERROR, message);
                if (mProvider == null && mForeground) {
                    Prefs.get().setEnabled(false);
                    sCameraRequested = false;
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    mForeground = false;
                }
            }
        });
        mPreview.start(new FrameSink.Callback() {
            @Override public void onSurfaceReady(Surface surface, int width, int height) {
                if (current(generation, stage)) stage.setSurface("preview", surface, width, height);
            }
            @Override public void onSurfaceLost() {
                if (current(generation, stage)) stage.clearSurface("preview");
            }
        });
        mPreview.attach(mPreviewView);
    }

    private boolean current(int generation, AvatarStage stage) {
        return !mDestroyed && mGeneration == generation && mStage == stage;
    }

    private void loadAvatar() {
        if (mLoad != null) mLoad.cancel(true);
        final int request = ++mLoadGeneration;
        final int generation = mGeneration;
        final AvatarStage stage = mStage;
        final String ref = Prefs.get().getAvatar();
        mRequestedAvatar = ref;
        mModelReady = false;
        mLoading = true;
        mAvatarError = false;
        mPanel.hide();
        change(SessionState.STARTING, getString(R.string.detail_loading));
        mLoad = mIo.submit(() -> {
            try {
                byte[] bytes = AvatarStore.read(this, ref);
                mMain.post(() -> {
                    if (current(generation, stage) && request == mLoadGeneration) stage.setAvatar(bytes, ref);
                });
            } catch (Exception | OutOfMemoryError e) {
                mMain.post(() -> {
                    if (!current(generation, stage) || request != mLoadGeneration) return;
                    mLoading = false;
                    mAvatarError = true;
                    mModelReady = !mLoadedAvatar.isEmpty();
                    String fallback = mModelReady ? mLoadedAvatar : Prefs.get().getLastGoodAvatar();
                    Prefs.get().setAvatar(fallback);
                    mRequestedAvatar = fallback;
                    change(SessionState.ERROR, getString(R.string.detail_avatar_failed, e.getMessage()));
                    if (mProvider == null && mForeground) {
                        sCameraRequested = false;
                        Prefs.get().setEnabled(false);
                        stopForeground(STOP_FOREGROUND_REMOVE);
                        mForeground = false;
                    }
                });
            }
        });
    }

    private void startProvider() {
        if (mProvider != null || !mModelReady) return;
        final int generation = mGeneration;
        final AvatarStage stage = mStage;
        final ProviderFrameSink provider = new ProviderFrameSink(this);
        mProvider = provider;
        mProviderHealthy = false;
        change(SessionState.CAMERA_STARTING, getString(R.string.detail_connecting));
        provider.setStatusListener(new ProviderFrameSink.StatusListener() {
            @Override public void onReady(boolean replacement) {
                if (!current(generation, stage) || mProvider != provider) return;
                mProviderHealthy = replacement;
                if (!replacement) change(SessionState.ERROR, getString(R.string.detail_extra_camera));
                else refreshAuthority();
                updatePanel();
            }
            @Override public void onFailure(String reason) {
                if (!current(generation, stage) || mProvider != provider) return;
                mProviderHealthy = false;
                change(SessionState.ERROR, reason);
                refreshAuthority();
            }
        });
        provider.start(new FrameSink.Callback() {
            @Override public void onSurfaceReady(Surface surface, int width, int height) {
                if (current(generation, stage) && mProvider == provider) {
                    stage.setSurface("provider", surface, width, height);
                }
            }
            @Override public void onSurfaceLost() {
                if (current(generation, stage)) stage.clearSurface("provider");
            }
        });
    }

    private void updatePanel() {
        if (sCameraRequested && mModelReady && Prefs.get().getMode() == ControlMode.JOYSTICK
                && Prefs.get().getJoystickOverlay() && Settings.canDrawOverlays(this)) {
            mPanel.show(axis(0), axis(1), axis(2), mControl::setExpression, value -> {
                mControl.setExpressionSpeed(value);
                Prefs.get().setExpressionSpeed(value);
            }, mControl.getExpressionSpeed());
            mPanel.setExpressionMask(mExpressionMask);
        } else mPanel.hide();
    }

    private void stopSession(boolean explicit) {
        if (explicit) Prefs.get().setEnabled(false);
        ++mGeneration;
        ++mLoadGeneration;
        if (mLoad != null) mLoad.cancel(true);
        mMain.removeCallbacks(mPoll);
        boolean restoreCamera = sCameraRequested || explicit;
        sCameraRequested = false;
        sRunning = false;
        change(SessionState.STOPPING, getString(R.string.detail_stopping));
        mPanel.hide();
        mPreview.stop();
        if (mStage != null) mStage.destroy();
        mStage = null;
        mModelReady = false;
        mEngineReady = false;
        mLoading = false;
        mAvatarError = false;
        mLoadedAvatar = "";
        mRequestedAvatar = "";
        ProviderFrameSink provider = mProvider;
        mProvider = null;
        mProviderHealthy = false;
        final int generation = mGeneration;
        mIo.submit(() -> {
            if (provider != null) provider.stop();
            if (restoreCamera) ProviderFrameSink.restoreCamera();
            int actual = ProviderFrameSink.queryOverrideState();
            mMain.post(() -> {
                if (mDestroyed || generation != mGeneration) return;
                mCameraState = actual;
                change(actual == 0 ? SessionState.OFF : SessionState.ERROR,
                        getString(actual == 0 ? R.string.detail_off : R.string.detail_cleanup_failed));
                if (actual == 0 && mForeground) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    mForeground = false;
                }
                if (actual == 0) stopSelf();
            });
        });
    }

    private final Runnable mPoll = this::refreshAuthority;
    private void refreshAuthority() {
        final int generation = mGeneration;
        mIo.submit(() -> {
            int actual = ProviderFrameSink.queryOverrideState();
            mMain.post(() -> {
                if (mDestroyed || generation != mGeneration) return;
                mCameraState = actual;
                if (actual == 1) change(SessionState.CAMERA_BLOCKED, getString(R.string.detail_blocked));
                else if (actual == 2 && mProvider != null && mModelReady && mProviderHealthy && !mAvatarError) {
                    change(SessionState.CAMERA_READY, getString(R.string.detail_camera));
                } else if (actual == 2 && mProvider == null) {
                    change(SessionState.ERROR, getString(R.string.detail_existing_override));
                } else if (sCameraRequested && sState == SessionState.CAMERA_READY) {
                    change(SessionState.ERROR, getString(R.string.detail_camera_lost));
                }
                mMain.removeCallbacks(mPoll);
                if (sCameraRequested || (actual > 0 && !mObservers.isEmpty())) {
                    mMain.postDelayed(mPoll, 2000);
                }
            });
        });
    }

    private void change(SessionState state, String detail) {
        if (sState == state && java.util.Objects.equals(mDetail, detail)
                && mNotifiedCamera == sCameraRequested) return;
        sState = state;
        mDetail = detail;
        mNotifiedCamera = sCameraRequested;
        for (StageObserver observer : mObservers) observer.onSessionChanged(state, detail, sCameraRequested);
        if (mForeground) getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
        StageTile.requestRefresh(this);
    }
    private Notification notification() {
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, StageService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_agever)
                .setContentTitle(getString(sState.label)).setContentText(mDetail)
                .setOngoing(true).setContentIntent(open)
                .addAction(0, getString(R.string.notification_stop), stop)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build();
    }
}
