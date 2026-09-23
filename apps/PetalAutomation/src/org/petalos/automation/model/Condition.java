/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.automation.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** i only run the action list if all of these pass. */
public final class Condition {
    public String type = Types.C_BATTERY_ABOVE;
    public final Map<String, String> params = new LinkedHashMap<>();

    public Condition() {
    }

    public Condition(String type) {
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

    public JSONObject toJson() throws JSONException {
        final JSONObject json = new JSONObject();
        json.put("type", type);
        json.put("params", new JSONObject(params));
        return json;
    }

    public static Condition fromJson(JSONObject json) throws JSONException {
        final Condition condition = new Condition(json.optString("type", Types.C_BATTERY_ABOVE));
        Trigger.readMap(json.optJSONObject("params"), condition.params);
        return condition;
    }

    public String describe() {
        switch (type) {
            case Types.C_BATTERY_ABOVE:
            case Types.C_BATTERY_BELOW:
                return Types.conditionLabel(type) + " " + getInt(Types.P_LEVEL, 50) + "%";
            case Types.C_WIFI_SSID:
                return Types.conditionLabel(type) + " " + get(Types.P_VALUE, "?");
            case Types.C_APP_FOREGROUND:
                return Types.conditionLabel(type) + " " + get(Types.P_APP, "?");
            case Types.C_TIME_AFTER:
            case Types.C_TIME_BEFORE:
                return Types.conditionLabel(type) + " " + String.format(java.util.Locale.US,
                        "%02d:%02d", getInt(Types.P_HOUR, 0), getInt(Types.P_MINUTE, 0));
            default:
                return Types.conditionLabel(type);
        }
    }
}
