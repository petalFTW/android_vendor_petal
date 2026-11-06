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
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// works out what oem camera bits exist, all of it optional. no blobs, plain camerax
public final class VendorCameraBridge {

    private static final String TAG = "PetalCamVendor";

    private static final String OPLUS_CAMERA_UNIT_IMPL = "com.oplus.ocs.camera.CameraUnitImpl";
    private static final String COLOROS_CAMERA_UNIT_IMPL =
            "com.coloros.ocs.camera.impl.CameraUnitImpl";
    private static final String OPLUS_CAMERA_UNIT_IFACE =
            "com.oplus.ocs.camera.appinterface.CameraUnitInterface";
    private static final String OPLUS_CAMERA_PACKAGE = "com.oplus.camera";

    private VendorCameraBridge() {
    }

    @NonNull
    public static VendorCameraInfo detect(@NonNull Context context) {
        boolean oplusUnit = classPresent(OPLUS_CAMERA_UNIT_IMPL)
                || classPresent(COLOROS_CAMERA_UNIT_IMPL)
                || classPresent(OPLUS_CAMERA_UNIT_IFACE);
        boolean oplusApp = packageInstalled(context, OPLUS_CAMERA_PACKAGE);

        int keyCount = 0;
        Set<String> prefixes = new LinkedHashSet<>();
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cm != null) {
            try {
                for (String id : cm.getCameraIdList()) {
                    CameraCharacteristics c = cm.getCameraCharacteristics(id);
                    List<CaptureRequest.Key<?>> keys = Camera2VendorKeys.vendorRequestKeys(c);
                    keyCount += keys.size();
                    for (CaptureRequest.Key<?> k : keys) {
                        String p = prefixOf(k.getName());
                        if (p != null) {
                            prefixes.add(p);
                        }
                    }
                }
            } catch (CameraAccessException | RuntimeException e) {
                Log.w(TAG, "Camera probe failed", e);
            }
        }

        String vendorName;
        if (oplusUnit) {
            vendorName = "OPlus CameraUnit SDK";
        } else if (oplusApp) {
            vendorName = "OPlus camera blobs";
        } else if (keyCount > 0 && !prefixes.isEmpty()) {
            vendorName = new ArrayList<>(prefixes).get(0) + " HAL";
        } else {
            vendorName = "None";
        }

        return new VendorCameraInfo(
                safe(Build.MANUFACTURER), safe(Build.BRAND), safe(Build.DEVICE),
                oplusUnit, oplusApp, keyCount, new ArrayList<>(prefixes), vendorName);
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean packageInstalled(Context context, String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static String prefixOf(String key) {
        int dot = key.indexOf('.');
        if (dot < 0) {
            return null;
        }
        int second = key.indexOf('.', dot + 1);
        if (second < 0) {
            return key.substring(0, dot + 1);
        }
        return key.substring(0, second + 1);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
