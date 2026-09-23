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

package org.petalos.cam;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.ImageCapture;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.petalos.cam.capture.ProControls;
import org.petalos.cam.settings.SettingsActivity;
import org.petalos.cam.ui.ExposureSliderView;
import org.petalos.cam.ui.FocusRingView;
import org.petalos.cam.ui.GridOverlayView;
import org.petalos.cam.ui.LevelView;
import org.petalos.cam.ui.ModeSelectorView;
import org.petalos.cam.ui.PetalPress;
import org.petalos.cam.ui.ProPanelView;
import org.petalos.cam.ui.ShutterButtonView;
import org.petalos.cam.ui.ZoomSelectorView;
import org.petalos.cam.vendor.VendorCameraInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// the camera activity. owns the viewfinder chrome, CameraController does the work
public class PetalCamActivity extends AppCompatActivity implements CameraController.Listener {

    private static final String TAG = "PetalCam";
    private static final int REQ_PERMS = 1001;

    // Flash cycle
    private static final int[] FLASH_CYCLE = {
            ImageCapture.FLASH_MODE_OFF,
            ImageCapture.FLASH_MODE_ON,
            ImageCapture.FLASH_MODE_AUTO,
    };

    private PreviewView mPreview;
    private GridOverlayView mGrid;
    private LevelView mLevel;
    private FocusRingView mFocusRing;
    private ShutterButtonView mShutter;
    private ModeSelectorView mModes;
    private ZoomSelectorView mZoom;
    private ExposureSliderView mExposure;
    private ProPanelView mProPanel;
    private ImageButton mBtnFlash;
    private ImageButton mBtnTimer;
    private ImageButton mBtnFlip;
    private ImageButton mBtnHdr;
    private ImageButton mBtnGrid;
    private ImageButton mBtnRotate;
    private List<String> mAvailableModes = new ArrayList<>();
    private String mCurrentMode;
    private ImageView mThumb;
    private View mThumbFrame;
    private TextView mCountdown;
    private TextView mToast;
    private View mQrPopup;
    private TextView mQrText;
    private TextView mQrHint;
    private String mQrValue;
    private View mRecChip;
    private TextView mRecTime;
    private View mProcessing;
    private android.os.Handler mHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private CameraController mController;
    private CameraController.Info mInfo;
    private ProControls mPro;
    private MediaActionSound mShutterSound;

    private int mFlashIndex = 0;
    private int mTimer = 0; // 0, 3, 10
    private boolean mHdrEnabled = true;
    private boolean mAutoRotate;
    private boolean mInitialized;
    private boolean mStartRecordingAfterBind;
    private boolean mRecordingUi;
    private long mRecordStart;
    private boolean mCountdownRunning;
    private Uri mLastUri;

    private float mZoomStart = 1f;
    private float mCurrentZoom = 1f;

    private float mIsoMin = 50, mIsoMax = 6400;
    private long mShutterMinNs = 1_000_000L, mShutterMaxNs = 1_000_000_000L;
    private float mFocusMaxDiopters = 0f;

    private static final String[] AWB_PRESETS = {
            "Auto", "Incandescent", "Fluorescent", "Warm", "Daylight", "Cloudy", "Twilight",
            "Shade",
    };
    private static final int[] AWB_VALUES = {1, 2, 3, 9, 5, 6, 8, 7};

    private final android.os.Handler mRecHandler = new android.os.Handler(
            android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_petal_cam);
        applyImmersive();

        bindViews();
        mAutoRotate = PetalCamPrefs.autoRotate(this);
        applyAutoRotate();
        updateRotateButton();
        setupModes();
        setupControls();
        setupGestures();
        applyGrid(PetalCamPrefs.gridMode(this));

        mController = new CameraController(this, this, mPreview, this);
        mShutterSound = new MediaActionSound();
        try {
            mShutterSound.load(MediaActionSound.SHUTTER_CLICK);
        } catch (Throwable ignored) {
        }

