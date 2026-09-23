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

package org.petalos.config;

import android.content.Context;
import android.provider.Settings;

// shared with SystemUI. change these and stuff breaks
public final class PetalConfig {

    private PetalConfig() {}

    public static final String KEY_DITHERING_ENABLED = "petal_screen_dither_enabled";
    public static final String KEY_DITHERING_BATTERY_SAVER = "petal_screen_dither_battery_saver";
    public static final String KEY_DITHERING_SCHEDULE = "petal_screen_dither_schedule";
    public static final String KEY_DITHERING_START_MIN = "petal_screen_dither_start_min";
    public static final String KEY_DITHERING_END_MIN = "petal_screen_dither_end_min";
    public static final String KEY_DITHERING_PERCENT = "petal_screen_dither_percent";
    public static final int DEFAULT_DITHERING_PERCENT = 25;
    public static final int DEFAULT_DITHERING_START_MIN = 22 * 60;
    public static final int DEFAULT_DITHERING_END_MIN = 7 * 60;

    // dialog geometry, must match PetalUiConfig
    /** power menu side. 0 left, 1 right */
    public static final String KEY_POWER_EDGE = "petal_ui_power_edge";
    /** power menu y position, 0-100 */
    public static final String KEY_POWER_ANCHOR_PCT = "petal_ui_power_anchor_pct";
    /** 0 = volume hud left, 1 = right */
    public static final String KEY_VOLUME_EDGE = "petal_ui_volume_edge";
    /** volume hud y position, 0-100 */
    public static final String KEY_VOLUME_ANCHOR_PCT = "petal_ui_volume_anchor_pct";

    /** bezel bend edge, 0 left / 1 right */
    public static final String KEY_EXTRA_KEY_EDGE = "petal_ui_extra_key_edge";
    /** bezel bend vertical spot, 0-100 */
    public static final String KEY_EXTRA_KEY_ANCHOR_PCT = "petal_ui_extra_key_anchor_pct";

    // quick settings panel
    /** turn QS theming on */
    public static final String KEY_QS_CUSTOM_ENABLED = "petal_qs_custom_enabled";
    /** QS colour, rgb only */
    public static final String KEY_QS_COLOR = "petal_qs_color";
    /** QS alpha, 0-255 */
    public static final String KEY_QS_ALPHA = "petal_qs_alpha";
    /** QS blur amount, 0-100 */
    public static final String KEY_QS_BLUR_INTENSITY = "petal_qs_blur_intensity";
    // restyle the QS tiles, default off
    public static final String KEY_QS_SKIN_ENABLED = "petal_qs_skin_enabled";
    // tile glow, feeds bloom and fill
    public static final String KEY_QS_EMISSION = "petal_qs_emission";

    // app drawer keys, used by Launcher3
    /** turn drawer theming on */
    public static final String KEY_APPS_CUSTOM_ENABLED = "petal_apps_custom_enabled";
    /** drawer colour, rgb only */
    public static final String KEY_APPS_COLOR = "petal_apps_color";
    /** drawer alpha, 0-255 */
    public static final String KEY_APPS_ALPHA = "petal_apps_alpha";
    /** drawer blur, 0-100 */
    public static final String KEY_APPS_BLUR_INTENSITY = "petal_apps_blur_intensity";

    // lockscreen media art
    // use cover art as the wallpaper
    public static final String KEY_LOCK_MEDIA_COVER = "petal_lock_media_cover";
    // spinning cd player, beats cover art
    public static final String KEY_LOCK_CD_PLAYER = "petal_lock_cd_player";

    // depth lockscreen, cutout over the clock
    /** depth lockscreen on/off */
    public static final String KEY_DEPTH_ENABLED = "petal_depth_enabled";
    /** uri of the cutout png */
    public static final String KEY_DEPTH_CUTOUT_URI = "petal_depth_cutout_uri";
    /** editor bumps this to force a reload */
    public static final String KEY_DEPTH_GENERATION = "petal_depth_generation";
    /** does the cutout parallax */
    public static final String KEY_DEPTH_PARALLAX = "petal_depth_parallax";
    // depth clock style id
    public static final String KEY_DEPTH_CLOCK_STYLE = "petal_depth_clock_style";
    /** 0 horizontal, 1 stacked */
    public static final String KEY_DEPTH_CLOCK_STACK = "petal_depth_clock_stack";
    /** clock colour, or DISABLED to auto-pick */
    public static final String KEY_DEPTH_CLOCK_COLOR = "petal_depth_clock_color";
    /** show the date line */
    public static final String KEY_DEPTH_CLOCK_DATE = "petal_depth_clock_date";
    /** clock y anchor, 5-85 */
    public static final String KEY_DEPTH_CLOCK_ANCHOR = "petal_depth_clock_anchor";
    /** clock x anchor, 10-90 */
    public static final String KEY_DEPTH_CLOCK_X = "petal_depth_clock_x";
    /** clock scale %, 60-160 */
    public static final String KEY_DEPTH_CLOCK_SCALE = "petal_depth_clock_scale";
    /** parallax strength in dp, 4-24 */
    public static final String KEY_DEPTH_TILT_DP = "petal_depth_tilt_dp";

