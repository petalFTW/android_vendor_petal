/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.automation.engine;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import org.petalos.automation.model.Automation;
import org.petalos.automation.model.Types;
import org.petalos.automation.trigger.AlarmReceiver;

import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** clocks. exact alarms, one per time rule, re-armed when it fires. */
public final class TriggerScheduler {
    private static final String TAG = "PetalAutomation";

    private TriggerScheduler() {
    }

    public static void reschedule(Context context, List<Automation> rules) {
        cancelAll(context);
        if (!PowerState.isEnabled(context)) {
            return;
        }
        for (Automation rule : rules) {
            if (rule.enabled && Types.T_TIME.equals(rule.trigger.type)) {
                scheduleNext(context, rule);
            }
        }
    }

    public static void scheduleNext(Context context, Automation rule) {
        final AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) {
            return;
        }
        final long when = nextOccurrence(rule);
        final PendingIntent pending = pending(context, rule.id);
        try {
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pending);
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pending);
            }
            track(context).add(rule.id);
            Log.i(TAG, "armed " + rule.name + " for " + new java.util.Date(when));
        } catch (SecurityException e) {
            Log.w(TAG, "no exact alarm permission, not arming", e);
        }
    }

    public static void cancelAll(Context context) {
        final AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) {
            return;
        }
        for (String id : new HashSet<>(track(context))) {
            alarms.cancel(pending(context, id));
        }
        track(context).clear();
    }

    private static long nextOccurrence(Automation rule) {
        final int hour = rule.trigger.getInt(Types.P_HOUR, 22);
        final int minute = rule.trigger.getInt(Types.P_MINUTE, 0);
        final Set<Integer> days = parseDays(rule.trigger.get(Types.P_DAYS, null));
        final Calendar now = Calendar.getInstance();
        final Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, hour);
        next.set(Calendar.MINUTE, minute);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (days.isEmpty()) {
            if (!next.after(now)) {
                next.add(Calendar.DAY_OF_YEAR, 1);
            }
            return next.getTimeInMillis();
        }
        for (int i = 0; i < 8; i++) {
            if (days.contains(next.get(Calendar.DAY_OF_WEEK)) && (i > 0 || next.after(now))) {
                return next.getTimeInMillis();
            }
            next.add(Calendar.DAY_OF_YEAR, 1);
        }
        return next.getTimeInMillis();
    }

    private static Set<Integer> parseDays(String raw) {
        final Set<Integer> days = new HashSet<>();
        if (raw == null || raw.isEmpty()) {
            return days;
        }
        for (String piece : raw.split(",")) {
            try {
                days.add(Integer.parseInt(piece.trim()));
            } catch (NumberFormatException ignored) {
                // garbage day, ignore
            }
        }
        return days;
    }

    private static PendingIntent pending(Context context, String ruleId) {
        final Intent intent = new Intent(context, AlarmReceiver.class)
                .setAction("org.petalos.automation.ALARM")
                .setData(Uri.parse("petalauto://rule/" + ruleId))
                .putExtra("rule_id", ruleId);
        return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> track(Context context) {
        final android.content.SharedPreferences prefs = context
                .createDeviceProtectedStorageContext()
                .getSharedPreferences("automation_alarms", Context.MODE_PRIVATE);
        final Set<String> ids = new HashSet<>(prefs.getStringSet("ids", new HashSet<>()));
        return new TrackedSet(prefs, ids);
    }

    /** writes back whenever the engine mutates the set. */
    private static final class TrackedSet extends HashSet<String> {
        private final android.content.SharedPreferences prefs;

        TrackedSet(android.content.SharedPreferences prefs, Set<String> seed) {
            super(seed);
            this.prefs = prefs;
        }

        @Override
        public boolean add(String value) {
            final boolean changed = super.add(value);
            persist();
            return changed;
        }

        @Override
        public boolean remove(Object value) {
            final boolean changed = super.remove(value);
            persist();
            return changed;
        }

        @Override
        public void clear() {
            super.clear();
            persist();
        }

        private void persist() {
            // super(seed) fires addAll before prefs lands, so bail if we got here too early
            if (prefs == null) {
                return;
            }
            prefs.edit().putStringSet("ids", new HashSet<>(this)).apply();
        }
    }
}
