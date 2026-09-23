/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.automation.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** when this shit fires. */
public final class Trigger {
    public String type = Types.T_TIME;
    public final Map<String, String> params = new LinkedHashMap<>();

    public Trigger() {
    }

    public Trigger(String type) {
        this.type = type;
    }

    public String get(String key) {
        return params.get(key);
    }

    public String get(String key, String fallback) {
        final String value = params.get(key);
        return value == null ? fallback : value;
    }

    public int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(get(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean getBool(String key, boolean fallback) {
        return "1".equals(get(key, fallback ? "1" : "0"));
    }

    public JSONObject toJson() throws JSONException {
        final JSONObject json = new JSONObject();
        json.put("type", type);
        json.put("params", new JSONObject(params));
        return json;
    }

    public static Trigger fromJson(JSONObject json) throws JSONException {
        final Trigger trigger = new Trigger(json.optString("type", Types.T_TIME));
        readMap(json.optJSONObject("params"), trigger.params);
        return trigger;
    }

    static void readMap(JSONObject source, Map<String, String> target) {
        if (source == null) {
            return;
        }
        final Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            target.put(key, source.optString(key, ""));
        }
    }

    public String describe() {
        final StringBuilder out = new StringBuilder(Types.triggerLabel(type));
        if (Types.T_TIME.equals(type)) {
            out.append(String.format(java.util.Locale.US, " %02d:%02d",
                    getInt(Types.P_HOUR, 22), getInt(Types.P_MINUTE, 0)));
        } else if (Types.T_APP_OPEN.equals(type) || Types.T_APP_CLOSE.equals(type)) {
            out.append(" ").append(get(Types.P_APP, "?"));
        } else if (Types.T_BATTERY.equals(type)) {
            out.append(" ").append(getInt(Types.P_LEVEL, 50)).append("%");
        } else if (Types.T_POWER.equals(type) || Types.T_SCREEN.equals(type)) {
            out.append(" ").append(get(Types.P_STATE, "on"));
        } else if (Types.T_WIFI.equals(type) || Types.T_BLUETOOTH.equals(type)
                || Types.T_AIRPLANE.equals(type)) {
            out.append(" ").append(getBool(Types.P_ON, true) ? "on" : "off");
        }
        return out.toString();
    }
}
