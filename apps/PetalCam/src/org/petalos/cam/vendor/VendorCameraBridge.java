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

/**
 * Vendor camera bridge.
 *
 * <p>petalOS is built for many devices with wildly different proprietary camera
 * blobs. Rather than link any single vendor's SDK (which would break every other
 * device), PetalCam probes at runtime and adapts:
 *
 * <ol>
 *   <li><b>CameraX extensions</b> (see {@link ExtensionBridge}) give hardware
 *       bokeh/HDR/night wherever the OEM ships an extension implementation.</li>
 *   <li><b>Vendor HAL tags</b> are discovered from Camera2 characteristics and
 *       echoed onto capture requests when a known-safe key exists.</li>
 *   <li><b>OPlus CameraUnit SDK</b> ({@code com.oplus.ocs.camera}) is detected
 *       reflectively. Its real entry point needs the stock camera's producer /
 *       consumer surfaces, so if it cannot be brought up cleanly we sidestep it
 *       entirely and stay on the CameraX path. This is deliberate: a camera that
 *       always launches beats a camera that sometimes talks to a half-initialised
 *       vendor pipeline.</li>
 * </ol>
 *
 * <p>Nothing here assumes a specific blob set; every call is guarded and falls
 * back to stock behaviour.
 */
public final class VendorCameraBridge {

    private static final String TAG = "PetalCamVendor";

    private static final String OPLUS_CAMERA_UNIT_IMPL = "com.oplus.ocs.camera.CameraUnitImpl";
    private static final String COLOROS_CAMERA_UNIT_IMPL =
            "com.coloros.ocs.camera.impl.CameraUnitImpl";
    private static final String OPLUS_CAMERA_UNIT_IFACE =
            "com.oplus.ocs.camera.appinterface.CameraUnitInterface";
    private static final String OPLUS_CAMERA_PACKAGE = "com.oplus.camera";

    /**
     * Known-safe boolean vendor toggles. Keys are matched exactly against the
     * HAL's advertised keys; unknown devices simply skip them.
     */
    private static final String[] NIGHT_KEYS = {
            "com.oplus.supernight.enable",
            "com.oplus.night.enable",
            "org.codeaurora.qcamera3.nightmode.enable",
    };

    private static final String[] HDR_KEYS = {
            "com.oplus.hdr.enable",
            "org.codeaurora.qcamera3.hdr.enable",
    };

    private VendorCameraBridge() {
    }

    /** One-shot probe of the device's proprietary camera stack. */
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

    /** Whether the OPlus CameraUnit SDK (or its ColorOS twin) is loadable. */
    public static boolean hasOplusCameraUnit() {
        return classPresent(OPLUS_CAMERA_UNIT_IMPL) || classPresent(COLOROS_CAMERA_UNIT_IMPL);
    }

    /**
     * Attempt to bring up the OPlus CameraUnit. Returns an opaque handle on
     * success, or {@code null} when the SDK is absent or refuses to initialise
     * without the stock camera's surfaces. Callers must treat null as "sidestep".
     */
    public static Object tryOpenVendorCameraUnit(@NonNull Context context) {
        String[] candidates = {OPLUS_CAMERA_UNIT_IMPL, COLOROS_CAMERA_UNIT_IMPL};
        for (String className : candidates) {
            try {
                Class<?> clazz = Class.forName(className);
                java.lang.reflect.Constructor<?> ctor = clazz.getDeclaredConstructor();
                ctor.setAccessible(true);
                Object instance = ctor.newInstance();
                java.lang.reflect.Method init = findMethod(clazz, "initialize",
                        Context.class, String.class);
                if (init != null) {
                    init.setAccessible(true);
                    init.invoke(instance, context, OPLUS_CAMERA_PACKAGE);
                }
                Log.i(TAG, "Vendor camera unit opened: " + className);
                return instance;
            } catch (Throwable t) {
                Log.d(TAG, "Vendor camera unit unavailable: " + className + " (" + t + ")");
            }
        }
        return null;
    }

    /**
     * Resolves whichever known vendor toggles the HAL advertises into a
     * key/value map that can be applied to a capture request builder (or a
     * CameraX Camera2Interop extender). Unknown keys and rejected values are
     * dropped. Returns an empty map on a stock HAL.
     */
    @NonNull
    public static java.util.Map<CaptureRequest.Key<?>, Object> knownVendorOptions(
            CameraCharacteristics characteristics, boolean night, boolean hdr) {
        java.util.Map<CaptureRequest.Key<?>, Object> out = new java.util.LinkedHashMap<>();
        if (characteristics == null) {
            return out;
        }
        try {
            java.util.List<CaptureRequest.Key<?>> available =
                    new ArrayList<>(characteristics.getAvailableCaptureRequestKeys());
            if (night) {
                collect(available, out, NIGHT_KEYS, Boolean.TRUE);
            }
            if (hdr) {
                collect(available, out, HDR_KEYS, Boolean.TRUE);
            }
        } catch (Throwable t) {
            Log.d(TAG, "Vendor option resolution failed", t);
        }
        return out;
    }

    private static void collect(java.util.List<CaptureRequest.Key<?>> available,
            java.util.Map<CaptureRequest.Key<?>, Object> out, String[] names, Object value) {
        for (CaptureRequest.Key<?> key : available) {
            for (String name : names) {
                if (name.equals(key.getName())) {
                    out.put(key, value);
                }
            }
        }
    }

    private static java.lang.reflect.Method findMethod(Class<?> clazz, String name,
            Class<?>... params) {
        try {
            return clazz.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            try {
                return clazz.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                return null;
            }
        }
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
