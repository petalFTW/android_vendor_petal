/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.automation.trigger;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.provider.Settings;

import org.petalos.automation.engine.AutomationEngine;
import org.petalos.automation.engine.Battery;
import org.petalos.automation.model.Types;

/** all the cheap system broadcasts. only enabled while a rule wants one. */
public final class EventReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (action == null) {
            return;
        }
        final AutomationEngine.Event event = new AutomationEngine.Event();
        switch (action) {
            case Intent.ACTION_SCREEN_ON:
                event.triggerType = Types.T_SCREEN;
                event.on = true;
                break;
            case Intent.ACTION_SCREEN_OFF:
                event.triggerType = Types.T_SCREEN;
                event.on = false;
                break;
            case Intent.ACTION_USER_PRESENT:
                event.triggerType = Types.T_UNLOCK;
                break;
            case Intent.ACTION_POWER_CONNECTED:
                event.triggerType = Types.T_POWER;
                event.on = true;
                break;
            case Intent.ACTION_POWER_DISCONNECTED:
                event.triggerType = Types.T_POWER;
                event.on = false;
                break;
            case Intent.ACTION_BATTERY_CHANGED:
                event.triggerType = Types.T_BATTERY;
                event.level = Battery.level(context);
                break;
            case WifiManager.WIFI_STATE_CHANGED_ACTION:
            case WifiManager.NETWORK_STATE_CHANGED_ACTION:
                event.triggerType = Types.T_WIFI;
                event.on = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1)
                        == WifiManager.WIFI_STATE_ENABLED;
                break;
            case BluetoothAdapter.ACTION_STATE_CHANGED:
                event.triggerType = Types.T_BLUETOOTH;
                event.on = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                        == BluetoothAdapter.STATE_ON;
                break;
            case Intent.ACTION_AIRPLANE_MODE_CHANGED:
                event.triggerType = Types.T_AIRPLANE;
                event.on = intent.getBooleanExtra("state",
                        Settings.Global.getInt(context.getContentResolver(),
                                Settings.Global.AIRPLANE_MODE_ON, 0) == 1);
                break;
            default:
                return;
        }
        AutomationEngine.handle(context, event);
    }
}
