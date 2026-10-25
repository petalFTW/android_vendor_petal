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

// publish synthetic frames, but ask CameraService before claiming anything is ready
public final class ProviderFrameSink implements FrameSink {

    private static final String TAG = "AgeVerProviderSink";

    /** Signature permission the {@code virtual_camera} provider accepts. */
    public static final String PERMISSION =
            "org.petalos.agever.permission.MANAGE_VIRTUAL_CAMERA";

    /** Service instance name of the AOSP virtual camera provider. */
    public static final String SERVICE_NAME = "virtual_camera";

    // the binder name isn't the Context service key, annoyingly
    private static final String CAMERA_BINDER_NAME = "media.camera";

    private final Context mAppContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final IBinder mToken = new Binder();
    /** Guards mStopped / mOverrideArmed between the register thread and stop(). */
    private final Object mLifecycleLock = new Object();

    /** True while CameraService is redirecting the front camera to the avatar. */
    public static boolean isOverrideArmed() {
        return queryOverrideState() > 0;
    }

    private Callback mCallback;
    private volatile IVirtualCameraService mService;
    private volatile boolean mActive;
    private boolean mStopped;
    private boolean mOverrideArmed;
    private int mCurrentStream = -1;
    private volatile StatusListener mStatusListener;
    private final IBinder.DeathRecipient mDeath = () -> unavailable("Camera provider disconnected");

    public interface StatusListener {
        void onReady(boolean replacementActive);
        void onFailure(String reason);
    }

    public void setStatusListener(StatusListener listener) { mStatusListener = listener; }

    public static int queryOverrideState() {
        try {
            ICameraService camera = ICameraService.Stub.asInterface(
                    ServiceManager.getService(CAMERA_BINDER_NAME));
            return camera == null ? -1 : camera.getPetalFrontCameraOverrideState();
        } catch (RemoteException | RuntimeException e) {
            return -1;
        }
    }

    public static boolean restoreCamera() {
        try {
            ICameraService camera = ICameraService.Stub.asInterface(
                    ServiceManager.getService(CAMERA_BINDER_NAME));
            if (camera == null) return false;
            if (camera.getPetalFrontCameraOverrideState() == 0) return true;
            camera.setPetalFrontCameraOverride(false, "");
            return camera.getPetalFrontCameraOverrideState() == 0;
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Could not restore front camera", e);
            return false;
        }
    }

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
                        synchronized (mLifecycleLock) { if (mStopped) return; }
                        mCurrentStream = streamId;
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
                        if (mCurrentStream != streamId) return;
                        mCurrentStream = -1;
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
        return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
                && ServiceManager.checkService(SERVICE_NAME) != null;
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
        // block first; registration alone doesn't replace the sensor
        boolean overrideSupported;
        synchronized (mLifecycleLock) {
            if (mStopped) {
                return;
            }
            overrideSupported = armOverride("");
            mOverrideArmed = overrideSupported;
        }
        try {
            IBinder binder = null;
            for (int attempt = 0; attempt < 30 && binder == null; attempt++) {
                synchronized (mLifecycleLock) { if (mStopped) return; }
                binder = ServiceManager.checkService(SERVICE_NAME);
                if (binder == null) Thread.sleep(100);
            }
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
                binder.linkToDeath(mDeath, 0);
            }
            if (overrideSupported) {
                String cameraId = service.getCameraId(mToken);
                if (cameraId == null || cameraId.isEmpty()) {
                    // broken backend stays blocked until the user stops it
                    unavailable("provider returned no camera id");
                    return;
                }
                if (!armReplacement(cameraId)) {
                    // don't quietly drop the block on this failure
                    unavailable("avatar backend never became usable");
                    return;
                }
                Log.i(TAG, "front camera replaced by " + cameraId);
            } else {
                Log.i(TAG, "registered extra front virtual camera (no override), deviceId=0");
            }
            mMain.post(() -> {
                synchronized (mLifecycleLock) { if (mStopped) return; }
                StatusListener listener = mStatusListener;
                if (listener != null) listener.onReady(overrideSupported);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            unavailable("Camera startup interrupted");
        } catch (RemoteException | RuntimeException e) {
            unavailable("registration failed: " + e.getMessage());
        }
    }

    // older ROMs may expose only an extra camera, say so
    private boolean armOverride(String cameraId) {
        try {
            ICameraService cameraService = ICameraService.Stub.asInterface(
                    ServiceManager.getService(CAMERA_BINDER_NAME));
            if (cameraService == null) {
                Log.w(TAG, "camera service not running yet");
                return false;
            }
            cameraService.setPetalFrontCameraOverride(true, cameraId);
            return true;
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "front-camera override unavailable", e);
            return false;
        }
    }

    // enumeration arrives late sometimes; keep retries short
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
            synchronized (mLifecycleLock) { if (mStopped) return; }
            StatusListener listener = mStatusListener;
            if (listener != null) listener.onFailure(reason);
            Callback cb = mCallback;
            if (cb != null) {
                cb.onSurfaceLost();
            }
        });
    }

    @Override
    public void stop() {
        mCallback = null;
        mStatusListener = null;
        synchronized (mLifecycleLock) {
            mStopped = true;
            if (mOverrideArmed) {
                try {
                    ICameraService cameraService = ICameraService.Stub.asInterface(
                            ServiceManager.getService(CAMERA_BINDER_NAME));
                    if (cameraService == null) throw new IllegalStateException("Camera service unavailable");
                    cameraService.setPetalFrontCameraOverride(false, "");
                    mOverrideArmed = queryOverrideState() != 0;
                } catch (RemoteException | RuntimeException e) {
                    Log.w(TAG, "disarming front-camera override failed", e);
                }
            }
        }
        IVirtualCameraService service = mService;
        if (mActive && service != null) {
            try {
                service.asBinder().unlinkToDeath(mDeath, 0);
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
