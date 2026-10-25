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

package org.petalos.petaltuner;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import org.petalos.petaltuner.core.Prefs;
import org.petalos.petaltuner.core.Profile;
import org.petalos.petaltuner.core.ProfileManager;
import org.petalos.petaltuner.core.TunerEngine;

public final class OverlayService extends Service {

    public static final String ACTION_START = "org.petalos.petaltuner.action.START";
    public static final String ACTION_STOP = "org.petalos.petaltuner.action.STOP";
    public static final String ACTION_TOGGLE_BYPASS = "org.petalos.petaltuner.action.TOGGLE_BYPASS";
    public static final String ACTION_REFRESH = "org.petalos.petaltuner.action.REFRESH";

    private static final int NOTIFICATION_ID = 4210;
    private static final long POLL_MS = 2000;
    private static final long POPUP_MS = 4000;

    private TunerEngine mEngine;
    private WindowManager mWindowManager;
    private View mOverlay;
    private WindowManager.LayoutParams mOverlayParams;
    private TextView mStateView;
    private TextView mProfileChip;
    private TextView mBypassChip;
    private String mShownGame;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private HandlerThread mWorkerThread;
    private Handler mWorker;
    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            try {
                mEngine.onForegroundChanged();
                mEngine.enforceCharging();
            } catch (RuntimeException e) {
                android.util.Log.e("PetalTuner/Overlay", "poll failed", e);
            }
            mHandler.post(() -> {
                showGamePopupIfNeeded();
                updateState();
            });
            mWorker.postDelayed(this, POLL_MS);
        }
    };
    private final Runnable mHidePopup = this::removeOverlay;

    private boolean mReceiverRegistered;

    private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mWorker != null) {
                mWorker.post(mEngine::onPowerChanged);
            }
            updateState();
        }
    };

    public static void start(Context context) {
        Intent intent = new Intent(context, OverlayService.class).setAction(ACTION_START);
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException e) {
            android.util.Log.w("PetalTuner/Overlay", "service start blocked: " + e.getMessage());
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, OverlayService.class).setAction(ACTION_STOP);
        context.stopService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mEngine = TunerEngine.get(this);
        mWorkerThread = new HandlerThread("petal-gm-worker");
        mWorkerThread.start();
        mWorker = new Handler(mWorkerThread.getLooper());
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE_BYPASS.equals(action)) {
            boolean next = !mEngine.charging().isBypassActive();
            mEngine.charging().setBypass(next);
            mEngine.prefs().putBool(Prefs.BYPASS_ENABLED, next);
            mEngine.prefs().putBool(Prefs.GM_BYPASS_BY_GM, false);
        }
        mEngine.gm().requestGrants();
        registerBatteryReceiver();
        mWorker.removeCallbacks(mPoll);
        mWorker.post(() -> {
            if (mEngine.prefs().getBool(Prefs.FOCUS_ENABLED, false)) {
                mEngine.setFocusEnabled(true);
            }
        });
        mWorker.postDelayed(mPoll, 500);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        if (mWorker != null) {
            mWorker.removeCallbacksAndMessages(null);
            mWorker.post(mEngine::shutdownSession);
            mWorkerThread.quitSafely();
        }
        if (mReceiverRegistered) {
            try {
                unregisterReceiver(mBatteryReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            mReceiverRegistered = false;
        }
        removeOverlay();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerBatteryReceiver() {
        if (mReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(mBatteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        mReceiverRegistered = true;
    }

    private Notification buildNotification() {
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, TunerActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, TunerApp.CHANNEL_ID)
                .setContentTitle(getString(R.string.overlay_notification_title))
                .setContentText(getString(R.string.overlay_notification_text))
                .setSmallIcon(R.drawable.ic_tuner)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
    }

    private void showGamePopupIfNeeded() {
        if (!mEngine.gm().overlayEnabled()) {
            mShownGame = null;
            mHandler.removeCallbacks(mHidePopup);
            removeOverlay();
            return;
        }
        String game = mEngine.foreground().getForegroundGame();
        if (game == null) {
            mShownGame = null;
            mHandler.removeCallbacks(mHidePopup);
            removeOverlay();
            return;
        }
        if (game.equals(mShownGame)) return;
        if (!Settings.canDrawOverlays(this)) {
            return;
        }
        mHandler.removeCallbacks(mHidePopup);
        removeOverlay();
        mWindowManager = getSystemService(WindowManager.class);
        if (mWindowManager == null) {
            return;
        }
        mOverlay = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null);
        mOverlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mOverlayParams.gravity = Gravity.TOP | Gravity.START;
        mOverlayParams.x = mEngine.prefs().getInt(Prefs.OVERLAY_X, 24);
        mOverlayParams.y = mEngine.prefs().getInt(Prefs.OVERLAY_Y, 220);

        mStateView = mOverlay.findViewById(R.id.overlay_state);
        mProfileChip = mOverlay.findViewById(R.id.overlay_profile);
        mBypassChip = mOverlay.findViewById(R.id.overlay_bypass);
        TextView boostChip = mOverlay.findViewById(R.id.overlay_boost);
        TextView killChip = mOverlay.findViewById(R.id.overlay_kill);
        TextView openChip = mOverlay.findViewById(R.id.overlay_recents);
        TextView closeChip = mOverlay.findViewById(R.id.overlay_close);
        View header = mOverlay.findViewById(R.id.overlay_header);

        mProfileChip.setOnClickListener(v -> {
            String[] keys = ProfileManager.keys();
            String active = mEngine.getActiveProfile();
            int index = 0;
            for (int i = 0; i < keys.length; i++) {
                if (keys[i].equals(active)) {
                    index = i;
                    break;
                }
            }
            String next = keys[(index + 1) % keys.length];
            mEngine.setActiveProfile(next);
            updateState();
            mWorker.post(() -> mEngine.applyProfile(next));
        });
        mBypassChip.setOnClickListener(v -> {
            boolean next = !mEngine.charging().isBypassActive();
            mEngine.prefs().putBool(Prefs.BYPASS_ENABLED, next);
            mEngine.prefs().putBool(Prefs.GM_BYPASS_BY_GM, false);
            mWorker.post(() -> {
                mEngine.charging().setBypass(next);
                mHandler.post(this::updateState);
            });
            toast(getString(R.string.bypass_title) + ": " + (next ? getString(R.string.value_on)
                    : getString(R.string.value_off)));
            updateState();
        });
        boostChip.setOnClickListener(v -> {
            mWorker.post(mEngine::applyActiveProfile);
            toast(getString(R.string.toast_applied, mEngine.getActiveProfile()));
        });
        killChip.setOnClickListener(v -> {
            mWorker.post(() -> mEngine.memory().killBackgroundProcesses());
            toast(getString(R.string.memory_kill_bg_title));
        });
        openChip.setOnClickListener(v -> {
            Intent open = new Intent(this, TunerActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(open);
        });
        closeChip.setOnClickListener(v -> {
            mHandler.removeCallbacks(mHidePopup);
            removeOverlay();
        });

        header.setOnTouchListener(new DragTouchListener());
        mWindowManager.addView(mOverlay, mOverlayParams);
        mShownGame = game;
        mHandler.postDelayed(mHidePopup, POPUP_MS);
        updateState();
    }

    private void updateState() {
        if (mOverlay == null) {
            return;
        }
        Profile profile = ProfileManager.get(mEngine.getActiveProfile());
        int level = mEngine.charging().getLevel();
        String state = profile.label;
        if (level >= 0) {
            state += " · " + level + "%";
        }
        mStateView.setText(state);
        mProfileChip.setText(profile.label);
        boolean bypass = mEngine.charging().isBypassActive();
        int color = getColor(bypass ? R.color.tuner_accent_teal : R.color.tuner_text_secondary);
        mBypassChip.setText(bypass ? getString(R.string.value_on) : getString(R.string.value_off));
        mBypassChip.setTextColor(color);
    }

    private void removeOverlay() {
        if (mOverlay != null && mWindowManager != null) {
            try {
                mWindowManager.removeView(mOverlay);
            } catch (IllegalArgumentException ignored) {
            }
        }
        mOverlay = null;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private final class DragTouchListener implements View.OnTouchListener {
        private int mInitialX;
        private int mInitialY;
        private float mTouchX;
        private float mTouchY;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mInitialX = mOverlayParams.x;
                    mInitialY = mOverlayParams.y;
                    mTouchX = event.getRawX();
                    mTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    mOverlayParams.x = mInitialX + (int) (event.getRawX() - mTouchX);
                    mOverlayParams.y = mInitialY + (int) (event.getRawY() - mTouchY);
                    if (mWindowManager != null && mOverlay != null) {
                        mWindowManager.updateViewLayout(mOverlay, mOverlayParams);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    mEngine.prefs().putInt(Prefs.OVERLAY_X, mOverlayParams.x);
                    mEngine.prefs().putInt(Prefs.OVERLAY_Y, mOverlayParams.y);
                    return true;
                default:
                    return false;
            }
        }
    }
}
