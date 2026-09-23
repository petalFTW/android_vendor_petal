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

package org.petalos.agever.output;

import android.companion.virtualcamera.Format;
import android.companion.virtualcamera.ICaptureResultConsumer;
import android.companion.virtualcamera.IVirtualCameraCallback;
import android.companion.virtualcamera.IVirtualCameraService;
import android.companion.virtualcamera.LensFacing;
import android.companion.virtualcamera.SensorOrientation;
import android.companion.virtualcamera.SupportedStreamConfiguration;
import android.companion.virtualcamera.VirtualCameraConfiguration;
import android.companion.virtualcamera.VirtualCameraMetadata;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.ICameraService;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Sink that publishes the rendered avatar as a real front camera.
 *
 * <p>It talks to petalOS' {@code virtual_camera} provider (the AOSP virtual
 * camera HAL, enabled on petal builds) and registers a camera with
 * {@code deviceId = 0} — the default device — so every app, including
 * age-verification apps, sees it as an extra {@code LENS_FACING_FRONT} camera.
 *
 * <p>Registration alone would only <em>add</em> a camera; clients that simply
 * open "the" front camera would still get the real sensor. While the stage is
 * live, this sink therefore also arms CameraService's petal front-camera
 * override ({@code ICameraService.setPetalFrontCameraOverride}): every open of
 * any front camera — including plain camera-id picks — is redirected to the
 * avatar camera, and the real front camera cannot be opened by anyone but this
 * app (its UID is exempt so AR face-tracking keeps working). The override
 * persists in {@code persist.petal.camera_override}, so between arming and the
 * app re-arming after boot, the front camera fails closed instead of leaking.
 *
 * <p>Registration needs the {@code MANAGE_VIRTUAL_CAMERA} signature permission
 * (see {@link #PERMISSION}); the provider accepts it via a small petalOS patch
 * because the stock {@code CREATE_VIRTUAL_DEVICE} permission is internal|role
 * and is not grantable to a regular privileged app. The override call needs the
 * platform {@code CAMERA_INJECT_EXTERNAL_CAMERA} signature permission.
 *
 * <p>When a client app opens the camera, the provider calls
 * {@link IVirtualCameraCallback#onStreamConfigured} with a producer Surface.
 * That Surface is handed straight to the renderer, which draws the avatar into
 * it. The provider samples it and emits the client's requested camera streams.
 */
public final class ProviderFrameSink implements FrameSink {

    private static final String TAG = "AgeVerProviderSink";

    /** Signature permission the {@code virtual_camera} provider accepts. */
    public static final String PERMISSION =
            "org.petalos.agever.permission.MANAGE_VIRTUAL_CAMERA";

    /** Service instance name of the AOSP virtual camera provider. */
    public static final String SERVICE_NAME = "virtual_camera";

    /**
     * Binder name CameraService publishes itself under. Not
     * {@link Context#CAMERA_SERVICE} ("camera") — that is the CameraManager
     * system-service key, the real binder is "media.camera".
     */
    private static final String CAMERA_BINDER_NAME = "media.camera";

    private final Context mAppContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final IBinder mToken = new Binder();
    /** Guards mStopped / mOverrideArmed between the register thread and stop(). */
    private final Object mLifecycleLock = new Object();

    /** Process-wide override state, so the QS tile can mirror it. */
    private static final java.util.concurrent.atomic.AtomicBoolean sOverrideArmed =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** True while CameraService is redirecting the front camera to the avatar. */
    public static boolean isOverrideArmed() {
        return sOverrideArmed.get();
    }

    private Callback mCallback;
    private volatile IVirtualCameraService mService;
    private volatile boolean mActive;
    private boolean mStopped;
    private boolean mOverrideArmed;

    private final IVirtualCameraCallback.Stub mVirtualCallback =
            new IVirtualCameraCallback.Stub() {
                @Override
                public void onOpenCamera() {
                    Log.i(TAG, "client opened the virtual camera");
                }

                @Override
                public void onConfigureSession(VirtualCameraMetadata sessionParameters,
                        ICaptureResultConsumer captureResultConsumer) {
                    // Per-frame metadata is disabled; nothing to do.
                }

                @Override
                public void onStreamConfigured(int streamId, Surface surface, int width,
                        int height, int pixelFormat) {
                    Log.i(TAG, "stream " + streamId + " configured " + width + "x" + height);
                    mMain.post(() -> {
                        Callback cb = mCallback;
                        if (cb != null) {
                            cb.onSurfaceReady(surface, width, height);
                        }
                    });
                }

                @Override
                public void onProcessCaptureRequest(int streamId, int frameId,
                        VirtualCameraMetadata captureRequestSettings) {
                    // The renderer draws continuously; nothing to do per request.
                }

                @Override
                public void onStreamClosed(int streamId) {
                    mMain.post(() -> {
                        Callback cb = mCallback;
                        if (cb != null) {
                            cb.onSurfaceLost();
                        }
                    });
                }
            };

    public ProviderFrameSink(@NonNull Context context) {
        mAppContext = context.getApplicationContext();
    }

    /** Whether this build can publish a virtual camera (permission granted). */
    public static boolean isAvailable(@NonNull Context context) {
        return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void start(@NonNull Callback callback) {
        mCallback = callback;
        synchronized (mLifecycleLock) {
            mStopped = false;
            mOverrideArmed = false;
        }
        new Thread(this::registerAsync, "agever-vcam-register").start();
    }

    private void registerAsync() {
        // The petal front-camera override has two steps. Arming with an empty
        // camera id fails closed: from this instant no app can open any real
        // front camera (CameraService returns ERROR_DISABLED). Once the avatar
        // camera is registered and enumerated, we re-arm with its id and the
        // same open resolves to the avatar stage instead.
        boolean overrideSupported;
        synchronized (mLifecycleLock) {
            if (mStopped) {
                return;
            }
            overrideSupported = armOverride("");
            mOverrideArmed = overrideSupported;
        }
        try {
            IBinder binder = ServiceManager.waitForService(SERVICE_NAME);
            if (binder == null) {
                unavailable("virtual_camera service did not come up");
                return;
            }
            IVirtualCameraService service = IVirtualCameraService.Stub.asInterface(binder);
            if (service == null) {
                unavailable("cannot bind virtual_camera service");
                return;
            }
            synchronized (mLifecycleLock) {
                if (mStopped) {
                    return;
                }
                boolean ok = service.registerCamera(mToken, buildConfiguration(), 0);
                if (!ok) {
                    unavailable("provider rejected camera registration");
                    return;
                }
                mService = service;
                mActive = true;
            }
            if (overrideSupported) {
                String cameraId = service.getCameraId(mToken);
                if (cameraId == null || cameraId.isEmpty()) {
                    // Keep the fail-closed block armed; never fall back to the
                    // real camera while the stage claims to be live.
                    unavailable("provider returned no camera id");
                    return;
                }
                if (!armReplacement(cameraId)) {
                    // Same: stay fail-closed and tell the stage the provider
                    // path did not come up.
                    unavailable("avatar backend never became usable");
                    return;
                }
                Log.i(TAG, "front camera replaced by " + cameraId);
            } else {
                Log.i(TAG, "registered extra front virtual camera (no override), deviceId=0");
            }
        } catch (RemoteException | RuntimeException e) {
            unavailable("registration failed: " + e.getMessage());
        }
    }

    /**
     * Calls {@code ICameraService.setPetalFrontCameraOverride}. Returns false
     * when the ROM has no petal CameraService hooks, so callers can degrade to
     * the plain extra-camera behaviour instead of failing.
     */
    private boolean armOverride(String cameraId) {
        try {
            ICameraService cameraService = ICameraService.Stub.asInterface(
                    ServiceManager.getService(CAMERA_BINDER_NAME));
            if (cameraService == null) {
                Log.w(TAG, "camera service not running yet");
                return false;
            }
            cameraService.setPetalFrontCameraOverride(true, cameraId);
            sOverrideArmed.set(true);
            return true;
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "front-camera override unavailable", e);
            return false;
        }
    }

    /**
     * Arms the override with the concrete avatar camera id. CameraService
     * validates the backend (must be a present, usable, front-facing
     * {@code v0_*} virtual camera), so retry briefly while the provider's
     * {@code cameraDeviceStatusChange(PRESENT)} is still propagating.
     */
    private boolean armReplacement(String cameraId) {
        for (int attempt = 0; attempt < 30; attempt++) {
            synchronized (mLifecycleLock) {
                if (mStopped) {
                    return false;
                }
                try {
                    ICameraService cameraService = ICameraService.Stub.asInterface(
                            ServiceManager.getService(CAMERA_BINDER_NAME));
                    if (cameraService == null) {
                        throw new IllegalStateException("camera service not running");
                    }
                    cameraService.setPetalFrontCameraOverride(true, cameraId);
                    mOverrideArmed = true;
                    sOverrideArmed.set(true);
                    return true;
                } catch (SecurityException e) {
                    Log.w(TAG, "front-camera override denied", e);
                    return false;
                } catch (RemoteException | RuntimeException e) {
                    // CameraService has not enumerated the new camera yet.
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private VirtualCameraConfiguration buildConfiguration() {
        VirtualCameraConfiguration config = new VirtualCameraConfiguration();
        // Cover both 4:3 and 16:9 up to 1080p so any common camera request
        // satisfies the provider's resolution/aspect matching.
        config.supportedStreamConfigs = new SupportedStreamConfiguration[]{
                stream(640, 480, Format.RGBA_8888, 30),
                stream(1280, 960, Format.RGBA_8888, 30),
                stream(1280, 720, Format.RGBA_8888, 30),
                stream(1920, 1080, Format.RGBA_8888, 30),
        };
        config.virtualCameraCallback = mVirtualCallback;
        // The avatar is rendered upright in portrait, so ask clients not to
        // rotate the buffers.
        config.sensorOrientation = SensorOrientation.ORIENTATION_0;
        config.lensFacing = LensFacing.FRONT;
        config.perFrameCameraMetadataEnabled = false;
        config.cameraCharacteristics = null;
        return config;
    }

    private static SupportedStreamConfiguration stream(int width, int height, int format,
            int maxFps) {
        SupportedStreamConfiguration c = new SupportedStreamConfiguration();
        c.width = width;
        c.height = height;
        c.pixelFormat = format;
        c.maxFps = maxFps;
        return c;
    }

    private void unavailable(String reason) {
        Log.w(TAG, reason);
        mMain.post(() -> {
            Callback cb = mCallback;
            if (cb != null) {
                cb.onSurfaceLost();
            }
        });
    }

    @Override
    public void stop() {
        mCallback = null;
        synchronized (mLifecycleLock) {
            mStopped = true;
            if (mOverrideArmed) {
                try {
                    ICameraService cameraService = ICameraService.Stub.asInterface(
                            ServiceManager.getService(CAMERA_BINDER_NAME));
                    if (cameraService != null) {
                        cameraService.setPetalFrontCameraOverride(false, "");
                    }
                    mOverrideArmed = false;
                } catch (RemoteException | RuntimeException e) {
                    Log.w(TAG, "disarming front-camera override failed", e);
                }
            }
        }
        IVirtualCameraService service = mService;
        sOverrideArmed.set(false);
        if (mActive && service != null) {
            try {
                service.unregisterCamera(mToken);
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "unregisterCamera failed", e);
            }
        }
        mActive = false;
        mService = null;
    }

    @Override
    public boolean isActive() {
        return mActive;
    }

    @NonNull
    @Override
    public String name() {
        return "camera-provider";
    }

    @Nullable
    public Callback callback() {
        return mCallback;
    }
}
