/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.inputmethod.latin.settings;

import android.os.Bundle;

import com.android.inputmethod.latin.R;

/** Petal keyboard looks: font and the built-in glass keys. */
public final class PetalKeyboardSettingsFragment extends SubScreenFragment {
    @Override
    public void onCreate(final Bundle icicle) {
        super.onCreate(icicle);
        addPreferencesFromResource(R.xml.prefs_screen_petal_keyboard);
    }
}
