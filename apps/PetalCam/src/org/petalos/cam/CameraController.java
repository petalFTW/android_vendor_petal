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

import android.content.Context;
import android.content.ContentResolver;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExposureState;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.extensions.ExtensionMode;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import org.petalos.cam.capture.LocationProvider;
import org.petalos.cam.capture.MediaSaver;
import org.petalos.cam.capture.NightStacker;
import org.petalos.cam.capture.QrAnalyzer;
import org.petalos.cam.vendor.ExtensionBridge;
import org.petalos.cam.vendor.VendorCameraBridge;
import org.petalos.cam.vendor.VendorCameraInfo;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

// camerax wrangling, rebinds on lens or mode changes
public class CameraController {

    private static final String TAG = "PetalCamController";

    public enum Lens { BACK, FRONT }

    public static final String MODE_PHOTO = "PHOTO";
    public static final String MODE_PORTRAIT = "PORTRAIT";
    public static final String MODE_NIGHT = "NIGHT";
    public static final String MODE_VIDEO = "VIDEO";
    public static final String MODE_PRO = "PRO";

    public static final class Info {
        public float minZoomRatio = 1f;
        public float maxZoomRatio = 1f;
        public boolean flashAvailable;
        public boolean focusAvailable;
        public int exposureMin;
        public int exposureMax;
        public List<Float> zoomChips = new ArrayList<>();
        public String lensTag = "back";
        public List<String> extensionModes = new ArrayList<>();
    }

    public interface Listener {
        void onCameraReady(@NonNull Info info);

        void onCameraError(@NonNull String message);

        void onPhotoSaved(@Nullable Uri uri, boolean ok);

        /** Fired once the portrait/denoise ML stages have finished and the
         *  final pixels are in the file (or processing gave up). */
        void onPhotoProcessed(@Nullable Uri uri, boolean ok);

        void onCaptureStarted();

        void onRecordingStarted();

        void onRecordingStopped(@Nullable Uri uri, boolean ok);

        void onFocusResult(boolean locked);

        void onVendorInfo(@NonNull VendorCameraInfo info, @NonNull String engineStatus);

        /** A QR/barcode was decoded from the live preview. */
        void onQrDetected(@NonNull String text);
    }

