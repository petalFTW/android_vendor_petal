/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.automation.trigger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.petalos.automation.data.AutomationStore;
import org.petalos.automation.engine.AutomationEngine;
import org.petalos.automation.engine.ConditionEvaluator;
import org.petalos.automation.engine.PowerState;
import org.petalos.automation.engine.TriggerScheduler;
import org.petalos.automation.model.Automation;
import org.petalos.automation.model.Types;

/** a time rule went off. run it, then arm tomorrow. */
public final class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!PowerState.isEnabled(context)) {
            return;
        }
        final String id = intent.getStringExtra("rule_id");
        if (id == null) {
            return;
        }
        for (Automation rule : AutomationStore.load(context)) {
            if (!rule.id.equals(id)) {
                continue;
            }
            if (rule.enabled && ConditionEvaluator.allPass(context, rule.conditions)) {
                org.petalos.automation.engine.ActionExecutor.run(context, rule.actions);
            }
            if (rule.enabled && Types.T_TIME.equals(rule.trigger.type)) {
                TriggerScheduler.scheduleNext(context, rule);
            }
            return;
        }
    }
}