    // lockscreen clock style key
    /** 0 stock, 1 bold, 2 minimal, 3 analog */
    public static final String KEY_LOCK_CLOCK_STYLE = "petal_lockscreen_clock_style";

    // seamless unlock transition, experimental
    /** hidden in the hub, experimental */
    public static final String KEY_UDFPS_RIPPLE = "petal_udfps_ripple";

    public static final String KEY_SEAMLESS_UNLOCK = "petal_seamless_unlock";

    // launcher keys
    /** icon pack package, empty = stock */
    public static final String KEY_ICON_PACK = "petal_icon_pack";
    /** recents style 0-2 */
    public static final String KEY_RECENTS_STYLE = "petal_recents_style";
    /** spring animations for recents */
    public static final String KEY_RECENTS_ANIM = "petal_recents_anim";

    // status bar key
    /** height in dp, or DISABLED for stock */
    public static final String KEY_STATUS_BAR_HEIGHT = "petal_status_bar_height";

    /** sentinel for "leave it to the system" */
    public static final int DISABLED = -1;

    // default blur, keeps some wallpaper detail
    public static final int DEFAULT_BLUR_INTENSITY = 70;
    public static final int DEFAULT_APPS_BLUR_INTENSITY = 40;
    /** fully opaque by default */
    public static final int DEFAULT_ALPHA = 255;
    // emission default, readable at a glance
    public static final int DEFAULT_QS_EMISSION = 60;

    // read/write the QS values

    public static boolean isQsCustomEnabled(Context context) {
        return Settings.System.getInt(context.getContentResolver(), KEY_QS_CUSTOM_ENABLED, 0) == 1;
    }

    /** QS skin state */
    public static boolean isQsSkinEnabled(Context context) {
        return Settings.System.getInt(context.getContentResolver(), KEY_QS_SKIN_ENABLED, 0) == 1;
    }

    // tile emission, same as the key above
    public static int getQsEmission(Context context) {
        // old key migration, drop this later
        if (Settings.System.getString(context.getContentResolver(), KEY_QS_EMISSION) == null) {
            return clamp(Settings.System.getInt(context.getContentResolver(),
                    "petal_qs_active_intensity", DEFAULT_QS_EMISSION), 0, 100);
        }
        return clamp(Settings.System.getInt(context.getContentResolver(),
                KEY_QS_EMISSION, DEFAULT_QS_EMISSION), 0, 100);
    }



    /** panel colour, DISABLED if unset */
    public static int getQsColor(Context context) {
        return Settings.System.getInt(context.getContentResolver(), KEY_QS_COLOR, DISABLED);
    }

    /** panel opacity, 0-255 */
    public static int getQsAlpha(Context context) {
        return clamp(Settings.System.getInt(
                context.getContentResolver(), KEY_QS_ALPHA, DEFAULT_ALPHA), 0, 255);
    }

    /** blur as a fraction of max radius */
    public static float getQsBlurScale(Context context) {
        int intensity = Settings.System.getInt(
                context.getContentResolver(), KEY_QS_BLUR_INTENSITY, DEFAULT_BLUR_INTENSITY);
        return clamp(intensity, 0, 100) / 100f;
    }

    // drawer getters/setters

    public static boolean isAppsCustomEnabled(Context context) {
        return Settings.System.getInt(context.getContentResolver(), KEY_APPS_CUSTOM_ENABLED, 0) == 1;
    }

    /** drawer colour, DISABLED if unset */
    public static int getAppsColor(Context context) {
        return Settings.System.getInt(context.getContentResolver(), KEY_APPS_COLOR, DISABLED);
    }

    /** drawer alpha, 0-255 */
    public static int getAppsAlpha(Context context) {
        return clamp(Settings.System.getInt(
                context.getContentResolver(), KEY_APPS_ALPHA, DEFAULT_ALPHA), 0, 255);
    }

    /** drawer blur fraction */
    public static float getAppsBlurScale(Context context) {
        int intensity = Settings.System.getInt(
                context.getContentResolver(), KEY_APPS_BLUR_INTENSITY, DEFAULT_APPS_BLUR_INTENSITY);
        return clamp(intensity, 0, 100) / 100f;
    }

    // dp getter, DISABLED sentinel and all

    /** height in dp, DISABLED = stock */
    public static int getStatusBarHeightDp(Context context) {
        return Settings.System.getInt(context.getContentResolver(), KEY_STATUS_BAR_HEIGHT, DISABLED);
    }

