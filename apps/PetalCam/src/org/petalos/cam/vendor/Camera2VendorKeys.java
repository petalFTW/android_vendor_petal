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

// vendor tags, only ever the key objects the hal hands us
public final class Camera2VendorKeys {

    private static final String TAG = "PetalCamVendor";

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
        // anything else that smells vendor
        return name.contains(".vendor.") || name.contains("vendorTag");
    }

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

    public static int vendorKeyCount(CameraCharacteristics c) {
        return vendorRequestKeys(c).size();
    }

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