        if (hasCorePermissions()) {
            mController.initialize();
            mInitialized = true;
        } else {
            requestPermissions(corePermissions(), REQ_PERMS);
        }
        refreshThumbnail();
    }

    private void applyImmersive() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        // when bars peek back in, dont let the chrome sit on the glass
        View top = findViewById(R.id.top_bar);
        View bottom = findViewById(R.id.bottom_bar);
        int baseTopPad = top.getPaddingTop();
        int baseBottomPad = bottom.getPaddingBottom();
        findViewById(R.id.cam_root).setOnApplyWindowInsetsListener((v, insets) -> {
            int bars = android.view.WindowInsets.Type.statusBars()
                    | android.view.WindowInsets.Type.displayCutout()
                    | android.view.WindowInsets.Type.navigationBars();
            android.graphics.Insets sys = insets.getInsets(bars);
            top.setPadding(top.getPaddingLeft(), baseTopPad + sys.top,
                    top.getPaddingRight(), top.getPaddingBottom());
            bottom.setPadding(bottom.getPaddingLeft(), bottom.getPaddingTop(),
                    bottom.getPaddingRight(), baseBottomPad + sys.bottom);
            return android.view.WindowInsets.CONSUMED;
        });
    }

    private void bindViews() {
        mPreview = findViewById(R.id.preview);
        // letterbox the feed, what you see is what actually lands in the jpeg
        mPreview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        // TextureView-backed preview so the ripple can snapshot the frame
        // cheaply and reliably (SurfaceView readback is slower/flakier)
        mPreview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        mGrid = findViewById(R.id.grid);
        mLevel = findViewById(R.id.level);
        mFocusRing = findViewById(R.id.focus_ring);
        mShutter = findViewById(R.id.shutter);
        mModes = findViewById(R.id.modes);
        mZoom = findViewById(R.id.zoom);
        mExposure = findViewById(R.id.exposure);
        mProPanel = findViewById(R.id.pro_panel);
        // park the pro glass and the qr card just above the zoom blob row, the
        // hardcoded bottom margins kept getting eaten once nav insets landed
        final View bottomBar = findViewById(R.id.bottom_bar);
        bottomBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            float d = getResources().getDisplayMetrics().density;
            int base = v.getHeight() + Math.round(10f * d);
            liftAboveBar(mProPanel, base);
            liftAboveBar(mToast, base);
            if (mQrPopup != null) {
                // extra room so the slide-up never dips back into the chips
                liftAboveBar(mQrPopup, base + Math.round(40f * d));
            }
        });
        mBtnFlash = findViewById(R.id.btn_flash);
        mBtnTimer = findViewById(R.id.btn_timer);
        mBtnFlip = findViewById(R.id.btn_flip);
        mBtnHdr = findViewById(R.id.btn_hdr);
        mBtnGrid = findViewById(R.id.btn_grid);
        mBtnRotate = findViewById(R.id.btn_rotate);
        mThumb = findViewById(R.id.thumb);
        mThumbFrame = findViewById(R.id.thumb_frame);
        mCountdown = findViewById(R.id.countdown);
        mToast = findViewById(R.id.toast);
        mQrPopup = findViewById(R.id.qr_popup);
        mQrText = findViewById(R.id.qr_text);
        mQrHint = findViewById(R.id.qr_hint);
        findViewById(R.id.qr_close).setOnClickListener(v -> hideQr());
        mQrPopup.setOnClickListener(v -> handleQrAction());
        mRecChip = findViewById(R.id.rec_chip);
        mRecTime = findViewById(R.id.rec_time);
        mProcessing = findViewById(R.id.processing);
        setupPressPhysics();
    }

    /** Springy liquid press on every tappable chrome control. */
    private void setupPressPhysics() {
        PetalPress.attach(mBtnFlash);
        PetalPress.attach(mBtnTimer);
        PetalPress.attach(mBtnHdr);
        PetalPress.attach(mBtnGrid);
        PetalPress.attach(mBtnRotate);
        PetalPress.attach(findViewById(R.id.btn_settings));
        PetalPress.attach(mBtnFlip);
        PetalPress.attach(mThumbFrame);
    }

    private void setupModes() {
        mModes.setOnModeSelectedListener(index -> {
            if (index < 0 || index >= mAvailableModes.size()) {
                return;
            }
            String mode = mAvailableModes.get(index);
            mCurrentMode = mode;
            PetalCamPrefs.setLastMode(this, mode);
            onModeChanged(mode);
        });
        refreshModes();
    }

    /** Rebuilds the strip, dropping modes this lens has no vendor pipeline for. */
    private void refreshModes() {
        List<String> modes = new ArrayList<>(Arrays.asList(
                CameraController.MODE_PHOTO,
                CameraController.MODE_PORTRAIT,
                CameraController.MODE_NIGHT,
                CameraController.MODE_VIDEO,
                CameraController.MODE_PRO));
        if (mInfo != null) {
            List<String> usable = new ArrayList<>();
            for (String m : modes) {
                // PORTRAIT is always offered now: it is handled by the
                // on-device depth model, no OEM bokeh extension required.
                if (CameraController.MODE_NIGHT.equals(m)
                        && !mInfo.extensionModes.contains("Night")) {
                    continue;
                }
                usable.add(m);
            }
            modes = usable;
        }
        String keep = mCurrentMode != null ? mCurrentMode : PetalCamPrefs.lastMode(this);
        mAvailableModes = modes;
        mModes.setModes(modes);
        int idx = keep != null ? modes.indexOf(keep) : 0;
        if (idx < 0) {
            idx = 0;
        }
        mCurrentMode = modes.get(idx);
        // animate: a camera rebind fires this again and must not snap the blob
        mModes.setSelected(idx, true);
        // kept mode just got hidden by a lens switch, rebind to what is shown
        if (mInfo != null && !modes.get(idx).equals(mController.mode())) {
            onModeChanged(modes.get(idx));
        }
    }

    private void onModeChanged(String mode) {
        boolean video = CameraController.MODE_VIDEO.equals(mode);
        boolean pro = CameraController.MODE_PRO.equals(mode);
        mShutter.setVideoMode(video);
        mProPanel.setVisibility(pro ? View.VISIBLE : View.GONE);
        if (pro) {
            // manual mode already owns exposure, the rail just smothers the readouts
            mExposure.dismiss();
        }
        if (!pro && mPro != null) {
            mPro.reset();
        }
        mController.setMode(mode);
        if (mInfo != null && CameraController.MODE_NIGHT.equals(mode)
                && !mInfo.extensionModes.contains("Night")) {
            showToast(getString(R.string.vendor_mode_unavailable));
        }
    }

    private boolean isProMode() {
        return CameraController.MODE_PRO.equals(mCurrentMode);
    }

    private void liftAboveBar(View view, int px) {
        if (view == null) {
            return;
        }
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        if (lp != null && lp.bottomMargin != px) {
            lp.bottomMargin = px;
            view.setLayoutParams(lp);
        }
    }

    private void setupControls() {
        mShutter.setOnShutterListener(new ShutterButtonView.OnShutterListener() {
            @Override
            public void onShutterClick() {
                performShutterAction();
            }

            @Override
            public void onShutterLongPress() {
                if (!mController.isRecording()) {
                    if (!CameraController.MODE_VIDEO.equals(mController.mode())) {
                        mStartRecordingAfterBind = true;
                        selectMode(CameraController.MODE_VIDEO);
                    } else {
                        startRecording();
                    }
                }
            }

            @Override
            public void onShutterRelease() {
                // Released before the video pipeline finished rebinding: abort.
                mStartRecordingAfterBind = false;
                if (mController.isRecording()) {
                    mController.stopRecording();
                }
            }
        });

        mZoom.setOnZoomSelectedListener(ratio -> {
            mCurrentZoom = ratio;
            mController.setZoomRatio(ratio);
        });

        mExposure.setOnExposureListener(ev -> {
            int index = Math.round(ev);
            mController.setExposureIndex(index);
        });

        mBtnFlash.setOnClickListener(v -> cycleFlash());
        mBtnTimer.setOnClickListener(v -> cycleTimer());
        mBtnFlip.setOnClickListener(v -> mController.switchLens());
        mBtnHdr.setOnClickListener(v -> {
            mHdrEnabled = !mHdrEnabled;
            mController.setHdrEnabled(mHdrEnabled);
            updateHdrButton();
            showToast(mHdrEnabled ? "Petal HDR on" : "Petal HDR off");
        });
        mBtnGrid.setOnClickListener(v -> cycleGrid());
        mBtnRotate.setOnClickListener(v -> toggleAutoRotate());
        findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        mThumbFrame.setOnClickListener(v -> openGallery());

        mProPanel.setOnProChangeListener(this::onProChanged);
        mProPanel.setVisibility(View.GONE);
    }

    // ---- Gestures -------------------------------------------------------

    private void setupGestures() {
        final ScaleGestureDetector scale = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                        mZoomStart = mCurrentZoom;
                        return true;
                    }

                    @Override
                    public boolean onScale(@NonNull ScaleGestureDetector detector) {
                        float target = mZoomStart * detector.getScaleFactor();
                        target = Math.max(0.5f, Math.min(10f, target));
                        mCurrentZoom = target;
                        mController.setZoomRatio(target);
                        mZoom.setSelected(target);
                        return true;
                    }
                });

        final GestureDetector taps = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapUp(@NonNull MotionEvent e) {
                        // fresh tap drops any AE/AF lock and meters once
                        mController.unlockFocusAndExposure();
                        mFocusRing.focusAt(e.getX(), e.getY());
                        mController.tapToFocus(e.getX(), e.getY());
                        if (!isProMode()) {
                            mExposure.reveal();
                        }
                        return true;
                    }

                    @Override
                    public void onLongPress(@NonNull MotionEvent e) {
                        // hold locks exposure + focus, grey padlock comes up
                        mFocusRing.lockAt(e.getX(), e.getY());
                        mController.lockFocusAndExposure(e.getX(), e.getY());
                        if (!isProMode()) {
                            mExposure.reveal();
                        }
                    }

                    @Override
                    public boolean onDoubleTap(@NonNull MotionEvent e) {
                        float target = mCurrentZoom > 1.05f ? 1f : 2f;
                        if (mInfo != null && target > mInfo.maxZoomRatio) {
                            target = mInfo.maxZoomRatio;
                        }
                        mCurrentZoom = target;
                        mController.setZoomRatio(target);
                        mZoom.setSelected(target);
                        return true;
                    }
                });

        mPreview.setOnTouchListener((v, event) -> {
            scale.onTouchEvent(event);
            taps.onTouchEvent(event);
            return true;
        });
    }

    // ---- Capture --------------------------------------------------------

    // one entry point so the button and the volume key can never drift apart
    private void performShutterAction() {
        if (CameraController.MODE_VIDEO.equals(mController.mode())) {
            toggleRecording();
        } else if (mTimer > 0) {
            runCountdown(mTimer);
        } else {
            capture();
        }
    }

    // vol-up is a shutter now, vol-down stays volume like a normal person's phone
    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP
                && PetalCamPrefs.volumeShutter(this)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                performShutterAction();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void capture() {
        if (mShutterSound != null && PetalCamPrefs.shutterSound(this)) {
            try {
                mShutterSound.play(MediaActionSound.SHUTTER_CLICK);
            } catch (Throwable ignored) {
            }
        }
        mController.takePhoto();
    }

    private void runCountdown(int seconds) {
        mCountdownRunning = true;
        mCountdown.setVisibility(View.VISIBLE);
        final int[] remaining = {seconds};
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (!mCountdownRunning) {
                return;
            }
            if (remaining[0] <= 0) {
                mCountdown.setVisibility(View.GONE);
                mCountdownRunning = false;
                capture();
                return;
            }
            mCountdown.setText(String.valueOf(remaining[0]));
            mCountdown.setAlpha(0f);
            mCountdown.setScaleX(1.4f);
            mCountdown.setScaleY(1.4f);
            mCountdown.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).start();
            remaining[0]--;
            mHandler.postDelayed(tick[0], 1000);
        };
        tick[0].run();
    }

    private void toggleRecording() {
        if (mController.isRecording()) {
            mController.stopRecording();
        } else {
            startRecording();
        }
    }

    private void startRecording() {
        if (!CameraController.MODE_VIDEO.equals(mController.mode())) {
            mStartRecordingAfterBind = true;
            selectMode(CameraController.MODE_VIDEO);
            return;
        }
        mController.startRecording();
    }

    private void selectMode(String mode) {
        int idx = mModeList().indexOf(mode);
        if (idx >= 0) {
            mModes.setSelected(idx);
        }
        onModeChanged(mode);
    }

    private List<String> mModeList() {
        return Arrays.asList(
                CameraController.MODE_PHOTO,
                CameraController.MODE_PORTRAIT,
                CameraController.MODE_NIGHT,
                CameraController.MODE_VIDEO,
                CameraController.MODE_PRO);
    }

    // ---- Flash / grid / HDR --------------------------------------------

    private void cycleTimer() {
        mTimer = mTimer == 0 ? 3 : mTimer == 3 ? 10 : 0;
        // dim the button so you can tell the timer is armed
        mBtnTimer.setAlpha(mTimer > 0 ? 1f : 0.55f);
        showToast(mTimer > 0 ? "Timer " + mTimer + "s" : "Timer off");
    }

    private void cycleFlash() {
        if (mInfo != null && !mInfo.flashAvailable) {
            showToast("No flash on this camera");
            return;
        }
        mFlashIndex = (mFlashIndex + 1) % 4; // off, on, auto, torch
        if (mFlashIndex == 3) {
            mController.setTorch(true);
            mController.setFlashMode(ImageCapture.FLASH_MODE_OFF);
            mBtnFlash.setImageResource(R.drawable.ic_cam_flash_torch);
            showToast("Torch");
        } else {
            mController.setTorch(false);
            int mode = FLASH_CYCLE[mFlashIndex];
            mController.setFlashMode(mode);
            updateFlashIcon();
            showToast(mode == ImageCapture.FLASH_MODE_ON ? "Flash on"
                    : mode == ImageCapture.FLASH_MODE_AUTO ? "Flash auto" : "Flash off");
        }
    }

    private void updateFlashIcon() {
        switch (mFlashIndex) {
            case 1:
                mBtnFlash.setImageResource(R.drawable.ic_cam_flash_on);
                break;
            case 2:
                mBtnFlash.setImageResource(R.drawable.ic_cam_flash_auto);
                break;
            default:
                mBtnFlash.setImageResource(R.drawable.ic_cam_flash_off);
        }
    }

    private void cycleGrid() {
        int mode = (mGrid.getMode() + 1) % 4;
        PetalCamPrefs.setGridMode(this, mode);
        applyGrid(mode);
    }

    private void applyGrid(int mode) {
        mGrid.setMode(mode);
        mLevel.setActive(mode == GridOverlayView.THIRDS || mode == GridOverlayView.GOLDEN);
    }

    private void updateHdrButton() {
        boolean available = mInfo != null && mInfo.extensionModes.contains("HDR");
        mBtnHdr.setAlpha(mHdrEnabled && available ? 1f : 0.45f);
    }

    // ---- Auto rotate ----------------------------------------------------

    private void toggleAutoRotate() {
        mAutoRotate = !mAutoRotate;
        PetalCamPrefs.setAutoRotate(this, mAutoRotate);
        applyAutoRotate();
        updateRotateButton();
        showToast(getString(mAutoRotate ? R.string.autorotate_on : R.string.autorotate_off));
    }

    /** Off locks the viewfinder upright; on hands it back to the sensor. */
    private void applyAutoRotate() {
        setRequestedOrientation(mAutoRotate
                ? ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    private void updateRotateButton() {
        mBtnRotate.setAlpha(mAutoRotate ? 1f : 0.55f);
    }

    // ---- Camera listener ------------------------------------------------

    @Override
    public void onCameraReady(@NonNull CameraController.Info info) {
        mInfo = info;
        if (info.zoomChips != null && !info.zoomChips.isEmpty()) {
            mZoom.setRatios(info.zoomChips);
            mZoom.setSelected(mCurrentZoom);
        }
        refreshModes();
        mExposure.setRange(info.exposureMin, info.exposureMax);
        mExposure.setVisibility(info.exposureMax > info.exposureMin
                ? View.VISIBLE : View.INVISIBLE);
        mBtnFlip.setEnabled(true);
        updateHdrButton();
        updateFlashIcon();
        loadProRanges();
        if (CameraController.MODE_PRO.equals(mController.mode())) {
            buildProPanel();
        }
        if (mStartRecordingAfterBind && CameraController.MODE_VIDEO.equals(mController.mode())) {
            mStartRecordingAfterBind = false;
            mHandler.postDelayed(this::startRecording, 350);
        }
    }

    @Override
    public void onCameraError(@NonNull String message) {
        showToast(message);
    }

    @Override
    public void onPhotoSaved(@Nullable Uri uri, boolean ok) {
        // keep the spinner up: the portrait/denoise stages are still running
        if (ok && uri != null) {
            mLastUri = uri;
            showToast(getString(R.string.saved));
            refreshThumbnail();
        } else {
            if (mProcessing != null) {
                mProcessing.setVisibility(View.GONE);
            }
            showToast("Couldn't save photo");
        }
        mShutter.setShutterEnabled(true);
    }

    @Override
    public void onPhotoProcessed(@Nullable Uri uri, boolean ok) {
        if (mProcessing != null) {
            mProcessing.setVisibility(View.GONE);
        }
        if (ok && uri != null) {
            mLastUri = uri;
            showToast(getString(R.string.processing_done));
            refreshThumbnail();
        }
    }

    @Override
    public void onCaptureStarted() {
        if (mProcessing != null) {
            mProcessing.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onRecordingStarted() {
        mRecordingUi = true;
        mRecordStart = System.currentTimeMillis();
        mShutter.setRecording(true);
        mRecChip.setVisibility(View.VISIBLE);
        startRecordTicker();
    }

    @Override
    public void onRecordingStopped(@Nullable Uri uri, boolean ok) {
        mRecordingUi = false;
        mShutter.setRecording(false);
        mRecChip.setVisibility(View.GONE);
        mRecHandler.removeCallbacks(mRecordTicker);
        if (ok && uri != null) {
            mLastUri = uri;
            showToast(getString(R.string.saved));
            refreshThumbnail();
        } else {
            showToast("Recording failed");
        }
    }

    @Override
    public void onFocusResult(boolean locked) {
        if (locked) {
            mFocusRing.focusSuccess();
        } else {
            mFocusRing.focusFail();
        }
    }

    @Override
    public void onVendorInfo(@NonNull VendorCameraInfo info, @NonNull String engineStatus) {
        Log.i(TAG, "Vendor=" + info.vendorName + " engine=" + engineStatus);
        updateHdrButton();
    }

    private final Runnable mRecordTicker = new Runnable() {
        @Override
        public void run() {
            long elapsed = System.currentTimeMillis() - mRecordStart;
            long sec = elapsed / 1000;
            mRecTime.setText(String.format(java.util.Locale.US, "%02d:%02d",
                    sec / 60, sec % 60));
            if (mRecordingUi) {
                mRecHandler.postDelayed(this, 500);
            }
        }
    };

    private void startRecordTicker() {
        mRecHandler.removeCallbacks(mRecordTicker);
        mRecHandler.post(mRecordTicker);
    }

    // ---- PRO mode -------------------------------------------------------

    private void loadProRanges() {
        android.hardware.camera2.CameraCharacteristics c = mController.characteristics();
        if (c == null) {
            return;
        }
        try {
            Range<Integer> iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if (iso != null) {
                mIsoMin = iso.getLower();
                mIsoMax = iso.getUpper();
            }
            Range<Long> exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            if (exp != null) {
                mShutterMinNs = exp.getLower();
                mShutterMaxNs = exp.getUpper();
            }
            Float minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            mFocusMaxDiopters = minFocus != null ? minFocus : 0f;
        } catch (Throwable t) {
            Log.d(TAG, "Pro ranges unavailable", t);
        }
    }

    private void buildProPanel() {
        if (mController.cameraControl() == null) {
            return;
        }
        if (mPro == null) {
            mPro = new ProControls(mController.cameraControl());
        }
        mProPanel.clearControls();
        mProPanel.addControl("iso", "ISO", "", (int) mIsoMin, (int) mIsoMax,
                (int) mIsoMin, value -> String.valueOf(value));
        mProPanel.addControl("shutter", "SHUTTER", "", 0, 100, 50,
                this::formatShutter);
        if (mFocusMaxDiopters > 0f) {
            mProPanel.addControl("focus", "FOCUS", "", 0, 100, 0,
                    value -> String.format(java.util.Locale.US, "%.2f",
                            mFocusMaxDiopters * value / 100f));
        }
        mProPanel.addControl("wb", "WHITE BALANCE", "", 0, 100, 0,
                value -> AWB_PRESETS[Math.min(AWB_PRESETS.length - 1,
                        Math.round(value / 100f * (AWB_PRESETS.length - 1)))]);
    }

    private String formatShutter(int value) {
        long ns = mapShutter(value);
        double seconds = ns / 1_000_000_000.0;
        if (seconds >= 1.0) {
            return String.format(java.util.Locale.US, "%.1fs", seconds);
        }
        int denom = (int) Math.round(1.0 / Math.max(seconds, 1e-6));
        return "1/" + denom;
    }

    private long mapShutter(int value) {
        float t = Math.max(0f, Math.min(1f, value / 100f));
        double ratio = (double) mShutterMaxNs / Math.max(1L, mShutterMinNs);
        return Math.round(mShutterMinNs * Math.pow(ratio, t));
    }

    private void onProChanged(String key, int value) {
        if (mPro == null) {
            return;
        }
        switch (key) {
            case "iso":
                mPro.setIso(value);
                break;
            case "shutter":
                mPro.setShutterNs(mapShutter(value));
                break;
            case "focus":
                mPro.setFocusDiopters(mFocusMaxDiopters * value / 100f);
                break;
            case "wb": {
                int idx = Math.min(AWB_VALUES.length - 1,
                        Math.round(value / 100f * (AWB_VALUES.length - 1)));
                mPro.setAwbMode(AWB_VALUES[idx]);
                break;
            }
            default:
                break;
        }
    }

    // ---- Thumbnail / gallery -------------------------------------------

    private void refreshThumbnail() {
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            Uri uri = queryLatest(app);
            Bitmap bmp = null;
            if (uri != null) {
                try {
                    bmp = app.getContentResolver().loadThumbnail(uri,
                            new android.util.Size(256, 256), null);
                } catch (Throwable ignored) {
                }
            }
            final Bitmap result = bmp;
            mHandler.post(() -> {
                if (result != null) {
                    mThumb.setImageBitmap(result);
                }
            });
        }).start();
    }

    @Nullable
    private Uri queryLatest(android.content.Context context) {
        Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String[] projection = {MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE};
        String selection = MediaStore.Files.FileColumns.MEDIA_TYPE + "=? OR "
                + MediaStore.Files.FileColumns.MEDIA_TYPE + "=?";
        String[] args = {
                String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
                String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)};
        try (Cursor cursor = context.getContentResolver().query(collection, projection,
                selection, args, MediaStore.Files.FileColumns.DATE_ADDED + " DESC")) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                int type = cursor.getInt(1);
                if (type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                    return android.content.ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                }
                return android.content.ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
            }
        } catch (Throwable t) {
            Log.d(TAG, "Thumbnail query failed", t);
        }
        return null;
    }

    private void openGallery() {
        // plain ACTION_VIEW so the resolver shows just once / always, no shortcut past it
        Uri uri = mLastUri != null
                ? mLastUri
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, "image/*");
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(view);
        } catch (Throwable t) {
            showToast("No gallery app");
        }
    }

    // ---- Toast / lifecycle / permissions --------------------------------

    private void showToast(String text) {
        mToast.setText(text);
        mToast.setVisibility(View.VISIBLE);
        mToast.setAlpha(0f);
        mToast.animate().alpha(1f).setDuration(180).start();
        mHandler.removeCallbacks(mHideToast);
        mHandler.postDelayed(mHideToast, 1800);
    }

    private final Runnable mHideToast = () -> {
        mToast.animate().alpha(0f).setDuration(220).withEndAction(
                () -> mToast.setVisibility(View.GONE)).start();
    };

    // ---- QR / barcode ---------------------------------------------------

    @Override
    public void onQrDetected(@NonNull String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        if (text.equals(mQrValue) && mQrPopup.getVisibility() == View.VISIBLE) {
            return;
        }
        mQrValue = text;
        mQrText.setText(text);
        mQrHint.setText(getString(isUrl(text) ? R.string.qr_hint_open : R.string.qr_hint_copy));
        mQrPopup.setVisibility(View.VISIBLE);
        mQrPopup.setAlpha(0f);
        mQrPopup.setTranslationY(40f * getResources().getDisplayMetrics().density);
        mQrPopup.animate().alpha(1f).translationY(0f).setDuration(220).start();
        mHandler.removeCallbacks(mHideQr);
        mHandler.postDelayed(mHideQr, 9000);
    }

    private static boolean isUrl(String text) {
        String t = text.trim();
        return android.util.Patterns.WEB_URL.matcher(t).matches()
                || t.toLowerCase(java.util.Locale.US).startsWith("http");
    }

    private void handleQrAction() {
        if (mQrValue == null) {
            return;
        }
        String trimmed = mQrValue.trim();
        if (isUrl(trimmed)) {
            String url = trimmed.toLowerCase(java.util.Locale.US).startsWith("http")
                    ? trimmed : "https://" + trimmed;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Throwable t) {
                showToast("No app can open this link");
            }
        } else {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("QR", mQrValue));
            }
            showToast(getString(R.string.qr_copied));
        }
        hideQr();
    }

    private void hideQr() {
        mHandler.removeCallbacks(mHideQr);
        mQrValue = null;
        if (mQrPopup.getVisibility() != View.VISIBLE) {
            return;
        }
        mQrPopup.animate().alpha(0f).setDuration(180).withEndAction(
                () -> mQrPopup.setVisibility(View.GONE)).start();
    }

    private final Runnable mHideQr = this::hideQr;

    private boolean hasCorePermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String[] corePermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.CAMERA);
        perms.add(Manifest.permission.RECORD_AUDIO);
        if (PetalCamPrefs.location(this)) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES);
            perms.add(Manifest.permission.READ_MEDIA_VIDEO);
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return perms.toArray(new String[0]);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                if (!mInitialized) {
                    mController.initialize();
                    mInitialized = true;
                }
            } else {
                showToast(getString(R.string.permission_needed));
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyAutoRotate();
        if (mController != null) {
            mController.setHdrEnabled(mHdrEnabled);
            // camera quietly reopens after gallery/settings, zoom forgets itself
            mController.reapplyZoom();
        }
        mThumbFrame.setOnClickListener(v -> openGallery());
        refreshThumbnail();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mController != null && mController.isRecording()) {
            mController.stopRecording();
        }
    }

    @Override
    protected void onDestroy() {
        if (mController != null) {
            mController.release();
        }
        if (mShutterSound != null) {
            mShutterSound.release();
        }
        super.onDestroy();
    }
}
