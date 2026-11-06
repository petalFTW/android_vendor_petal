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

package org.petalos.cam.vendor;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraProvider;
import androidx.camera.core.CameraSelector;
import androidx.camera.extensions.ExtensionMode;
import androidx.camera.extensions.ExtensionsManager;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

// "Petal Engine". only does anything if the rom ships androidx.camera.extensions.impl
public final class ExtensionBridge {

    private static final String TAG = "PetalCamEngine";

    private final Map<String, Boolean> mAvailability = new LinkedHashMap<>();
    private ExtensionsManager mManager;
    private boolean mReady;
    private String mStatus = "initialising";

    public interface ReadyCallback {
        void onReady(boolean available);
    }

    public void initialize(@NonNull Context context, @NonNull CameraProvider provider,
            @NonNull Executor executor, ReadyCallback callback) {
        ListenableFuture<ExtensionsManager> future;
        try {
            future = ExtensionsManager.getInstanceAsync(context, provider);
        } catch (Throwable t) {
            Log.w(TAG, "Extensions manager unavailable", t);
            mStatus = "unavailable";
            callback.onReady(false);
            return;
        }
        future.addListener(() -> {
            try {
                mManager = future.get();
                mReady = true;
                mStatus = mManager == null ? "missing implementation" : "ready";
            } catch (Throwable t) {
                Log.w(TAG, "Extensions init failed", t);
                mReady = false;
                mStatus = "error";
            }
            callback.onReady(mReady);
        }, executor);
    }

    public boolean isReady() {
        return mReady;
    }

    public String status() {
        return mStatus;
    }

    public void probe(@NonNull CameraSelector base, String lensTag) {
        // used to clear the lot here, so probing the second lens binned the first
        if (!mReady) {
            return;
        }
        probeMode(base, lensTag, "Bokeh", ExtensionMode.BOKEH);
        probeMode(base, lensTag, "HDR", ExtensionMode.HDR);
        probeMode(base, lensTag, "Night", ExtensionMode.NIGHT);
        probeMode(base, lensTag, "Retouch", ExtensionMode.FACE_RETOUCH);
    }

    private void probeMode(CameraSelector base, String lensTag, String name, int mode) {
        try {
            boolean ok = mManager.isExtensionAvailable(base, mode);
            mAvailability.put(lensTag + ":" + name, ok);
        } catch (Throwable t) {
            Log.d(TAG, "Extension probe failed for " + name, t);
        }
    }

    /** not every oem pipeline takes an analysis stream, binding one anyway just throws */
    public boolean supportsAnalysis(CameraSelector base, int mode) {
        if (!mReady) {
            return true;
        }
        try {
            return mManager.isImageAnalysisSupported(base, mode);
        } catch (Throwable t) {
            Log.d(TAG, "Analysis support probe failed", t);
            return false;
        }
    }

    public boolean isModeAvailable(String lensTag, String name) {
        Boolean b = mAvailability.get(lensTag + ":" + name);
        return b != null && b;
    }

    public boolean hasAny() {
        for (Boolean b : mAvailability.values()) {
            if (b) {
                return true;
            }
        }
        return false;
    }

    public List<String> enabledModes(String lensTag) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : mAvailability.entrySet()) {
            if (e.getValue() && e.getKey().startsWith(lensTag + ":")) {
                out.add(e.getKey().substring((lensTag + ":").length()));
            }
        }
        return out;
    }

    // falls back to the plain selector if the mode isnt there
    public CameraSelector selectorFor(CameraSelector base, String lensTag, String name, int mode) {
        if (!mReady || !isModeAvailable(lensTag, name)) {
            return base;
        }
        try {
            return mManager.getExtensionEnabledCameraSelector(base, mode);
        } catch (Throwable t) {
            Log.w(TAG, "Could not build extension selector for " + name, t);
            return base;
        }
    }

    public Map<String, Boolean> snapshot() {
        return Collections.unmodifiableMap(mAvailability);
    }
}
