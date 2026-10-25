/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.power;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.android.internal.app.PetalSpoofConfig;

/** Permission-protected wakeup to reconcile a changed persistent idle exemption. */
public final class SpoofPolicyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        final String action = intent.getAction();
        if (!PetalSpoofConfig.ACTION_POLICY_CHANGED.equals(action)
                && !IdlePowerReceiver.ACTION_SETTINGS_CHANGED.equals(action)) return;
        final PendingResult result = goAsync();
        new Thread(() -> {
            try {
                if (IdlePowerReceiver.ACTION_SETTINGS_CHANGED.equals(action)) {
                    IdlePowerReceiver.updateSettings(context.getApplicationContext());
                } else {
                    IdlePowerReceiver.reconcileExemptions(context.getApplicationContext());
                }
            } finally {
                result.finish();
            }
        }, "PetalIdleReconcile").start();
    }
}
