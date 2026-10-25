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

package org.petalos.petaltuner.core;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import org.petalos.config.PetalConfig;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class GmConfig {

    public static final String HUB_PACKAGE = "org.petalos.hub";
    public static final String ACTION_GRANT = "org.petalos.hub.action.GM_GRANT";
    public static final String ACTION_DISPLAY = "org.petalos.hub.action.GM_DISPLAY";
    public static final String EXTRA_MAX_RES = "max_res";

    private final Context mContext;
    private final ContentResolver mResolver;
    private final Prefs mPrefs;

    public GmConfig(Context context) {
        mContext = context.getApplicationContext();
        mResolver = mContext.getContentResolver();
        mPrefs = new Prefs(mContext);
    }

    public Set<String> games() {
        Set<String> result = new LinkedHashSet<>();
        String raw = Settings.System.getString(mResolver, PetalConfig.KEY_GM_GAMES);
        if (raw != null) {
            for (String pkg : raw.split(",")) {
                String trimmed = pkg.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        result.addAll(mPrefs.getStringSet(Prefs.GAME_PACKAGES));
        return Collections.unmodifiableSet(result);
    }

    public void setGames(Set<String> games) {
        Settings.System.putString(mResolver, PetalConfig.KEY_GM_GAMES, String.join(",", games));
        mPrefs.putStringSet(Prefs.GAME_PACKAGES, new LinkedHashSet<>());
    }

    public boolean isGame(String packageName) {
        return packageName != null && games().contains(packageName);
    }

    public boolean flag(String key, boolean def) {
        return Settings.System.getInt(mResolver, key, def ? 1 : 0) != 0;
    }

    public void setFlag(String key, boolean value) {
        Settings.System.putInt(mResolver, key, value ? 1 : 0);
    }

    public boolean overlayEnabled() {
        return flag(PetalConfig.KEY_GM_OVERLAY, true)
                && mPrefs.getBool(Prefs.OVERLAY_ENABLED, true);
    }

    public void setOverlayEnabled(boolean enabled) {
        setFlag(PetalConfig.KEY_GM_OVERLAY, enabled);
        mPrefs.putBool(Prefs.OVERLAY_ENABLED, enabled);
    }

    public String profile() {
        String value = Settings.System.getString(mResolver, PetalConfig.KEY_GM_PROFILE);
        return value == null || value.isEmpty() ? "performance" : value;
    }

    public boolean hasWork() {
        return !games().isEmpty()
                || mPrefs.getBool(Prefs.CHARGE_LIMIT_ENABLED, false)
                || mPrefs.getBool(Prefs.BYPASS_ENABLED, false)
                || mPrefs.getBool(Prefs.FOCUS_ENABLED, false);
    }

    public void requestGrants() {
        android.content.Intent intent = new android.content.Intent(ACTION_GRANT)
                .setPackage(HUB_PACKAGE);
        mContext.sendBroadcast(intent);
    }

    public void requestMaxResolution(boolean max) {
        android.content.Intent intent = new android.content.Intent(ACTION_DISPLAY)
                .setPackage(HUB_PACKAGE)
                .putExtra(EXTRA_MAX_RES, max);
        mContext.sendBroadcast(intent);
    }
}
