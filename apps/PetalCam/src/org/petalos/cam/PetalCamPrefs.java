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

import java.util.List;

import androidx.preference.PreferenceManager;

/** Typed access to PetalCam's preferences, backed by the shared preference file. */
public final class PetalCamPrefs {

    public static final String KEY_SHUTTER_SOUND = "shutter_sound";
    public static final String KEY_WATERMARK = "watermark";
    public static final String KEY_LOCATION = "location";
    public static final String KEY_AUTO_ROTATE = "auto_rotate";
    public static final String KEY_VOLUME_SHUTTER = "volume_shutter";
    public static final String KEY_ASPECT_RATIO = "aspect_ratio";
    public static final String KEY_PHOTO_QUALITY = "photo_quality";
    public static final String KEY_VIDEO_QUALITY = "video_quality";

    private PetalCamPrefs() {
    }

    private static SharedPreferences prefs(Context c) {
        return PreferenceManager.getDefaultSharedPreferences(c);
    }

    // two crashes in a row on this build and we leave the oem stack alone for one launch.
    // force-stops and quick exits used to count here, and FINGERPRINT never changes on us
    public static boolean engineStruckOut(Context c) {
        List<android.app.ApplicationExitInfo> exits = c.getSystemService(android.app.ActivityManager.class)
                .getHistoricalProcessExitReasons(null, 0, 2);
        if (exits.size() < 2) {
            return false;
        }
        for (android.app.ApplicationExitInfo exit : exits) {
            int reason = exit.getReason();
            boolean crashed = reason == android.app.ApplicationExitInfo.REASON_CRASH
                    || reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE;
            if (!crashed || exit.getTimestamp() < android.os.Build.TIME) {
                return false;
            }
        }
        return true;
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

    /** Whether the viewfinder follows the device orientation. Off by default. */
    public static boolean autoRotate(Context c) {
        return prefs(c).getBoolean(KEY_AUTO_ROTATE, false);
    }

    public static void setAutoRotate(Context c, boolean enabled) {
        prefs(c).edit().putBoolean(KEY_AUTO_ROTATE, enabled).apply();
    }

    public static String aspectRatio(Context c) {
        String value = prefs(c).getString(KEY_ASPECT_RATIO, "4:3");
        return "1:1".equals(value) || "16:9".equals(value) ? value : "4:3";
    }

    public static void setAspectRatio(Context c, String ratio) {
        prefs(c).edit().putString(KEY_ASPECT_RATIO, ratio).apply();
    }

    public static int photoJpegQuality(Context c) {
        switch (prefs(c).getString(KEY_PHOTO_QUALITY, "high")) {
            case "standard": return 85;
            case "saver": return 70;
            default: return 96;
        }
    }

    public static String videoQuality(Context c) {
        return prefs(c).getString(KEY_VIDEO_QUALITY, "fhd");
    }

    /** Grid index stored in Settings.System so it can be themed/transferred. */
    public static int gridMode(Context c) {
        return Settings.System.getInt(c.getContentResolver(), "petal_cam_grid", 1);
    }

    public static void setGridMode(Context c, int mode) {
        Settings.System.putInt(c.getContentResolver(), "petal_cam_grid", mode);
    }
}
