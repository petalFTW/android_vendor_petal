package org.petal.catback;

import android.content.Context;
import android.content.SharedPreferences;

// user prefs, mostly where audio goes
public final class Settings {
    public static final String AUDIO_OUTPUT = "audio_output", CAR = "car", CAR_ROOT = "car_root", PHONE = "phone";
    private Settings() { }
    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE); }
    // car_root = su helper grabs everything, car = through us, phone = stays put
    public static String audioOutput(Context c) { return prefs(c).getString(AUDIO_OUTPUT, PHONE); }
    public static void setAudioOutput(Context c, String value) { prefs(c).edit().putString(AUDIO_OUTPUT, value).apply(); }
    // car screen: panels by default; auto = rail layout on panels 480 tall or less. tapping the battery on the car
    // five times swaps rail <-> panels and saves it here
    public static final String SCREEN_LAYOUT = "screen_layout", LAYOUT_AUTO = "auto", LAYOUT_COMPACT = "compact", LAYOUT_WIDE = "wide",
            NATIVE_VIDEO = "native_video";
    public static String screenLayout(Context c) { return prefs(c).getString(SCREEN_LAYOUT, LAYOUT_WIDE); }
    public static void setScreenLayout(Context c, String value) { prefs(c).edit().putString(SCREEN_LAYOUT, value).apply(); }
    // small panels get their own resolution instead of 720p the car has to shrink
    public static boolean nativeVideo(Context c) { return prefs(c).getBoolean(NATIVE_VIDEO, true); }
    public static void setNativeVideo(Context c, boolean value) { prefs(c).edit().putBoolean(NATIVE_VIDEO, value).apply(); }
    // 2x supersampling below 720p, on by default
    public static boolean supersample(Context c) { return prefs(c).getBoolean("supersample", true); }
    public static void setSupersample(Context c, boolean value) { prefs(c).edit().putBoolean("supersample", value).apply(); }
    // what the last car ended up with, so the phone preview can show the same layout on auto
    public static boolean lastCompact(Context c) { return prefs(c).getBoolean("last_compact", false); }
    public static void setLastCompact(Context c, boolean value) { prefs(c).edit().putBoolean("last_compact", value).apply(); }
    // knob on the rail runs reversed by default; off = the old way, same direction as lists
    public static boolean knobRailReversed(Context c) { return prefs(c).getBoolean("knob_rail_reversed", true); }
    public static void setKnobRailReversed(Context c, boolean value) { prefs(c).edit().putBoolean("knob_rail_reversed", value).apply(); }
}
