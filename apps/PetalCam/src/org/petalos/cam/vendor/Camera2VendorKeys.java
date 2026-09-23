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

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Camera2 vendor tag plumbing.
 *
 * <p>Every OEM camera HAL advertises extra {@link CaptureRequest.Key}s in
 * {@link CameraCharacteristics#getKeys()}. There is no public API to construct
 * such a key from its name, so we can only use the key objects the HAL hands us.
 * This helper finds them by name prefix and applies the ones the device reports
 * as available to a capture request, swallowing anything the HAL rejects. That
 * keeps PetalCam working on a blob set we have never seen.
 */
public final class Camera2VendorKeys {

    private static final String TAG = "PetalCamVendor";

    /** Vendor tag namespaces seen across the petalOS device family. */
    public static final String[] VENDOR_PREFIXES = {
            "com.oplus.", "com.coloros.", "oplus.",
            "com.qti.", "org.codeaurora.",
            "com.samsung.", "samsung.",
            "com.google.", "com.mediatek.", "mediatek.",
            "com.htc.", "com.lge.", "vendor.",
    };

    private Camera2VendorKeys() {
    }

    public static boolean isVendorKey(String name) {
        if (name == null) {
            return false;
        }
        for (String prefix : VENDOR_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        // Also catch any vendor tag the platform did not put in android.*
        return name.contains(".vendor.") || name.contains("vendorTag");
    }

    /** All vendor keys the HAL exposes for the given camera. */
    public static List<CaptureRequest.Key<?>> vendorRequestKeys(CameraCharacteristics c) {
        if (c == null) {
            return Collections.emptyList();
        }
        List<CaptureRequest.Key<?>> out = new ArrayList<>();
        try {
            for (CaptureRequest.Key<?> key : c.getAvailableCaptureRequestKeys()) {
                if (isVendorKey(key.getName())) {
                    out.add(key);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to enumerate vendor request keys", t);
        }
        return out;
    }

    /** Count of vendor keys, used for the settings diagnostics row. */
    public static int vendorKeyCount(CameraCharacteristics c) {
        return vendorRequestKeys(c).size();
    }

    /**
     * Best-effort: apply a boolean vendor option by exact key name if the HAL
     * advertises it. Returns true if a value was written.
     */
    @SuppressWarnings("unchecked")
    public static boolean applyBoolean(CaptureRequest.Builder builder, CameraCharacteristics c,
            String keyName, boolean value) {
        if (builder == null || c == null || keyName == null) {
            return false;
        }
        try {
            for (CaptureRequest.Key<?> key : c.getAvailableCaptureRequestKeys()) {
                if (!keyName.equals(key.getName())) {
                    continue;
                }
                builder.set((CaptureRequest.Key<Boolean>) key, value);
                return true;
            }
        } catch (Throwable t) {
            Log.d(TAG, "Vendor boolean key rejected: " + keyName, t);
        }
        return false;
    }

    /**
     * Best-effort: apply an int vendor option by exact key name. The HAL silently
     * rejects out-of-range values, so callers should keep a safe allowlist.
     */
    @SuppressWarnings("unchecked")
    public static boolean applyInt(CaptureRequest.Builder builder, CameraCharacteristics c,
            String keyName, int value) {
        if (builder == null || c == null || keyName == null) {
            return false;
        }
        try {
            for (CaptureRequest.Key<?> key : c.getAvailableCaptureRequestKeys()) {
                if (!keyName.equals(key.getName())) {
                    continue;
                }
                builder.set((CaptureRequest.Key<Integer>) key, value);
                return true;
            }
        } catch (Throwable t) {
            Log.d(TAG, "Vendor int key rejected: " + keyName, t);
        }
        return false;
    }

    /** Human readable dump, surfaced in the debug dialog. */
    public static String describe(CameraCharacteristics c, int limit) {
        List<CaptureRequest.Key<?>> keys = vendorRequestKeys(c);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(limit, keys.size()); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(keys.get(i).getName());
        }
        if (keys.size() > limit) {
            sb.append("\n\u2026 +").append(keys.size() - limit).append(" more");
        }
        if (sb.length() == 0) {
            sb.append("none");
        }
        return sb.toString();
    }
}
