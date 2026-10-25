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

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    // game mode, shared between Hub (settings) and PetalGM (engine)
    /** comma separated package names the user picked as games */
    public static final String KEY_GM_GAMES = "petal_gm_games";
    /** show the in-game overlay popup, 1 = on */
    public static final String KEY_GM_OVERLAY = "petal_gm_overlay";
    /** engage bypass charging while a game runs, 1 = on */
    public static final String KEY_GM_BYPASS = "petal_gm_bypass";
    /** force max refresh rate while a game runs, 1 = on */
    public static final String KEY_GM_MAX_HZ = "petal_gm_max_hz";
    /** force max resolution while a game runs, 1 = on */
    public static final String KEY_GM_MAX_RES = "petal_gm_max_res";
    /** do not disturb, heads-up block, rotation lock while a game runs, 1 = on */
    public static final String KEY_GM_FOCUS = "petal_gm_focus";
    /** performance profile applied while a game runs: balanced/performance/extreme */
    public static final String KEY_GM_PROFILE = "petal_gm_profile";
    /** free background RAM when a game starts, 1 = on */
    public static final String KEY_GM_FREE_RAM = "petal_gm_free_ram";

    // dialog geometry, must match PetalUiConfig
    public static final String KEY_DIALOG_STYLE = "petal_ui_dialog_style";
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
    /** album art blur behind the QS media controls, 0-100 */
    public static final String KEY_QS_MEDIA_ART_BLUR = "petal_qs_media_art_blur";
    public static final String KEY_QS_MEDIA_REFRACTION = "petal_qs_media_refraction";
    // restyle the QS tiles, default off
    public static final String KEY_QS_SKIN_ENABLED = "petal_qs_skin_enabled";
    // tile glow, feeds bloom and fill
    public static final String KEY_QS_EMISSION = "petal_qs_emission";
    public static final String KEY_QS_REACH_MODE = "petal_qs_reach_mode";
    public static final String QS_REACH_BALANCED = "balanced";
    public static final String QS_REACH_LEFT = "left";
    public static final String QS_REACH_RIGHT = "right";

    // island glass, does not follow the shade blur anymore
    /** island blur amount, 0-100 */
    public static final String KEY_ISLAND_BLUR_INTENSITY = "petal_island_blur_intensity";
    /** edge refraction on the island blobs */
    public static final String KEY_ISLAND_BUBBLE = "petal_island_bubble";
    /** island edge refraction amount, 0-100 */
    public static final String KEY_ISLAND_REFRACTION = "petal_island_refraction";
    /** island rim chromatic aberration amount, 0-100 */
    public static final String KEY_ISLAND_CHROMA = "petal_island_chroma";
    /** island rim pop-out (refraction displacement) amount, 0-100 */
    public static final String KEY_ISLAND_POPOUT = "petal_island_popout";

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
    // -1 follows the clock; otherwise a percentage from the top
    public static final String KEY_DEPTH_NOTIFICATION_START = "petal_depth_notification_start";

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

    // work mode, notification redaction. shared with SystemUI
    // Active state: zero is off; old nonzero values remain compatible.
    public static final String KEY_WORK_MODE = "petal_work_mode";
    // Preferred mode, independent of whether the QS tile is on.
    public static final String KEY_WORK_MODE_LAST = "petal_work_mode_last";
    /** comma separated packages that never get redacted */
    public static final String KEY_WORK_MODE_APPS = "petal_work_mode_apps";
    // Contact lookup keys and names, JSON so punctuation doesn't wreck the list.
    public static final String KEY_WORK_MODE_CONTACTS = "petal_work_mode_contacts";

    public static final int WORK_MODE_OFF = 0;
    public static final int WORK_MODE_CONTENT = 1;
    public static final int WORK_MODE_FULL = 2;

    /** sentinel for "leave it to the system" */
    public static final int DISABLED = -1;

    // default blur, keeps some wallpaper detail
    public static final int DEFAULT_BLUR_INTENSITY = 70;
    public static final int DEFAULT_APPS_BLUR_INTENSITY = 40;
    // island blur, a bit softer than the shade by default
    public static final int DEFAULT_ISLAND_BLUR_INTENSITY = 50;
    // island refraction, half pull to start with
    public static final int DEFAULT_ISLAND_REFRACTION = 50;
    // island chromatic aberration, half split to start with
    public static final int DEFAULT_ISLAND_CHROMA = 50;
    // island pop-out, a bit above half so it reads as raised by default
    public static final int DEFAULT_ISLAND_POPOUT = 60;
    // Start with a frosted centre, keep the rim readable.
    public static final int DEFAULT_QS_MEDIA_ART_BLUR = 50;
    public static final int DEFAULT_QS_MEDIA_REFRACTION = 50;
    /** 100% on the media art slider maps to this many dp */
    public static final float QS_MEDIA_ART_BLUR_MAX_DP = 32f;
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

    public static String getQsReachMode(Context context) {
        String mode = Settings.System.getString(context.getContentResolver(), KEY_QS_REACH_MODE);
        return QS_REACH_LEFT.equals(mode) || QS_REACH_RIGHT.equals(mode)
                ? mode : QS_REACH_BALANCED;
    }

    /** album art blur as a fraction, 0 means off */
    public static float getQsMediaArtBlurScale(Context context) {
        int intensity = Settings.System.getInt(
                context.getContentResolver(), KEY_QS_MEDIA_ART_BLUR, DEFAULT_QS_MEDIA_ART_BLUR);
        return clamp(intensity, 0, 100) / 100f;
    }

    public static float getQsMediaRefractionScale(Context context) {
        return clamp(Settings.System.getInt(context.getContentResolver(),
                KEY_QS_MEDIA_REFRACTION, DEFAULT_QS_MEDIA_REFRACTION), 0, 100) / 100f;
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

    /** island backdrop blur fraction, its own knob now */
    public static float getIslandBlurScale(Context context) {
        int intensity = Settings.System.getInt(
                context.getContentResolver(), KEY_ISLAND_BLUR_INTENSITY,
                DEFAULT_ISLAND_BLUR_INTENSITY);
        return clamp(intensity, 0, 100) / 100f;
    }

    /** master switch for the edge refraction on the island blobs */
    public static boolean isIslandBubbleEnabled(Context context) {
        return Settings.System.getInt(context.getContentResolver(), KEY_ISLAND_BUBBLE, 1) == 1;
    }

    /** island edge refraction, fraction of the max pull */
    public static float getIslandRefractionScale(Context context) {
        int intensity = Settings.System.getInt(
                context.getContentResolver(), KEY_ISLAND_REFRACTION, DEFAULT_ISLAND_REFRACTION);
        return clamp(intensity, 0, 100) / 100f;
    }

    /** island rim chromatic aberration, fraction of the max split */
    public static float getIslandChromaScale(Context context) {
        int intensity = Settings.System.getInt(
                context.getContentResolver(), KEY_ISLAND_CHROMA, DEFAULT_ISLAND_CHROMA);
        return clamp(intensity, 0, 100) / 100f;
    }

    /** island rim pop-out, fraction of the max refraction displacement */
    public static float getIslandPopoutScale(Context context) {
        int intensity = Settings.System.getInt(
                context.getContentResolver(), KEY_ISLAND_POPOUT, DEFAULT_ISLAND_POPOUT);
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

    public static int getDepthNotificationStartPct(Context context) {
        return DepthNotificationPlacement.clampPercent(Settings.System.getInt(
                context.getContentResolver(), KEY_DEPTH_NOTIFICATION_START,
                DepthNotificationPlacement.AUTOMATIC));
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

    // work mode getters/setters

    /** 0 off, 1 content blacked, 2 fully redacted */
    public static int getWorkMode(Context context) {
        int mode = Settings.System.getInt(context.getContentResolver(), KEY_WORK_MODE, WORK_MODE_OFF);
        return mode == WORK_MODE_CONTENT || mode == WORK_MODE_FULL
                ? getWorkModeLast(context) : WORK_MODE_OFF;
    }

    public static boolean setWorkMode(Context context, int mode) {
        mode = clamp(mode, WORK_MODE_OFF, WORK_MODE_FULL);
        int remember = mode == WORK_MODE_OFF ? getWorkMode(context) : mode;
        if (remember != WORK_MODE_OFF) {
            Settings.System.putInt(context.getContentResolver(), KEY_WORK_MODE_LAST, remember);
        }
        return Settings.System.putInt(context.getContentResolver(), KEY_WORK_MODE, mode);
    }

    public static boolean setWorkModePreference(Context context, int mode) {
        if (mode != WORK_MODE_CONTENT && mode != WORK_MODE_FULL) return false;
        return Settings.System.putInt(context.getContentResolver(), KEY_WORK_MODE_LAST, mode);
    }

    /** what the qs tile flips back on with, defaults to content mode */
    public static int getWorkModeLast(Context context) {
        int last = Settings.System.getInt(context.getContentResolver(), KEY_WORK_MODE_LAST,
                Settings.System.getInt(context.getContentResolver(), KEY_WORK_MODE, WORK_MODE_CONTENT));
        return last == WORK_MODE_FULL ? WORK_MODE_FULL : WORK_MODE_CONTENT;
    }

    /** qs tile flip: off remembers where it was */
    public static boolean toggleWorkMode(Context context) {
        int next = getWorkMode(context) == WORK_MODE_OFF ? getWorkModeLast(context) : WORK_MODE_OFF;
        return setWorkMode(context, next) && next != WORK_MODE_OFF;
    }

    /** packages that never get redacted, empty set = nothing spared */
    public static Set<String> getWorkModeApps(Context context) {
        String raw = Settings.System.getString(context.getContentResolver(), KEY_WORK_MODE_APPS);
        Set<String> apps = new LinkedHashSet<>();
        if (raw != null && !raw.trim().isEmpty()) {
            for (String pkg : raw.split(",")) {
                if (!pkg.trim().isEmpty()) apps.add(pkg.trim());
            }
        }
        return apps;
    }

    public static void setWorkModeApps(Context context, Set<String> apps) {
        Settings.System.putString(context.getContentResolver(), KEY_WORK_MODE_APPS,
                String.join(",", apps));
    }

    // Read the old draft format too, don't bin someone's picked contacts.
    public static List<String[]> getWorkModeContacts(Context context) {
        String raw = Settings.System.getString(context.getContentResolver(), KEY_WORK_MODE_CONTACTS);
        List<String[]> contacts = new ArrayList<>();
        if (raw != null && !raw.trim().isEmpty()) {
            if (raw.trim().startsWith("[")) {
                try {
                    JSONArray entries = new JSONArray(raw);
                    Set<String> seen = new LinkedHashSet<>();
                    for (int i = 0; i < entries.length(); i++) {
                        JSONArray entry = entries.optJSONArray(i);
                        if (entry == null || entry.length() != 2) continue;
                        String lookup = entry.optString(0, "");
                        String name = entry.optString(1, "");
                        if (!lookup.isEmpty() && !name.trim().isEmpty() && seen.add(lookup)) {
                            contacts.add(new String[]{lookup, name});
                        }
                    }
                } catch (JSONException ignored) {
                    // Broken stored data isn't an allow-list entry.
                }
                return contacts;
            }
            for (String line : raw.split("\n")) {
                int split = line.indexOf('#');
                if (split <= 0 || split == line.length() - 1) continue;
                contacts.add(new String[]{line.substring(0, split), line.substring(split + 1)});
            }
        }
        return contacts;
    }

    public static void setWorkModeContacts(Context context, List<String[]> contacts) {
        JSONArray entries = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        for (String[] contact : contacts) {
            if (contact == null || contact.length != 2 || contact[0] == null
                    || contact[1] == null || contact[0].isEmpty()
                    || contact[1].trim().isEmpty() || !seen.add(contact[0])) continue;
            entries.put(new JSONArray().put(contact[0]).put(contact[1]));
        }
        Settings.System.putString(context.getContentResolver(), KEY_WORK_MODE_CONTACTS,
                entries.toString());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
