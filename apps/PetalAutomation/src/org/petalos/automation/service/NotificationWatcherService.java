/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.automation.service;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.petalos.automation.engine.AutomationEngine;
import org.petalos.automation.model.Types;

/** notification triggers. only alive if the user granted listener access. */
public final class NotificationWatcherService extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) {
            return;
        }
        final Bundle extras = sbn.getNotification().extras;
        String text = "";
        if (extras != null) {
            final CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
            final CharSequence body = extras.getCharSequence(Notification.EXTRA_TEXT);
            text = (title == null ? "" : title) + " " + (body == null ? "" : body);
        }
        final AutomationEngine.Event event = new AutomationEngine.Event();
        event.triggerType = Types.T_NOTIFICATION;
        event.app = sbn.getPackageName();
        event.text = text.trim();
        AutomationEngine.handle(this, event);
    }
}