    private final Context mContext;
    private final LifecycleOwner mLifecycleOwner;
    private final PreviewView mPreviewView;
    private final Listener mListener;
    private final Executor mExecutor;
    // image processing (decode/mirror/encode/file IO) never runs on the UI thread
    private final java.util.concurrent.ExecutorService mCaptureExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "PetalCamCapture");
                t.setPriority(Thread.NORM_PRIORITY);
                return t;
            });
    private final android.os.Handler mMain =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final ExtensionBridge mEngine = new ExtensionBridge();
    private final java.util.concurrent.ExecutorService mAnalysisExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "PetalCamQr");
                t.setPriority(Thread.NORM_PRIORITY);
                return t;
            });
    private final QrAnalyzer mQrAnalyzer;

    private ProcessCameraProvider mProvider;
    private Preview mPreview;
    private ImageCapture mImageCapture;
    private VideoCapture<Recorder> mVideoCapture;
    private ImageAnalysis mImageAnalysis;
    private Recording mRecording;
    private Camera mCamera;

    private Lens mLens = Lens.BACK;
    // last applied zoom, tells the denoiser when we are on the ultrawide
    private float mLastZoom = 1f;
    // did the current binding drop oem extensions for the wide cam
    private boolean mBoundUltrawide;
    private String mMode = MODE_PHOTO;
    private int mFlashMode = ImageCapture.FLASH_MODE_OFF;
    private boolean mTorch;
    private boolean mEngineReady;
    private boolean mHdrEnabled = true;
    private String mAspectRatio;
    private int mPhotoQuality;
    private String mVideoQuality;
    private VendorCameraInfo mVendorInfo;
    private float mPendingZoom = 1f;

    private static final long ENGINE_START_TIMEOUT_MS = 4000L;
    private static final int NIGHT_FRAMES = 6;
    private static final float NIGHT_DENOISE = 0.25f;

    private boolean mBound;
    private boolean mReleased;
    private boolean mEngineStartDone;
    // what the camera's actually bound with right now
    private int mBoundExtension = ExtensionMode.NONE;

    public CameraController(@NonNull Context context, @NonNull LifecycleOwner owner,
            @NonNull PreviewView previewView, @NonNull Listener listener) {
        mContext = context;
        mLifecycleOwner = owner;
        mPreviewView = previewView;
        mListener = listener;
        mExecutor = ContextCompat.getMainExecutor(context);
        mQrAnalyzer = new QrAnalyzer(
                text -> mExecutor.execute(() -> mListener.onQrDetected(text)));
        mAspectRatio = PetalCamPrefs.aspectRatio(context);
        mPhotoQuality = PetalCamPrefs.photoJpegQuality(context);
        mVideoQuality = PetalCamPrefs.videoQuality(context);
    }

    public ExtensionBridge engine() {
        return mEngine;
    }

    public VendorCameraInfo vendorInfo() {
        return mVendorInfo;
    }

    public Lens lens() {
        return mLens;
    }

    public String mode() {
        return mMode;
    }

    public boolean isRecording() {
        return mRecording != null;
    }

    public void initialize() {
        // walks every camera over binder, keep that off the main thread
        mCaptureExecutor.execute(() -> {
            VendorCameraInfo info = VendorCameraBridge.detect(mContext);
            mExecutor.execute(() -> {
                mVendorInfo = info;
                if (!mReleased) startProvider();
            });
        });
    }

    private void startProvider() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(mContext);
        future.addListener(() -> {
            try {
                mProvider = future.get();
            } catch (Throwable t) {
                Log.e(TAG, "Provider init failed", t);
                mListener.onCameraError("Camera provider unavailable");
                return;
            }
            if (!PetalCamPrefs.engineStruckOut(mContext)) {
                mMain.postDelayed(mEngineGaveUp, ENGINE_START_TIMEOUT_MS);
                mEngine.initialize(mContext, mProvider, mExecutor, available -> {
                    finishEngineStart(available, mEngine.status());
                });
            } else {
                finishEngineStart(false, "disabled");
            }
        }, mExecutor);
    }

    // a stuck oem init can't get to keep the viewfinder black
    private void finishEngineStart(boolean available, String status) {
        if (mEngineStartDone || mReleased) {
            return;
        }
        mEngineStartDone = true;
        mMain.removeCallbacks(mEngineGaveUp);
        mEngineReady = available;
        mListener.onVendorInfo(mVendorInfo, status);
        bindCurrentMode(true);
    }

    private final Runnable mEngineGaveUp = () -> {
        Log.w(TAG, "Engine start timed out, going plain");
        finishEngineStart(false, "timed out");
    };


    public void setLens(Lens lens) {
        if (mLens == lens) {
            return;
        }
        mLens = lens;
        mTorch = false;
        // both lenses were already probed at init, no need to re-probe here;
        // skipping it keeps the switch to a single unbind/bind
        bindCurrentMode(false);
    }

    public void switchLens() {
        setLens(mLens == Lens.BACK ? Lens.FRONT : Lens.BACK);
    }

    public void setMode(@NonNull String mode) {
        // a persistent recording ignores unbinds, it would keep running headless
        if (mMode.equals(mode) || mRecording != null) {
            return;
        }
        mMode = mode;
        bindCurrentMode(false);
    }

    public void setAspectRatio(@NonNull String ratio) {
        if (!mAspectRatio.equals(ratio)) {
            mAspectRatio = ratio;
            if (!MODE_VIDEO.equals(mMode)) bindCurrentMode(false);
        }
    }

    /** Apply settings changed while the settings screen was open. */
    public void refreshQuality() {
        int photo = PetalCamPrefs.photoJpegQuality(mContext);
        String video = PetalCamPrefs.videoQuality(mContext);
        if (photo != mPhotoQuality || !video.equals(mVideoQuality)) {
            mPhotoQuality = photo;
            mVideoQuality = video;
            if (mBound && mRecording == null) bindCurrentMode(false);
        }
    }

    /** Toggle the OEM HDR pipeline in photo mode. */
    public void setHdrEnabled(boolean enabled) {
        if (mHdrEnabled != enabled) {
            mHdrEnabled = enabled;
            if (MODE_PHOTO.equals(mMode) && mBound) {
                bindCurrentMode(false);
            }
        }
    }

    public CameraSelector baseSelector() {
        return mLens == Lens.FRONT ? CameraSelector.DEFAULT_FRONT_CAMERA
                : CameraSelector.DEFAULT_BACK_CAMERA;
    }

    private String lensTag() {
        return mLens == Lens.FRONT ? "front" : "back";
    }

    private int extensionModeFor(String mode) {
        switch (mode) {
            case MODE_PORTRAIT:
                return ExtensionMode.BOKEH;
            case MODE_NIGHT:
                return ExtensionMode.NIGHT;
            default:
                return ExtensionMode.NONE;
        }
    }

    private void bindCurrentMode(boolean probeEngine) {
        if (mProvider == null) {
            return;
        }
        final CameraSelector base = baseSelector();

        if (probeEngine && mEngineReady) {
            mEngine.probe(base, lensTag());
            // Re-probe the other lens so zoom chips and mode availability are warm.
            mEngine.probe(mLens == Lens.BACK ? CameraSelector.DEFAULT_FRONT_CAMERA
                    : CameraSelector.DEFAULT_BACK_CAMERA, mLens == Lens.BACK ? "front" : "back");
        }

        CameraSelector selector = base;
        int boundExt = ExtensionMode.NONE;
        // below 1x we need the physical wide cam, and oem extensions tend to
        // clamp zoom at 1.0, so drop them until we are back on the main lens
        boolean wantUltrawide = mPendingZoom < 0.95f;
        int ext = mEngineReady ? extensionModeFor(mMode) : ExtensionMode.NONE;
        if (wantUltrawide && ext != ExtensionMode.NONE) {
            Log.i(TAG, "zoom " + mPendingZoom + " wants the wide cam, dropping extension");
            ext = ExtensionMode.NONE;
        }
        mBoundUltrawide = wantUltrawide;
        if (ext != ExtensionMode.NONE) {
            selector = mEngine.selectorFor(base, lensTag(),
                    ext == ExtensionMode.BOKEH ? "Bokeh" : "Night", ext);
            boundExt = ext;
        } else if (!wantUltrawide && mMode.equals(MODE_PHOTO) && mHdrEnabled && mEngineReady
                && mEngine.isModeAvailable(lensTag(), "HDR")) {
            // Photo mode leans on the OEM HDR pipeline when it exists.
            selector = mEngine.selectorFor(base, lensTag(), "HDR", ExtensionMode.HDR);
            boundExt = ExtensionMode.HDR;
        }

        mProvider.unbindAll();
        try {
            int targetRatio = MODE_VIDEO.equals(mMode) || "16:9".equals(mAspectRatio)
                    ? AspectRatio.RATIO_16_9 : AspectRatio.RATIO_4_3;
            mPreview = new Preview.Builder()
                    .setTargetAspectRatio(targetRatio)
                    .build();
            mPreview.setSurfaceProvider(mPreviewView.getSurfaceProvider());

            if (MODE_VIDEO.equals(mMode)) {
                // a live recording belongs to this VideoCapture, so a lens flip
                // moves it to the new camera instead of building a new one
                if (mRecording == null || mVideoCapture == null) {
                    List<Quality> qualities;
                    switch (mVideoQuality) {
                        case "uhd": qualities = Arrays.asList(Quality.UHD, Quality.FHD,
                                Quality.HD, Quality.SD); break;
                        case "hd": qualities = Arrays.asList(Quality.HD, Quality.SD); break;
                        default: qualities = Arrays.asList(Quality.FHD, Quality.HD, Quality.SD);
                    }
                    Recorder recorder = new Recorder.Builder()
                            .setQualitySelector(QualitySelector.fromOrderedList(qualities))
                            .build();
                    mVideoCapture = VideoCapture.withOutput(recorder);
                }
                mImageCapture = null;
                mImageAnalysis = null;
                mCamera = mProvider.bindToLifecycle(mLifecycleOwner, selector, mPreview,
                        mVideoCapture);
            } else {
                ImageCapture.Builder icb = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setTargetAspectRatio(targetRatio)
                        .setJpegQuality(mPhotoQuality)
                        .setFlashMode(mFlashMode);
                mVideoCapture = null;

                if (MODE_NIGHT.equals(mMode) && boundExt == ExtensionMode.NONE) {
                    allowLongExposure(icb);
                }
                mImageCapture = icb.build();

                // qr scanning, unless the oem pipeline won't take a third stream
                if (boundExt == ExtensionMode.NONE || mEngine.supportsAnalysis(base, boundExt)) {
                    mImageAnalysis = new ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setTargetResolution(new android.util.Size(1280, 720))
                            .build();
                    mImageAnalysis.setAnalyzer(mAnalysisExecutor, mQrAnalyzer);
                    mCamera = mProvider.bindToLifecycle(mLifecycleOwner, selector, mPreview,
                            mImageCapture, mImageAnalysis);
                } else {
                    mImageAnalysis = null;
                    mCamera = mProvider.bindToLifecycle(mLifecycleOwner, selector, mPreview,
                            mImageCapture);
                }
            }

            mBound = true;
            mBoundExtension = boundExt;
            if (boundExt != ExtensionMode.NONE) watchExtension(mCamera);
            applyZoom(mPendingZoom);
            logBoundCamera();
            scheduleZoomVerify();
            mCamera.getCameraControl().enableTorch(mTorch);
            mListener.onCameraReady(buildInfo());
        } catch (Throwable t) {
            Log.e(TAG, "Bind failed", t);
            mBound = false;
            // oem pipeline said no, a plain camera beats a dead viewfinder
            if (boundExt != ExtensionMode.NONE) {
                Log.w(TAG, "Extension bind failed, going plain");
                mEngineReady = false;
                bindCurrentMode(false);
                return;
            }
            // nothing feeds it now, end the file while it can still be saved
            stopRecording();
            mListener.onCameraError("Could not start camera");
        }
    }

    // let ae drop the frame rate so night frames can actually soak up some light
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void allowLongExposure(ImageCapture.Builder builder) {
        try {
            CameraCharacteristics chars = characteristicsForLens();
            if (chars == null) {
                return;
            }
            android.util.Range<Integer>[] ranges =
                    chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges == null || ranges.length == 0) {
                return;
            }
            android.util.Range<Integer> slowest = ranges[0];
            for (android.util.Range<Integer> range : ranges) {
                if (range.getLower() < slowest.getLower()
                        || (range.getLower().equals(slowest.getLower())
                                && range.getUpper() > slowest.getUpper())) {
                    slowest = range;
                }
            }
            new Camera2Interop.Extender(builder).setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, slowest);
        } catch (Throwable t) {
            Log.d(TAG, "Long exposure unavailable", t);
        }
    }

    // oem sessions die after the bind's already returned fine, catch won't see it
    private void watchExtension(Camera camera) {
        camera.getCameraInfo().getCameraState().observe(mLifecycleOwner, state -> {
            if (state.getError() == null || camera != mCamera || !mEngineReady) {
                return;
            }
            Log.w(TAG, "Extension session died (" + state.getError().getCode() + "), going plain");
            mEngineReady = false;
            bindCurrentMode(false);
        });
    }

    /** CameraCharacteristics for the selected lens, resolved without binding. */
    private CameraCharacteristics characteristicsForLens() {
        try {
            android.hardware.camera2.CameraManager cm =
                    (android.hardware.camera2.CameraManager)
                            mContext.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) {
                return null;
            }
            int want = mLens == Lens.FRONT
                    ? CameraCharacteristics.LENS_FACING_FRONT
                    : CameraCharacteristics.LENS_FACING_BACK;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == want) {
                    return c;
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "Characteristics lookup failed", t);
        }
        return null;
    }

    /** CameraCharacteristics for the bound camera, or null when not bound. */
    @Nullable
    public CameraCharacteristics characteristics() {
        try {
            if (mCamera == null) {
                return null;
            }
            return Camera2Interop.getCameraCharacteristics(mCamera.getCameraInfo());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Live CameraControl, used by PRO mode. */
    @Nullable
    public CameraControl cameraControl() {
        return mCamera != null ? mCamera.getCameraControl() : null;
    }

    public Info buildInfo() {
        Info info = new Info();
        info.lensTag = lensTag();
        if (mCamera == null) {
            return info;
        }
        CameraInfo cameraInfo = mCamera.getCameraInfo();

        try {
            ZoomState zs = cameraInfo.getZoomState().getValue();
            if (zs != null) {
                info.minZoomRatio = zs.getMinZoomRatio();
                info.maxZoomRatio = zs.getMaxZoomRatio();
                info.zoomChips = buildZoomChips(zs.getMinZoomRatio(), zs.getMaxZoomRatio());
            }
        } catch (Throwable t) {
            Log.d(TAG, "Zoom state unavailable", t);
        }
        try {
            info.flashAvailable = cameraInfo.hasFlashUnit();
        } catch (Throwable ignored) {
        }
        try {
            ExposureState es = cameraInfo.getExposureState();
            info.exposureMin = es.getExposureCompensationRange().getLower();
            info.exposureMax = es.getExposureCompensationRange().getUpper();
        } catch (Throwable ignored) {
        }
        // Tap to focus is exposed whenever the camera reports an AF mode.
        info.focusAvailable = true;
        if (mEngineReady) {
            info.extensionModes = mEngine.enabledModes(lensTag());
        }
        return info;
    }

    private static List<Float> buildZoomChips(float min, float max) {
        List<Float> chips = new ArrayList<>();
        if (min < 0.95f) {
            chips.add(round2(Math.max(min, 0.5f)));
        }
        chips.add(1f);
        if (max >= 1.9f) {
            chips.add(2f);
        }
        if (max >= 2.9f) {
            chips.add(3f);
        }
        if (max >= 4.9f) {
            chips.add(5f);
        }
        if (max > chips.get(chips.size() - 1) + 0.1f) {
            chips.add(round2(max));
        }
        return chips;
    }

    private static float round2(float v) {
        return Math.round(v * 10f) / 10f;
    }

    public void setZoomRatio(float ratio) {
        mPendingZoom = ratio;
        // crossing the 1x line flips which pipeline we need, rebind for it
        boolean wantUltrawide = ratio < 0.95f;
        // video has no extension to drop, and a rebind here would hiccup the file
        if (mBound && wantUltrawide != mBoundUltrawide && !MODE_VIDEO.equals(mMode)) {
            Log.i(TAG, "zoom line crossed, rebinding for "
                    + (wantUltrawide ? "wide" : "main"));
            bindCurrentMode(false);
            return;
        }
        applyZoom(ratio);
    }

    private void applyZoom(float ratio) {
        if (!mBound || mCamera == null) {
            return;
        }
        try {
            ZoomState zs = mCamera.getCameraInfo().getZoomState().getValue();
            if (zs == null) {
                return;
            }
            float clamped = Math.max(zs.getMinZoomRatio(), Math.min(zs.getMaxZoomRatio(), ratio));
            if (Math.abs(clamped - ratio) > 0.01f) {
                Log.i(TAG, "zoom " + ratio + " got clamped to " + clamped
                        + " (range " + zs.getMinZoomRatio() + ".." + zs.getMaxZoomRatio() + ")");
            }
            mCamera.getCameraControl().setZoomRatio(clamped);
            // keep what the user asked for, the range can lie while rebinding
            mPendingZoom = ratio;
            mLastZoom = clamped;
        } catch (Throwable t) {
            Log.d(TAG, "Zoom failed", t);
        }
    }

    /** Re-push the zoom after the camera quietly reopened (gallery, resume) */
    public void reapplyZoom() {
        scheduleZoomVerify();
    }

    private void scheduleZoomVerify() {
        mMain.removeCallbacks(mZoomVerify);
        mMain.postDelayed(mZoomVerify, 700);
    }

    private final Runnable mZoomVerify = () -> {
        if (!mBound || mCamera == null) {
            return;
        }
        try {
            ZoomState zs = mCamera.getCameraInfo().getZoomState().getValue();
            if (zs == null) {
                return;
            }
            if (Math.abs(zs.getZoomRatio() - mPendingZoom) > 0.05f) {
                Log.i(TAG, "zoom drifted to " + zs.getZoomRatio()
                        + ", forcing " + mPendingZoom);
                mCamera.getCameraControl().setZoomRatio(mPendingZoom);
            }
        } catch (Throwable t) {
            Log.w(TAG, "zoom verify failed", t);
        }
    };

    /** Dump what camera we actually landed on, cheap proof for the uw debate */
    private void logBoundCamera() {
        try {
            String id = "?";
            String focal = "?";
            try {
                androidx.camera.camera2.interop.Camera2CameraInfo c2 =
                        androidx.camera.camera2.interop.Camera2CameraInfo
                                .from(mCamera.getCameraInfo());
                id = c2.getCameraId();
                float[] lens = c2.getCameraCharacteristic(
                        android.hardware.camera2.CameraCharacteristics
                                .LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                focal = java.util.Arrays.toString(lens);
            } catch (Throwable ignored) {
            }
            ZoomState zs = mCamera.getCameraInfo().getZoomState().getValue();
            Log.i(TAG, "bound camera id=" + id
                    + " focal=" + focal
                    + " zoomRange=" + (zs != null
                            ? zs.getMinZoomRatio() + ".." + zs.getMaxZoomRatio() : "?")
                    + " zoom=" + (zs != null ? zs.getZoomRatio() : 0f)
                    + " mode=" + mMode
                    + " lens=" + lensTag());
        } catch (Throwable t) {
            Log.w(TAG, "camera log failed", t);
        }
    }

    public void setFlashMode(int flashMode) {
        mFlashMode = flashMode;
        if (mImageCapture != null) {
            mImageCapture.setFlashMode(flashMode);
        }
    }

    public int flashMode() {
        return mFlashMode;
    }

    public void setTorch(boolean on) {
        mTorch = on;
        if (mCamera != null) {
            mCamera.getCameraControl().enableTorch(on);
        }
    }

    public void setExposureIndex(int index) {
        if (mCamera != null) {
            mCamera.getCameraControl().setExposureCompensationIndex(index);
        }
    }

    public void tapToFocus(float x, float y) {
        if (mCamera == null || mPreviewView == null) {
            return;
        }
        try {
            MeteringPoint point = mPreviewView.getMeteringPointFactory().createPoint(x, y);
            FocusMeteringAction action = new FocusMeteringAction.Builder(
                    point, FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
            mCamera.getCameraControl().startFocusAndMetering(action)
                    .addListener(() -> mListener.onFocusResult(true), mExecutor);
        } catch (Throwable t) {
            Log.d(TAG, "Focus failed", t);
            mListener.onFocusResult(false);
        }
    }

    /** Tap and hold: pin AE and AF on that point until something taps again. */
    public void lockFocusAndExposure(float x, float y) {
        if (mCamera == null || mPreviewView == null) {
            return;
        }
        try {
            MeteringPoint point = mPreviewView.getMeteringPointFactory().createPoint(x, y);
            FocusMeteringAction action = new FocusMeteringAction.Builder(
                    point, FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .setLockingMode(FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .disableAutoCancel()
                    .build();
            mCamera.getCameraControl().startFocusAndMetering(action)
                    .addListener(() -> mListener.onFocusResult(true), mExecutor);
        } catch (Throwable t) {
            Log.d(TAG, "AE/AF lock failed", t);
            mListener.onFocusResult(false);
        }
    }

    public void unlockFocusAndExposure() {
        if (mCamera == null) {
            return;
        }
        try {
            mCamera.getCameraControl().cancelFocusAndMetering();
        } catch (Throwable t) {
            Log.d(TAG, "AE/AF unlock failed", t);
        }
    }

    public void takePhoto() {
        if (mImageCapture == null || !mBound) {
            mListener.onPhotoSaved(null, false);
            return;
        }
        // a real vendor night pipeline does its own stacking, one shot is enough there
        if (MODE_NIGHT.equals(mMode) && mBoundExtension == ExtensionMode.NONE) {
            mListener.onCaptureStarted();
            takeNightFrame(mImageCapture, new ArrayList<>());
            return;
        }
        mListener.onCaptureStarted();
        final String aspectRatio = mAspectRatio;
        final int jpegQuality = mPhotoQuality;
        mImageCapture.takePicture(mCaptureExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                int rotation = image.getImageInfo().getRotationDegrees();
                boolean mirror = mLens == Lens.FRONT && PetalCamPrefs.mirrorFront(mContext);
                boolean watermark = PetalCamPrefs.watermark(mContext);
                double[] loc = PetalCamPrefs.location(mContext)
                        ? LocationProvider.lastKnown(mContext) : null;
                Double lat = loc != null ? loc[0] : null;
                Double lon = loc != null ? loc[1] : null;
                // ultrawide sensor is the noisy one, lean on the denoiser hardest
                float denoise = mLens == Lens.FRONT
                        ? 0.4f
                        : (mPendingZoom < 0.95f ? 1f : 0.45f);
                boolean portrait = MODE_PORTRAIT.equals(mMode);
                MediaSaver.Result result = MediaSaver.saveImage(mContext, image, mirror,
                        watermark, lat, lon, rotation, portrait, denoise, aspectRatio,
                        jpegQuality, buildCaptureMeta(),
                        mExecutor, mListener::onPhotoProcessed);
                mExecutor.execute(() -> mListener.onPhotoSaved(result.uri, result.ok));
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "Capture failed", exception);
                mExecutor.execute(() -> mListener.onPhotoSaved(null, false));
            }
        });
    }

    private void takeNightFrame(ImageCapture capture, List<byte[]> frames) {
        capture.takePicture(mCaptureExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                int rotation = image.getImageInfo().getRotationDegrees();
                byte[] jpeg = MediaSaver.toJpegBytes(image);
                if (jpeg != null) {
                    frames.add(jpeg);
                }
                if (frames.size() < NIGHT_FRAMES && jpeg != null) {
                    takeNightFrame(capture, frames);
                } else {
                    finishNight(frames, rotation);
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "Night frame failed", exception);
                // whatever we already got is still a photo
                finishNight(frames, 0);
            }
        });
    }

    private void finishNight(List<byte[]> frames, int rotation) {
        if (frames.isEmpty()) {
            mExecutor.execute(() -> mListener.onPhotoSaved(null, false));
            return;
        }
        final int quality = mPhotoQuality;
        byte[] merged = NightStacker.merge(frames, quality);
        byte[] jpeg = merged != null ? merged : frames.get(0);
        boolean mirror = mLens == Lens.FRONT && PetalCamPrefs.mirrorFront(mContext);
        double[] loc = PetalCamPrefs.location(mContext)
                ? LocationProvider.lastKnown(mContext) : null;
        MediaSaver.Result result = MediaSaver.saveJpeg(mContext, jpeg, mirror,
                PetalCamPrefs.watermark(mContext), loc != null ? loc[0] : null,
                loc != null ? loc[1] : null, rotation, false, NIGHT_DENOISE, mAspectRatio,
                quality, buildCaptureMeta(), mExecutor, mListener::onPhotoProcessed);
        mExecutor.execute(() -> mListener.onPhotoSaved(result.uri, result.ok));
    }

    /** Snapshot of the lens + shot details MediaSaver should stamp into Exif. */
    private MediaSaver.Meta buildCaptureMeta() {
        MediaSaver.Meta meta = new MediaSaver.Meta();
        meta.lens = lensTag();
        meta.mode = mMode;
        meta.zoom = mPendingZoom;
        meta.lensMake = Build.MANUFACTURER != null ? Build.MANUFACTURER : "";

        CameraCharacteristics c = characteristics();
        if (c != null) {
            try {
                float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                if (focal != null && focal.length > 0) {
                    meta.focalLength = focal[0];
                }
                float[] apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
                if (apertures != null && apertures.length > 0) {
                    meta.aperture = apertures[0];
                }
                android.util.SizeF sensor =
                        c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                if (sensor != null && meta.focalLength > 0f) {
                    double diagonal = Math.hypot(sensor.getWidth(), sensor.getHeight());
                    if (diagonal > 0.01) {
                        meta.focalLength35 =
                                (float) (meta.focalLength * 43.266 / diagonal);
                    }
                }
            } catch (Throwable t) {
                Log.d(TAG, "Lens metadata unavailable", t);
            }
        }

        StringBuilder lens = new StringBuilder(lensTag());
        if (meta.focalLength > 0f) {
            lens.append(' ').append(Math.round(meta.focalLength)).append("mm");
        }
        if (meta.aperture > 0f) {
            lens.append(" f/").append(String.format(Locale.US, "%.1f", meta.aperture));
        }
        meta.lensModel = lens.toString();
        return meta;
    }

    public void startRecording() {
        if (mVideoCapture == null || !mBound || mRecording != null) {
            return;
        }
        try {
            String name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".mp4";
            ContentResolver resolver = mContext.getContentResolver();
            MediaStoreOutputOptions options = new MediaStoreOutputOptions.Builder(
                    resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    .setContentValues(MediaSaver.videoValues(name))
                    .build();

            double[] loc = PetalCamPrefs.location(mContext)
                    ? LocationProvider.lastKnown(mContext) : null;
            PendingRecording pending = mVideoCapture.getOutput()
                    .prepareRecording(mContext, options);
            // persistent so the recording outlives the rebind of a lens flip
            pending = pending.withAudioEnabled().asPersistentRecording();

            mRecording = pending.start(mExecutor, event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    mListener.onRecordingStarted();
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                    boolean ok = fin.getError() == VideoRecordEvent.Finalize.ERROR_NONE;
                    // let go before the first frame, nothing happened, not a failure
                    boolean empty = fin.getError() == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA;
                    Uri uri = ok ? fin.getOutputResults().getOutputUri() : null;
                    mRecording = null;
                    mListener.onRecordingStopped(uri, ok || empty);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Recording failed to start", t);
            mRecording = null;
            mListener.onRecordingStopped(null, false);
        }
    }

    public void stopRecording() {
        if (mRecording != null) {
            try {
                mRecording.stop();
            } catch (Throwable t) {
                Log.d(TAG, "Stop recording failed", t);
            }
        }
    }

    public void release() {
        mReleased = true;
        mMain.removeCallbacks(mEngineGaveUp);
        if (mRecording != null) {
            try {
                mRecording.close();
            } catch (Throwable ignored) {
            }
            mRecording = null;
        }
        if (mProvider != null) {
            mProvider.unbindAll();
        }
        mCaptureExecutor.shutdown();
        mAnalysisExecutor.shutdown();
        mBound = false;
    }
}
