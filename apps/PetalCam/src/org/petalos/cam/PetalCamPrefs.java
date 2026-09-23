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

package org.petalos.cam;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import androidx.preference.PreferenceManager;

/** Typed access to PetalCam's preferences, backed by the shared preference file. */
public final class PetalCamPrefs {

    public static final String KEY_PETAL_ENGINE = "petal_engine";
    public static final String KEY_SHUTTER_SOUND = "shutter_sound";
    public static final String KEY_WATERMARK = "watermark";
    public static final String KEY_LOCATION = "location";
    public static final String KEY_VENDOR_FORCE = "vendor_force";
    public static final String KEY_AUTO_ROTATE = "auto_rotate";
    public static final String KEY_VOLUME_SHUTTER = "volume_shutter";

    private PetalCamPrefs() {
    }

    private static SharedPreferences prefs(Context c) {
        return PreferenceManager.getDefaultSharedPreferences(c);
    }

    public static boolean petalEngine(Context c) {
        return prefs(c).getBoolean(KEY_PETAL_ENGINE, true);
    }

    // selfies are always saved the way the preview looked, no toggle for this
    public static boolean mirrorFront(Context c) {
        return true;
    }

    public static boolean shutterSound(Context c) {
        return prefs(c).getBoolean(KEY_SHUTTER_SOUND, true);
    }

    /** Vol-up as a shutter. On unless the user turns it off. */
    public static boolean volumeShutter(Context c) {
        return prefs(c).getBoolean(KEY_VOLUME_SHUTTER, true);
    }

    public static boolean watermark(Context c) {
        return prefs(c).getBoolean(KEY_WATERMARK, false);
    }

    public static boolean location(Context c) {
        return prefs(c).getBoolean(KEY_LOCATION, false);
    }

    public static boolean vendorForce(Context c) {
        return prefs(c).getBoolean(KEY_VENDOR_FORCE, false);
    }

    /** Whether the viewfinder follows the device orientation. Off by default. */
    public static boolean autoRotate(Context c) {
        return prefs(c).getBoolean(KEY_AUTO_ROTATE, false);
    }

    public static void setAutoRotate(Context c, boolean enabled) {
        prefs(c).edit().putBoolean(KEY_AUTO_ROTATE, enabled).apply();
    }

    /** Grid index stored in Settings.System so it can be themed/transferred. */
    public static int gridMode(Context c) {
        return Settings.System.getInt(c.getContentResolver(), "petal_cam_grid", 1);
    }

    public static void setGridMode(Context c, int mode) {
        Settings.System.putInt(c.getContentResolver(), "petal_cam_grid", mode);
    }

    /** Last used still-image mode index. */
    public static String lastMode(Context c) {
        return Settings.System.getString(c.getContentResolver(), "petal_cam_mode");
    }

    public static void setLastMode(Context c, String mode) {
        Settings.System.putString(c.getContentResolver(), "petal_cam_mode", mode);
    }
}
