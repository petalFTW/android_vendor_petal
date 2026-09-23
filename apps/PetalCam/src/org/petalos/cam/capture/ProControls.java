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

package org.petalos.cam.capture;

import android.hardware.camera2.CaptureRequest;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.core.CameraControl;

/**
 * Manual exposure / focus / white-balance for PRO mode.
 *
 * <p>CameraX exposes the public Camera2 capture keys through
 * {@link Camera2CameraControl}, which accepts live updates. That means PRO
 * controls work on any device with a Camera2 HAL, whether the vendor blob is
 * Qualcomm, MediaTek or something we have never seen — no proprietary symbols
 * required.
 */
public final class ProControls {

    private static final String TAG = "PetalCamPro";

    private final Camera2CameraControl mControl;
    private boolean mHasIso;
    private boolean mHasShutter;
    private boolean mHasFocus;

    public ProControls(@NonNull CameraControl control) {
        mControl = Camera2CameraControl.from(control);
    }

    /** AE off + fixed sensitivity. */
    public void setIso(int iso) {
        try {
            CaptureRequestOptions options = new CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    .build();
            mControl.addCaptureRequestOptions(options);
            mHasIso = true;
        } catch (Throwable t) {
            Log.d(TAG, "ISO not settable", t);
        }
    }

    /** AE off + fixed exposure time in nanoseconds. */
    public void setShutterNs(long ns) {
        try {
            CaptureRequestOptions options = new CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
                    .build();
            mControl.addCaptureRequestOptions(options);
            mHasShutter = true;
        } catch (Throwable t) {
            Log.d(TAG, "Shutter not settable", t);
        }
    }

    /** AF off + fixed focal distance in dioptres (0 = infinity). */
    public void setFocusDiopters(float diopters) {
        try {
            CaptureRequestOptions options = new CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
                    .build();
            mControl.addCaptureRequestOptions(options);
            mHasFocus = true;
        } catch (Throwable t) {
            Log.d(TAG, "Focus not settable", t);
        }
    }

    /** AWB preset mode (see CameraMetadata.CONTROL_AWB_MODE_*). */
    public void setAwbMode(int mode) {
        try {
            CaptureRequestOptions options = new CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, mode)
                    .build();
            mControl.addCaptureRequestOptions(options);
        } catch (Throwable t) {
            Log.d(TAG, "AWB not settable", t);
        }
    }

    /** Return to full auto. */
    public void reset() {
        try {
            mControl.clearCaptureRequestOptions();
        } catch (Throwable t) {
            Log.d(TAG, "Reset failed", t);
        }
        mHasIso = false;
        mHasShutter = false;
        mHasFocus = false;
    }

    public boolean isManual() {
        return mHasIso || mHasShutter || mHasFocus;
    }
}
