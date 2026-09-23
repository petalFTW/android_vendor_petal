/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.automation.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** one rule. trigger fires, conditions gate, actions run in order. */
public final class Automation {
    public String id = UUID.randomUUID().toString();
    public String name = "";
    public boolean enabled = true;
    public Trigger trigger = new Trigger();
    public final List<Condition> conditions = new ArrayList<>();
    public final List<Action> actions = new ArrayList<>();

    public JSONObject toJson() throws JSONException {
        final JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("enabled", enabled);
        json.put("trigger", trigger.toJson());
        final JSONArray conds = new JSONArray();
        for (Condition condition : conditions) {
            conds.put(condition.toJson());
        }
        json.put("conditions", conds);
        final JSONArray acts = new JSONArray();
        for (Action action : actions) {
            acts.put(action.toJson());
        }
        json.put("actions", acts);
        return json;
    }

    public static Automation fromJson(JSONObject json) throws JSONException {
        final Automation automation = new Automation();
        automation.id = json.optString("id", UUID.randomUUID().toString());
        automation.name = json.optString("name", "");
        automation.enabled = json.optBoolean("enabled", true);
        final JSONObject trigger = json.optJSONObject("trigger");
        if (trigger != null) {
            automation.trigger = Trigger.fromJson(trigger);
        }
        final JSONArray conds = json.optJSONArray("conditions");
        if (conds != null) {
            for (int i = 0; i < conds.length(); i++) {
                automation.conditions.add(Condition.fromJson(conds.getJSONObject(i)));
            }
        }
        final JSONArray acts = json.optJSONArray("actions");
        if (acts != null) {
            for (int i = 0; i < acts.length(); i++) {
                automation.actions.add(Action.fromJson(acts.getJSONObject(i)));
            }
        }
        return automation;
    }
}
