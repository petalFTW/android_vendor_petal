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

import java.util.Collections;
import java.util.List;

/** Immutable snapshot of the proprietary camera stack PetalCam found on boot. */
public final class VendorCameraInfo {

    public final String manufacturer;
    public final String brand;
    public final String device;

    /** True when the OPlus CameraUnit SDK classes are present. */
    public final boolean oplusCameraUnit;
    /** True when com.oplus.camera (the stock OPlus camera) is installed. */
    public final boolean oplusCameraApp;
    /** Number of Camera2 vendor capture keys the HAL advertises. */
    public final int vendorKeyCount;
    /** Vendor tag prefixes observed (e.g. "com.oplus."). */
    public final List<String> vendorPrefixes;
    /** Human readable name of the best vendor library we found, or "None". */
    public final String vendorName;

    public VendorCameraInfo(String manufacturer, String brand, String device,
            boolean oplusCameraUnit, boolean oplusCameraApp, int vendorKeyCount,
            List<String> vendorPrefixes, String vendorName) {
        this.manufacturer = manufacturer;
        this.brand = brand;
        this.device = device;
        this.oplusCameraUnit = oplusCameraUnit;
        this.oplusCameraApp = oplusCameraApp;
        this.vendorKeyCount = vendorKeyCount;
        this.vendorPrefixes = Collections.unmodifiableList(vendorPrefixes);
        this.vendorName = vendorName;
    }

    public String summary() {
        if (oplusCameraUnit) {
            return "OPlus CameraUnit SDK";
        }
        if (oplusCameraApp) {
            return "OPlus camera blobs";
        }
        if (vendorKeyCount > 0) {
            return "Vendor HAL tags (" + vendorKeyCount + ")";
        }
        return "None (stock CameraX)";
    }
}