    // cover art vs cd player

    // cd player wins when both are on
    public static boolean isLockMediaCoverEnabled(Context context) {
        if (isLockCdPlayerEnabled(context)) return false;
        return Settings.System.getInt(context.getContentResolver(), KEY_LOCK_MEDIA_COVER, 0) == 1;
    }

    // cd player check
    public static boolean isLockCdPlayerEnabled(Context context) {
        return Settings.System.getInt(context.getContentResolver(), KEY_LOCK_CD_PLAYER, 0) == 1;
    }

    // depth getters

    /** depth toggle */
    public static boolean isDepthEnabled(Context context) {
        return Settings.System.getInt(context.getContentResolver(), KEY_DEPTH_ENABLED, 0) == 1;
    }

    /** cutout uri, null if unset */
    public static String getDepthCutoutUri(Context context) {
        return Settings.System.getString(context.getContentResolver(), KEY_DEPTH_CUTOUT_URI);
    }

    /** bumped on every cutout save */
    public static int getDepthGeneration(Context context) {
        return Settings.System.getInt(context.getContentResolver(), KEY_DEPTH_GENERATION, 0);
    }

    /** parallax toggle */
    public static boolean isDepthParallaxEnabled(Context context) {
        // parallax made the subject swim, keep it off
        return Settings.System.getInt(context.getContentResolver(), KEY_DEPTH_PARALLAX, 0) == 1;
    }

    /** clock style id */
    public static int getDepthClockStyle(Context context) {
        return Settings.System.getInt(context.getContentResolver(), KEY_DEPTH_CLOCK_STYLE, 0);
    }

    /** stack digits vertically */
    public static boolean isDepthClockStackVertical(Context context) {
        return Settings.System.getInt(context.getContentResolver(), KEY_DEPTH_CLOCK_STACK, 0) == 1;
    }

    /** clock anchor, 5-85 */
    public static int getDepthClockAnchorPct(Context context) {
        return clamp(Settings.System.getInt(
                context.getContentResolver(), KEY_DEPTH_CLOCK_ANCHOR, 20), 5, 85);
    }

    /** clock x, 10-90 % */
    public static int getDepthClockXPct(Context context) {
        return clamp(Settings.System.getInt(
                context.getContentResolver(), KEY_DEPTH_CLOCK_X, 50), 10, 90);
    }

    /** clock size %, 60-160 */
    public static int getDepthClockScalePct(Context context) {
        return clamp(Settings.System.getInt(
                context.getContentResolver(), KEY_DEPTH_CLOCK_SCALE, 100), 60, 160);
    }

    /** parallax dp, 4-24 */
    public static int getDepthTiltDp(Context context) {
        return clamp(Settings.System.getInt(
                context.getContentResolver(), KEY_DEPTH_TILT_DP, 10), 4, 24);
    }

    /** ink colour, DISABLED = auto */
    public static int getDepthClockColor(Context context) {
        return Settings.System.getInt(context.getContentResolver(), KEY_DEPTH_CLOCK_COLOR, DISABLED);
    }

    /** draw the date or not */
    public static boolean isDepthClockDateEnabled(Context context) {
        return Settings.System.getInt(context.getContentResolver(), KEY_DEPTH_CLOCK_DATE, 1) == 1;
    }

    /** clock style 0-3 */
    public static int getLockClockStyle(Context context) {
        return Settings.System.getInt(context.getContentResolver(), KEY_LOCK_CLOCK_STYLE, 0);
    }

    /** save the clock style */
    public static void setLockClockStyle(Context context, int style) {
        Settings.System.putInt(context.getContentResolver(), KEY_LOCK_CLOCK_STYLE, style);
    }

    /** seamless unlock state */
    public static boolean isSeamlessUnlockEnabled(Context context) {
        return Settings.System.getInt(context.getContentResolver(), KEY_SEAMLESS_UNLOCK, 0) == 1;
    }

    /** pack package, null = stock */
    public static String getIconPack(Context context) {
        return Settings.System.getString(context.getContentResolver(), KEY_ICON_PACK);
    }

    /** save the pack, empty = stock */
    public static void setIconPack(Context context, String packageName) {
        Settings.System.putString(context.getContentResolver(), KEY_ICON_PACK,
                packageName == null ? "" : packageName);
    }

    /** card style 0-2 */
    public static int getRecentsStyle(Context context) {
        return Settings.System.getInt(context.getContentResolver(), KEY_RECENTS_STYLE, 0);
    }

    /** recents anim flag */
    public static boolean isRecentsAnimEnabled(Context context) {
        return Settings.System.getInt(context.getContentResolver(), KEY_RECENTS_ANIM, 1) == 1;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
