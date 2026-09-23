/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.automation.trigger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.petalos.automation.engine.AutomationEngine;
import org.petalos.automation.model.Types;

/** boot/shutdown. rebuilds the schedule and nothing else. */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (action == null) {
            return;
        }
        if (Intent.ACTION_SHUTDOWN.equals(action)) {
            final AutomationEngine.Event event = new AutomationEngine.Event();
            event.triggerType = Types.T_SHUTDOWN;
            AutomationEngine.handle(context, event);
            return;
        }
        AutomationEngine.reconcile(context);
        final AutomationEngine.Event event = new AutomationEngine.Event();
        event.triggerType = Types.T_BOOT;
        AutomationEngine.handle(context, event);
    }
}
