/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.automation.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.petalos.automation.model.Automation;

import java.util.ArrayList;
import java.util.List;

/** rules live in one json blob in device-protected prefs. no db, no drama. */
public final class AutomationStore {
    private static final String PREFS = "automation_store";
    private static final String KEY_RULES = "rules";
    private static final String KEY_STATS = "stats";

    private AutomationStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<Automation> load(Context context) {
        final String raw = prefs(context).getString(KEY_RULES, null);
        final List<Automation> rules = new ArrayList<>();
        if (raw == null) {
            return rules;
        }
        try {
            final JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                rules.add(Automation.fromJson(array.getJSONObject(i)));
            }
        } catch (Exception e) {
            // corrupt blob. wipe it, nothing i can do from here.
            prefs(context).edit().remove(KEY_RULES).apply();
        }
        return rules;
    }

    public static void save(Context context, List<Automation> rules) {
        final JSONArray array = new JSONArray();
        for (Automation rule : rules) {
            try {
                array.put(rule.toJson());
            } catch (Exception e) {
                // skip the broken one
            }
        }
        prefs(context).edit().putString(KEY_RULES, array.toString()).apply();
    }

    public static void delete(Context context, String id) {
        final List<Automation> rules = load(context);
        for (int i = rules.size() - 1; i >= 0; i--) {
            if (rules.get(i).id.equals(id)) {
                rules.remove(i);
            }
        }
        save(context, rules);
    }

    public static String stats(Context context) {
        return prefs(context).getString(KEY_STATS, "no runs yet");
    }

    public static void setStats(Context context, String stats) {
        prefs(context).edit().putString(KEY_STATS, stats).apply();
    }
}
