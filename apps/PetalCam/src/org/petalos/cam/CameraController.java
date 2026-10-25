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

/**
 * CameraX orchestration. Owns the process camera provider, rebinds use cases
 * when the user changes lens or mode, and routes through the OEM extension
 * implementation when "Petal Engine" is on.
 */
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

    private boolean mBound;

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

    /** Resolve the provider, probe vendor bits, then bind the current mode. */
    public void initialize() {
        mVendorInfo = VendorCameraBridge.detect(mContext);

        // Best-effort vendor SDK bring-up; null means "sidestep and use CameraX".
        Object vendorHandle = VendorCameraBridge.tryOpenVendorCameraUnit(mContext);
        Log.i(TAG, "Vendor camera unit: " + (vendorHandle != null ? "opened" : "skipped"));

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
            if (PetalCamPrefs.petalEngine(mContext)) {
                mEngine.initialize(mContext, mProvider, mExecutor, available -> {
                    mEngineReady = available;
                    mListener.onVendorInfo(mVendorInfo, mEngine.status());
                    bindCurrentMode(true);
                });
            } else {
                mEngineReady = false;
                mListener.onVendorInfo(mVendorInfo, "disabled");
                bindCurrentMode(true);
            }
        }, mExecutor);
    }

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
        if (mMode.equals(mode)) {
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
        // below 1x we need the physical wide cam, and oem extensions tend to
        // clamp zoom at 1.0, so drop them until we are back on the main lens
        boolean wantUltrawide = mPendingZoom < 0.95f;
        int ext = mEngineReady && PetalCamPrefs.petalEngine(mContext)
                ? extensionModeFor(mMode) : ExtensionMode.NONE;
        if (wantUltrawide && ext != ExtensionMode.NONE) {
            Log.i(TAG, "zoom " + mPendingZoom + " wants the wide cam, dropping extension");
            ext = ExtensionMode.NONE;
        }
        mBoundUltrawide = wantUltrawide;
        if (ext != ExtensionMode.NONE) {
            selector = mEngine.selectorFor(base, lensTag(),
                    ext == ExtensionMode.BOKEH ? "Bokeh" : "Night", ext);
        } else if (!wantUltrawide && mMode.equals(MODE_PHOTO) && mHdrEnabled && mEngineReady
                && PetalCamPrefs.petalEngine(mContext)
                && mEngine.isModeAvailable(lensTag(), "HDR")) {
            // Photo mode leans on the OEM HDR pipeline when it exists.
            selector = mEngine.selectorFor(base, lensTag(), "HDR", ExtensionMode.HDR);
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

                if (PetalCamPrefs.vendorForce(mContext)) {
                    applyVendorHints(icb);
                }
                mImageCapture = icb.build();

                // live QR/barcode scanning alongside every still photo mode
                mImageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(new android.util.Size(1280, 720))
                        .build();
                mImageAnalysis.setAnalyzer(mAnalysisExecutor, mQrAnalyzer);

                mCamera = mProvider.bindToLifecycle(mLifecycleOwner, selector, mPreview,
                        mImageCapture, mImageAnalysis);
            }

            mBound = true;
            applyZoom(mPendingZoom);
            logBoundCamera();
            scheduleZoomVerify();
            mCamera.getCameraControl().enableTorch(mTorch);
            mListener.onCameraReady(buildInfo());
        } catch (Throwable t) {
            Log.e(TAG, "Bind failed", t);
            mBound = false;
            mListener.onCameraError("Could not start camera");
        }
    }

    /**
     * Attach known vendor capture keys for photo capture on OEM HALs. Only runs
     * when the user has forced the vendor pipeline, and only writes keys the HAL
     * already advertises.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyVendorHints(ImageCapture.Builder builder) {
        try {
            CameraCharacteristics chars = characteristicsForLens();
            if (chars == null) {
                return;
            }
            boolean night = MODE_NIGHT.equals(mMode);
            boolean hdr = MODE_PHOTO.equals(mMode);
            java.util.Map<CaptureRequest.Key<?>, Object> options =
                    VendorCameraBridge.knownVendorOptions(chars, night, hdr);
            if (options.isEmpty()) {
                return;
            }
            Camera2Interop.Extender extender = new Camera2Interop.Extender(builder);
            for (java.util.Map.Entry<CaptureRequest.Key<?>, Object> e : options.entrySet()) {
                extender.setCaptureRequestOption((CaptureRequest.Key) e.getKey(), e.getValue());
            }
            Log.d(TAG, "Applied " + options.size() + " vendor capture key(s)");
        } catch (Throwable t) {
            Log.d(TAG, "Vendor hints unavailable", t);
        }
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
        if (mBound && wantUltrawide != mBoundUltrawide) {
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
            pending = pending.withAudioEnabled();

            mRecording = pending.start(mExecutor, event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    mListener.onRecordingStarted();
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                    boolean ok = fin.getError() == VideoRecordEvent.Finalize.ERROR_NONE;
                    Uri uri = ok ? fin.getOutputResults().getOutputUri() : null;
                    mRecording = null;
                    mListener.onRecordingStopped(uri, ok);
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
