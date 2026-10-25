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

package org.petalos.hub;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import org.json.JSONObject;

/**
 * Makes Commissioner the out-of-the-box system font.
 *
 * The font is a dynamic RRO and the theme framework disables dynamic font
 * overlays that are not referenced by THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
 * so pre-enabling it via the overlay config is not enough. Seed the setting
 * once; afterwards the PetalOS Hub font picker owns it.
 */
public class FontDefaultReceiver extends BroadcastReceiver {

    private static final String COMMISSIONER_OVERLAY =
            "org.petalos.overlay.font.commissioner";
    private static final String FONT_CATEGORY = "android.theme.customization.font";
    private static final String KEY_FONT_DEFAULT_APPLIED = "petal_font_default_applied";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        ContentResolver resolver = context.getContentResolver();
        if (Settings.Secure.getInt(resolver, KEY_FONT_DEFAULT_APPLIED, 0) == 1) {
            return;
        }
        try {
            String setting = Settings.Secure.getString(resolver,
                    Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES);
            JSONObject theme = new JSONObject(setting == null ? "{}" : setting);
            if (!theme.has(FONT_CATEGORY)) {
                theme.put(FONT_CATEGORY, COMMISSIONER_OVERLAY);
            }
            theme.put("_applied_timestamp", System.currentTimeMillis());
            Settings.Secure.putString(resolver,
                    Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES, theme.toString());
            Settings.Secure.putInt(resolver, KEY_FONT_DEFAULT_APPLIED, 1);
        } catch (Exception ignored) {
        }
    }
}
