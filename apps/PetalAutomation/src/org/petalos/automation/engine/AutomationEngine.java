/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.automation.engine;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import org.petalos.automation.data.AutomationStore;
import org.petalos.automation.model.Automation;
import org.petalos.automation.model.Types;
import org.petalos.automation.trigger.EventReceiver;

import java.util.List;

/** the brain. reconciles what's registered, then dispatches matches. */
public final class AutomationEngine {
    private static final String TAG = "PetalAutomation";

    private AutomationEngine() {
    }

    public static final class Event {
        public String triggerType;
        public String app;
        public String text;
        public boolean on;
        public int level = -1;
    }

    public static void reconcile(Context context) {
        final Context app = context.getApplicationContext();
        final List<Automation> rules = AutomationStore.load(app);
        if (!PowerState.isEnabled(app)) {
            setComponent(app, EventReceiver.class, false);
            TriggerScheduler.cancelAll(app);
            Log.i(TAG, "off, tore it all down");
            return;
        }
        boolean needEvents = false;
        for (Automation rule : rules) {
            if (rule.enabled && usesStateReceiver(rule.trigger.type)) {
                needEvents = true;
                break;
            }
        }
        setComponent(app, EventReceiver.class, needEvents);
        TriggerScheduler.reschedule(app, rules);
        Log.i(TAG, "reconciled " + rules.size() + " rules, events=" + needEvents);
    }

    public static void handle(Context context, Event event) {
        final Context app = context.getApplicationContext();
        if (!PowerState.isEnabled(app) || event.triggerType == null) {
            return;
        }
        final List<Automation> rules = AutomationStore.load(app);
        for (Automation rule : rules) {
            if (!rule.enabled || !matches(rule, event)) {
                continue;
            }
            if (!ConditionEvaluator.allPass(app, rule.conditions)) {
                continue;
            }
            Log.i(TAG, "firing " + rule.name);
            ActionExecutor.run(app, rule.actions);
        }
    }

    private static boolean matches(Automation rule, Event event) {
        if (!event.triggerType.equals(rule.trigger.type)) {
            return false;
        }
        switch (event.triggerType) {
            case Types.T_BOOT:
            case Types.T_SHUTDOWN:
            case Types.T_UNLOCK:
                return true;
            case Types.T_SCREEN:
            case Types.T_POWER:
                return rule.trigger.getBool(Types.P_ON, true) == event.on;
            case Types.T_WIFI:
            case Types.T_BLUETOOTH:
            case Types.T_AIRPLANE:
                return rule.trigger.getBool(Types.P_ON, true) == event.on;
            case Types.T_BATTERY: {
                final int target = rule.trigger.getInt(Types.P_LEVEL, 50);
                return "above".equals(rule.trigger.get(Types.P_STATE, "below"))
                        ? event.level >= target : event.level <= target && event.level >= 0;
            }
            case Types.T_APP_OPEN:
            case Types.T_APP_CLOSE:
                return rule.trigger.get(Types.P_APP, "").equals(event.app);
            case Types.T_NOTIFICATION: {
                final String pkg = rule.trigger.get(Types.P_PACKAGE, "");
                final String filter = rule.trigger.get(Types.P_FILTER, "");
                if (!pkg.isEmpty() && !pkg.equals(event.app)) {
                    return false;
                }
                return filter.isEmpty() || (event.text != null
                        && event.text.toLowerCase().contains(filter.toLowerCase()));
            }
            case Types.T_BUTTON:
                return rule.trigger.get(Types.P_KEY, "").equals(event.text);
            default:
                return false;
        }
    }

    private static boolean usesStateReceiver(String type) {
        switch (type) {
            case Types.T_POWER:
            case Types.T_BATTERY:
            case Types.T_WIFI:
            case Types.T_BLUETOOTH:
            case Types.T_AIRPLANE:
            case Types.T_SCREEN:
            case Types.T_UNLOCK:
                return true;
            default:
                return false;
        }
    }

    /** on/off a manifest component so the system stops waking us for nothing. */
    public static void setComponent(Context context, Class<?> cls, boolean enabled) {
        try {
            context.getPackageManager().setComponentEnabledSetting(
                    new ComponentName(context, cls),
                    enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            Log.w(TAG, "couldn't toggle " + cls.getSimpleName(), e);
        }
    }
}
