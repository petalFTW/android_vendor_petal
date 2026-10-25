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

package org.petalos.agever.render;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.filament.Camera;
import com.google.android.filament.Engine;
import com.google.android.filament.Filament;
import com.google.android.filament.IndirectLight;
import com.google.android.filament.LightManager;
import com.google.android.filament.Renderer;
import com.google.android.filament.Scene;
import com.google.android.filament.SwapChain;
import com.google.android.filament.View;
import com.google.android.filament.Viewport;
import com.google.android.filament.gltfio.AssetLoader;
import com.google.android.filament.gltfio.FilamentAsset;
import com.google.android.filament.gltfio.Gltfio;
import com.google.android.filament.gltfio.ResourceLoader;
import com.google.android.filament.gltfio.UbershaderProvider;

import org.petalos.agever.core.ControlSnapshot;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// one Filament thread, shared by preview and camera output
public final class AvatarStage {

    private static final String TAG = "AgeVerStage";
    public interface Listener {
        void onStageReady();

        void onStageError(@NonNull String message);
        void onAvatarReady(String ref, int expressionMask);
        void onAvatarError(String message);
    }

    private HandlerThread mThread;
    private Handler mHandler;
    private Handler mMainHandler;
    private Listener mListener;

    private volatile boolean mRunning;
    private volatile ControlSnapshot mControl = ControlSnapshot.neutral();
    private volatile boolean mIdleMotion = true;
    private volatile boolean mMirror = true;
    private volatile Supplier<ControlSnapshot> mControlSource;
    private volatile int mFrameRate = 24;
    private double mNextFrameMs;
    private volatile int mAvatarRequest;

    // Filament objects (render thread only).
    private Engine mEngine;
    private Renderer mRenderer;
    private Scene mScene;
    private View mView;
    private Camera mCamera;
    private int mCameraEntity;
    private int mProjectionWidth = -1;
    private int mProjectionHeight = -1;
    private int mViewportWidth = -1;
    private int mViewportHeight = -1;
    private IndirectLight mIndirectLight;
    private AssetLoader mAssetLoader;
    private ResourceLoader mResourceLoader;
    private UbershaderProvider mMaterialProvider;
    private final List<Integer> mLightEntities = new ArrayList<>();

    private FilamentAsset mAsset;
    private AvatarRig mRig;
    private ByteBuffer mAssetBuffer;

    private final java.util.Map<String, Target> mTargets = new java.util.LinkedHashMap<>();

    /** A render destination: the in-app preview and/or the camera-provider. */
    private static final class Target {
        Surface surface;
        int width;
        int height;
        SwapChain swapChain;
    }

    private long mStartNanos;

    private final Renderer.ClearOptions mClearOptions = new Renderer.ClearOptions();

