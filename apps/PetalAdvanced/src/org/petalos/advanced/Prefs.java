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

package org.petalos.advanced;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** dumb little prefs holder, no libraries were harmed */
final class Prefs {

    private static final String FILE = "petal_advanced";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LAST_UPDATE = "last_update";
    private static final String KEY_LAST_COUNT = "last_count";
    private static final String KEY_LAST_SOURCES = "last_sources";
    private static final String KEY_CATALOG = "catalog_json";
    private static final String KEY_SOURCES = "enabled_sources";
    private static final String KEY_CUSTOM = "custom_domains";

    private Prefs() {}

    private static SharedPreferences sp(Context ctx) {
        // always app context so direct-boot storage stays consistent
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context c) {
        return sp(c).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_ENABLED, v).apply();
    }

    static long lastUpdate(Context c) {
        return sp(c).getLong(KEY_LAST_UPDATE, 0L);
    }

    static void setLastUpdate(Context c, long v) {
        sp(c).edit().putLong(KEY_LAST_UPDATE, v).apply();
    }

    static int lastCount(Context c) {
        return sp(c).getInt(KEY_LAST_COUNT, 0);
    }

    static void setLastCount(Context c, int v) {
        sp(c).edit().putInt(KEY_LAST_COUNT, v).apply();
    }

    static int lastSources(Context c) {
        return sp(c).getInt(KEY_LAST_SOURCES, 0);
    }

    static void setLastSources(Context c, int v) {
        sp(c).edit().putInt(KEY_LAST_SOURCES, v).apply();
    }

    static String catalog(Context c) {
        return sp(c).getString(KEY_CATALOG, null);
    }

    static void setCatalog(Context c, String json) {
        sp(c).edit().putString(KEY_CATALOG, json).apply();
    }

    static Set<String> enabledSources(Context c) {
        return new HashSet<>(sp(c).getStringSet(KEY_SOURCES, new HashSet<>()));
    }

    static boolean isSourceEnabled(Context c, String id) {
        return enabledSources(c).contains(id);
    }

    static void setSourceEnabled(Context c, String id, boolean on) {
        Set<String> s = enabledSources(c);
        if (on) {
            s.add(id);
        } else {
            s.remove(id);
        }
        sp(c).edit().putStringSet(KEY_SOURCES, s).apply();
    }

    static Set<String> customDomains(Context c) {
        return new HashSet<>(sp(c).getStringSet(KEY_CUSTOM, new HashSet<>()));
    }

    static void addCustomDomain(Context c, String d) {
        Set<String> s = customDomains(c);
        s.add(d);
        sp(c).edit().putStringSet(KEY_CUSTOM, s).apply();
    }

    static void removeCustomDomain(Context c, String d) {
        Set<String> s = customDomains(c);
        s.remove(d);
        sp(c).edit().putStringSet(KEY_CUSTOM, s).apply();
    }
}
