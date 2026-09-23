/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.automation.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** the shit we actually do. gate is optional and skips this one step if it fails. */
public final class Action {
    public String type = Types.A_LAUNCH;
    public final Map<String, String> params = new LinkedHashMap<>();
    public Condition gate;

    public Action() {
    }

    public Action(String type) {
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
        if (gate != null) {
            json.put("gate", gate.toJson());
        }
        return json;
    }

    public static Action fromJson(JSONObject json) throws JSONException {
        final Action action = new Action(json.optString("type", Types.A_LAUNCH));
        Trigger.readMap(json.optJSONObject("params"), action.params);
        final JSONObject gate = json.optJSONObject("gate");
        if (gate != null) {
            action.gate = Condition.fromJson(gate);
        }
        return action;
    }

    public String describe() {
        switch (type) {
            case Types.A_LAUNCH:
            case Types.A_CLOSE:
                return Types.actionLabel(type) + " " + get(Types.P_APP, "?");
            case Types.A_SETTING:
                return Types.actionLabel(type) + " " + get(Types.P_SETTING, "?")
                        + " " + get(Types.P_VALUE, "?");
            case Types.A_BRIGHTNESS:
            case Types.A_VOLUME:
                return Types.actionLabel(type) + " " + getInt(Types.P_LEVEL, 50) + "%";
            case Types.A_WAIT:
                return Types.actionLabel(type) + " " + get(Types.P_SECONDS, "1") + "s";
            case Types.A_TOAST:
            case Types.A_TEXT:
            case Types.A_NOTIFY:
                return Types.actionLabel(type) + ": " + get(Types.P_TEXT, "?");
            default:
                return Types.actionLabel(type);
        }
    }
}