    private final Runnable mFrame = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) {
                return;
            }
            try {
                renderFrame();
            } catch (RuntimeException | OutOfMemoryError e) {
                mRunning = false;
                postError("Rendering stopped: " + e.getMessage());
            }
            if (mRunning && mHandler != null && mAsset != null && !mTargets.isEmpty()) {
                long now = android.os.SystemClock.uptimeMillis();
                double interval = 1000.0 / mFrameRate;
                mNextFrameMs = Math.max(mNextFrameMs + interval, now + 1);
                mHandler.postAtTime(this, (long) Math.ceil(mNextFrameMs));
            }
        }
    };

    public void start(@NonNull Listener listener) {
        if (mThread != null) {
            return;
        }
        mListener = listener;
        mMainHandler = new Handler(Looper.getMainLooper());
        mThread = new HandlerThread("agever-stage");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        mStartNanos = System.nanoTime();
        mHandler.post(() -> {
            try {
                initEngine();
                mRunning = true;
                scheduleFrame();
            } catch (Throwable e) {
                Log.e(TAG, "stage init failed", e);
                try { destroyEngine(); }
                catch (RuntimeException cleanup) { Log.w(TAG, "Stage cleanup failed", cleanup); }
                postError("Could not start the 3D stage: " + e.getMessage());
            }
        });
    }

    public void stop() {
        mListener = null;
        final Handler handler = mHandler;
        final HandlerThread thread = mThread;
        mHandler = null;
        mThread = null;
        if (handler == null) {
            if (thread != null) {
                thread.quitSafely();
            }
            return;
        }
        // Tear the engine down on the thread that created it, then quit.
        handler.post(() -> {
            mRunning = false;
            handler.removeCallbacksAndMessages(null);
            destroyEngine();
            if (thread != null) {
                thread.quitSafely();
            }
        });
    }

    public void setControl(@Nullable ControlSnapshot snapshot) {
        if (snapshot != null) {
            mControl = snapshot;
        }
    }

    public void setControlSource(Supplier<ControlSnapshot> source) { mControlSource = source; }
    public void setFrameRate(int fps) { mFrameRate = fps == 30 ? 30 : 24; }

    private void scheduleFrame() {
        if (!mRunning || mHandler == null || mTargets.isEmpty()) return;
        mHandler.removeCallbacks(mFrame);
        mNextFrameMs = android.os.SystemClock.uptimeMillis();
        mHandler.post(mFrame);
    }

    public void setMirror(boolean mirror) {
        mMirror = mirror;
        final Handler handler = mHandler;
        if (handler != null) {
            handler.post(() -> {
                if (mView != null && mEngine != null && mEngine.isValidView(mView)) {
                    mView.setFrontFaceWindingInverted(mirror);
                }
            });
        }
    }

    public void setIdleMotion(boolean idle) {
        mIdleMotion = idle;
    }

    public void setSurface(@NonNull String id, @Nullable Surface surface, int width, int height) {
        final Handler handler = mHandler;
        if (handler == null) {
            return;
        }
        handler.post(() -> {
            if (surface == null || !surface.isValid()) {
                removeTarget(id);
                return;
            }
            Target target = mTargets.get(id);
            if (target != null && target.surface == surface && target.width == width
                    && target.height == height && target.swapChain != null) return;
            if (target == null) {
                target = new Target();
                mTargets.put(id, target);
            }
            target.surface = surface;
            target.width = width;
            target.height = height;
            rebuildSwapChain(target);
            scheduleFrame();
        });
    }

    public void clearSurface(@NonNull String id) {
        final Handler handler = mHandler;
        if (handler == null) {
            return;
        }
        handler.post(() -> removeTarget(id));
    }

    /** Loads a GLB / glTF-binary avatar. {@code bytes} is retained until replaced. */
    public void setAvatar(@NonNull byte[] bytes, @NonNull String name) {
        final Handler handler = mHandler;
        if (handler == null) {
            return;
        }
        final int request = ++mAvatarRequest;
        handler.post(() -> {
            if (request == mAvatarRequest) loadAvatar(bytes, name, request);
        });
    }

    public boolean isRunning() {
        return mRunning;
    }

    // ------------------------------------------------------------ render thread

    private void initEngine() {
        // poke the JNI loaders before Filament gets grumpy
        Filament.init();
        Gltfio.init();
        mEngine = Engine.create();
        mRenderer = mEngine.createRenderer();
        mScene = mEngine.createScene();
        mView = mEngine.createView();
        mView.setScene(mScene);

        mCameraEntity = mEngine.getEntityManager().create();
        mCamera = mEngine.createCamera(mCameraEntity);
        applyCamera(0, 0);
        mView.setCamera(mCamera);

        setupAmbient();
        setupLights();

        mClearOptions.clear = true;
        mClearOptions.clearColor = new double[]{1.0, 1.0, 1.0, 1.0};
        mRenderer.setClearOptions(mClearOptions);

        mView.setPostProcessingEnabled(true);
        mView.setAntiAliasing(View.AntiAliasing.FXAA);
        mView.setFrontFaceWindingInverted(mMirror);

        mMaterialProvider = new UbershaderProvider(mEngine);
        mAssetLoader = new AssetLoader(mEngine, mMaterialProvider, mEngine.getEntityManager());
        mResourceLoader = new ResourceLoader(mEngine);

        postReady();
    }

    private void setupAmbient() {
        // constant ambient term keeps the stage evenly lit
        float[] sh = new float[27];
        sh[0] = 1.0f;
        sh[1] = 1.0f;
        sh[2] = 1.0f;
        mIndirectLight = new IndirectLight.Builder()
                .irradiance(3, sh)
                .intensity(9000f)
                .build(mEngine);
        mScene.setIndirectLight(mIndirectLight);
    }

    private void setupLights() {
        LightManager lm = mEngine.getLightManager();
        // Key
        addDirectional(lm, 1.0f, 0.98f, 0.96f, 30000f, -0.35f, -0.75f, -0.55f);
        // Fill
        addDirectional(lm, 0.86f, 0.9f, 1.0f, 12000f, 0.6f, -0.35f, -0.4f);
        // Rim
        addDirectional(lm, 0.95f, 0.95f, 1.0f, 15000f, 0.1f, -0.2f, 0.98f);
    }

    private void addDirectional(LightManager lm, float r, float g, float b, float intensity,
            float dx, float dy, float dz) {
        int entity = mEngine.getEntityManager().create();
        new LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(r, g, b)
                .intensity(intensity)
                .direction(dx, dy, dz)
                .castShadows(false)
                .build(mEngine, entity);
        mScene.addEntity(entity);
        mLightEntities.add(entity);
    }

    private void applyCamera(int width, int height) {
        if (mProjectionWidth == width && mProjectionHeight == height) return;
        mProjectionWidth = width;
        mProjectionHeight = height;
        double aspect = (width > 0 && height > 0) ? (double) width / (double) height : 1.0;
        // Head-and-shoulders framing for the bundled avatar (head at y=1.70).
        mCamera.setProjection(36.0, aspect, 0.05, 50.0, Camera.Fov.VERTICAL);
        mCamera.lookAt(0.0, 1.50, 1.35, 0.0, 1.45, 0.0, 0.0, 1.0, 0.0);
    }

    private void rebuildSwapChain(Target target) {
        destroySwapChain(target);
        if (mEngine == null || !mEngine.isValid()
                || target.surface == null || !target.surface.isValid()) {
            return;
        }
        target.swapChain = mEngine.createSwapChain(target.surface);
    }

    private void destroySwapChain(Target target) {
        if (target.swapChain != null && mEngine != null && mEngine.isValid()) {
            mEngine.destroySwapChain(target.swapChain);
        }
        target.swapChain = null;
    }

    private void removeTarget(String id) {
        Target target = mTargets.remove(id);
        if (target != null) {
            destroySwapChain(target);
        }
    }

    private void loadAvatar(byte[] bytes, String name, int request) {
        if (mAssetLoader == null) {
            return;
        }
        FilamentAsset replacement = null;
        try {
            ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
            buffer.put(bytes);
            buffer.rewind();
            FilamentAsset asset = mAssetLoader.createAsset(buffer);
            if (asset == null) {
                postAvatarError("Could not parse avatar", request);
                return;
            }
            replacement = asset;
            // upload first, then drop the source buffer
            mResourceLoader.loadResources(asset);
            boolean renderable = false;
            for (int entity : asset.getEntities()) {
                if (mEngine.getRenderableManager().hasComponent(entity)) { renderable = true; break; }
            }
            if (!renderable) throw new IllegalArgumentException("Avatar has no renderable geometry");
            AvatarRig rig = new AvatarRig(mEngine, asset);
            if (request != mAvatarRequest) {
                mAssetLoader.destroyAsset(asset);
                return;
            }
            if (mAsset != null) {
                for (int e : mAsset.getEntities()) mScene.removeEntity(e);
                mAssetLoader.destroyAsset(mAsset);
            }
            for (int e : asset.getEntities()) {
                mScene.addEntity(e);
            }
            asset.releaseSourceData();
            mAsset = asset;
            mAssetBuffer = null;
            mRig = rig;
            scheduleFrame();
            replacement = null;
            int expressions = rig.expressionMask();
            mMainHandler.post(() -> {
                if (mListener != null && request == mAvatarRequest) mListener.onAvatarReady(name, expressions);
            });
            Log.i(TAG, "Avatar loaded (" + asset.getEntities().length + " entities)");
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.e(TAG, "avatar load failed", e);
            if (replacement != null) mAssetLoader.destroyAsset(replacement);
            postAvatarError("Could not load avatar: " + e.getMessage(), request);
        }
    }

    private void renderFrame() {
        if (mRenderer == null || mView == null || mEngine == null || !mEngine.isValid()) {
            return;
        }
        boolean any = false;
        for (Target target : mTargets.values()) {
            if (target.swapChain != null) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }
        long now = System.nanoTime();
        Supplier<ControlSnapshot> source = mControlSource;
        if (source != null) mControl = source.get();
        double timeSec = (now - mStartNanos) / 1_000_000_000.0;
        boolean applied = false;
        for (Target target : mTargets.values()) {
            if (target.swapChain == null) {
                continue;
            }
            if (!mRenderer.beginFrame(target.swapChain, now)) {
                continue;
            }
            // beginFrame allocates the transform storage, so pose it after that
            if (!applied) {
                AvatarRig rig = mRig;
                if (rig != null) {
                    rig.apply(mControl, mIdleMotion, timeSec, mMirror);
                }
                applied = true;
            }
            if (target.width > 0 && target.height > 0) {
                if (mViewportWidth != target.width || mViewportHeight != target.height) {
                    mView.setViewport(new Viewport(0, 0, target.width, target.height));
                    mViewportWidth = target.width;
                    mViewportHeight = target.height;
                }
                applyCamera(target.width, target.height);
            }
            mRenderer.render(mView);
            mRenderer.endFrame();
        }
    }

    private void destroyEngine() {
        for (Target target : mTargets.values()) {
            destroySwapChain(target);
        }
        mTargets.clear();
        mProjectionWidth = mProjectionHeight = mViewportWidth = mViewportHeight = -1;
        if (mAsset != null && mAssetLoader != null) {
            mAssetLoader.destroyAsset(mAsset);
            mAsset = null;
        }
        mAssetBuffer = null;
        mControlSource = null;
        mRig = null;
        if (mResourceLoader != null) {
            mResourceLoader.destroy();
            mResourceLoader = null;
        }
        if (mAssetLoader != null) {
            mAssetLoader.destroy();
            mAssetLoader = null;
        }
        if (mMaterialProvider != null) {
            mMaterialProvider.destroy();
            mMaterialProvider = null;
        }
        if (mEngine == null || !mEngine.isValid()) {
            mEngine = null;
            return;
        }
        for (int e : mLightEntities) {
            if (mEngine.getLightManager().hasComponent(e)) {
                mEngine.getLightManager().destroy(e);
            }
            mEngine.destroyEntity(e);
        }
        mLightEntities.clear();
        if (mIndirectLight != null) {
            mEngine.destroyIndirectLight(mIndirectLight);
            mIndirectLight = null;
        }
        if (mView != null) {
            mEngine.destroyView(mView);
            mView = null;
        }
        if (mCamera != null) {
            mEngine.destroyCameraComponent(mCameraEntity);
            mCamera = null;
        }
        if (mRenderer != null) {
            mEngine.destroyRenderer(mRenderer);
            mRenderer = null;
        }
        if (mScene != null) {
            mEngine.destroyScene(mScene);
            mScene = null;
        }
        mEngine.destroy();
        mEngine = null;
    }

    /** Alias for {@link #stop()}; teardown happens on the stage thread. */
    public void destroy() {
        stop();
    }

    private void postReady() {
        if (mMainHandler != null && mListener != null) {
            mMainHandler.post(() -> {
                if (mListener != null) {
                    mListener.onStageReady();
                }
            });
        }
    }

    private void postError(String message) {
        if (mMainHandler != null && mListener != null) {
            mMainHandler.post(() -> {
                if (mListener != null) {
                    mListener.onStageError(message);
                }
            });
        }
    }

    private void postAvatarError(String message, int request) {
        if (mMainHandler != null) mMainHandler.post(() -> {
            if (mListener != null && request == mAvatarRequest) mListener.onAvatarError(message);
        });
    }
}
