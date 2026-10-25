/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.inputmethod.latin.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.preference.PreferenceManager;

/**
 * Reads the petal keyboard appearance prefs. All of it lives in the normal default
 * prefs file, same one the settings screen writes to.
 */
public final class PetalKeyboardAppearance {

    public static final String PREF_FONT = "petal_kb_font";
    public static final String PREF_GLASS = "petal_kb_glass";

    public static final String FONT_SYSTEM = "system";
    public static final String FONT_COMMISSIONER = "commissioner";
    public static final String FONT_CONDENSED = "condensed";
    public static final String FONT_SERIF = "serif";
    public static final String FONT_MONO = "monospace";

    private PetalKeyboardAppearance() {
    }

    public static SharedPreferences prefs(final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static String fontKey(final Context context) {
        return prefs(context).getString(PREF_FONT, FONT_SYSTEM);
    }

    /** system face by default, the petal font is commissioner */
    public static Typeface typeface(final Context context) {
        switch (fontKey(context)) {
            case FONT_COMMISSIONER:
                // aint bundled, so ask the platform nicely and hope
                return Typeface.create("commissioner", Typeface.NORMAL);
            case FONT_CONDENSED:
                return Typeface.create("sans-serif-condensed", Typeface.NORMAL);
            case FONT_SERIF:
                return Typeface.create("serif", Typeface.NORMAL);
            case FONT_MONO:
                return Typeface.create("monospace", Typeface.NORMAL);
            case FONT_SYSTEM:
            default:
                return Typeface.DEFAULT;
        }
    }

    /** the glass slab on every key, off until the user turns it on */
    public static boolean isGlassEnabled(final Context context) {
        return prefs(context).getBoolean(PREF_GLASS, false);
    }
}
