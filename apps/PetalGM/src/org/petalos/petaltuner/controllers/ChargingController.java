/*
 * Copyright (C) 2026 petalOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.petalos.petaltuner.controllers;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;

import org.petalos.petaltuner.core.DeviceSupport;
import org.petalos.petaltuner.core.Sysfs;

import vendor.lineage.health.IChargingControl;
import vendor.oplus.hardware.charger.ICharger;

public final class ChargingController {

    private static final String TAG = "PetalTuner/Charging";

    private static final long HAL_RETRY_MS = 5 * 60 * 1000;

    private final Context mContext;
    private long mChargingControlFailedAt;

    public ChargingController(Context context) {
        mContext = context.getApplicationContext();
    }

    public boolean isSupported() {
        return DeviceSupport.supportsBypass() || getChargingControl() != null;
    }

    private IChargingControl getChargingControl() {
        if (mChargingControlFailedAt != 0
                && System.currentTimeMillis() - mChargingControlFailedAt < HAL_RETRY_MS) {
            return null;
        }
        try {
            IBinder binder = ServiceManager.waitForDeclaredService(
                    IChargingControl.DESCRIPTOR + "/default");
            if (binder == null) {
                return null;
            }
            return IChargingControl.Stub.asInterface(binder);
        } catch (Exception e) {
            Log.w(TAG, "Lineage health HAL unavailable: " + e.getMessage());
            mChargingControlFailedAt = System.currentTimeMillis();
            return null;
        }
    }

    private ICharger getCharger() {
        try {
            IBinder binder = ServiceManager.getService(ICharger.DESCRIPTOR + "/default");
            if (binder == null) {
                return null;
            }
            return ICharger.Stub.asInterface(binder);
        } catch (Exception e) {
            Log.w(TAG, "Oplus charger HAL unavailable", e);
            return null;
        }
    }

    public boolean isPlugged() {
        Intent battery = batteryIntent();
        if (battery == null) {
            return false;
        }
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        if (plugged != 0) {
            return true;
        }
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    public int getLevel() {
        Intent battery = batteryIntent();
        if (battery == null) {
            return -1;
        }
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        if (level < 0 || scale <= 0) {
            return -1;
        }
        return Math.round(level * 100f / scale);
    }

    public int getTemperature() {
        Intent battery = batteryIntent();
        if (battery == null) {
            return Integer.MIN_VALUE;
        }
        return battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
    }

    public int getVoltage() {
        Intent battery = batteryIntent();
        if (battery == null) {
            return Integer.MIN_VALUE;
        }
        return battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Integer.MIN_VALUE);
    }

    public int getPluggedType() {
        Intent battery = batteryIntent();
        if (battery == null) {
            return 0;
        }
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
    }

    public String getHealth() {
        Intent battery = batteryIntent();
        if (battery == null) {
            return null;
        }
        switch (battery.getIntExtra(BatteryManager.EXTRA_HEALTH,
                BatteryManager.BATTERY_HEALTH_UNKNOWN)) {
            case BatteryManager.BATTERY_HEALTH_GOOD:
                return "Good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT:
                return "Overheat";
            case BatteryManager.BATTERY_HEALTH_DEAD:
                return "Dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:
                return "Over voltage";
            case BatteryManager.BATTERY_HEALTH_COLD:
                return "Cold";
            default:
                return "Unknown";
        }
    }

    public String getTechnology() {
        Intent battery = batteryIntent();
        if (battery == null) {
            return null;
        }
        String technology = battery.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
        ICharger charger = getCharger();
        if (charger != null) {
            try {
                int fast = charger.getPsyFastChgType();
                if (fast > 0) {
                    return (technology == null ? "Battery" : technology) + " · fast type " + fast;
                }
            } catch (Exception ignored) {
            }
        }
        return technology;
    }

    public int getCurrentNow() {
        BatteryManager batteryManager = mContext.getSystemService(BatteryManager.class);
        if (batteryManager == null) {
            return Integer.MIN_VALUE;
        }
        int current = batteryManager.getIntProperty(
                BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        return current == Integer.MIN_VALUE ? Integer.MIN_VALUE : Math.abs(current) / 1000;
    }

    public boolean isBatteryChargingEnabled() {
        IChargingControl control = getChargingControl();
        if (control != null) {
            try {
                return control.getChargingEnabled();
            } catch (Exception ignored) {
            }
        }
        String node = DeviceSupport.chargingNode();
        if (node == null) {
            return false;
        }
        String value = Sysfs.read(node);
        if (value == null) {
            return false;
        }
        return "1".equals(value.trim());
    }

    public boolean setBatteryChargingEnabled(boolean enabled) {
        IChargingControl control = getChargingControl();
        if (control != null) {
            try {
                control.setChargingEnabled(enabled);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "HAL setChargingEnabled failed", e);
            }
        }
        String node = DeviceSupport.chargingNode();
        if (node == null) {
            return false;
        }
        return Sysfs.write(node, enabled ? "1" : "0");
    }

    public boolean isBypassActive() {
        return isPlugged() && !isBatteryChargingEnabled();
    }

    public boolean setBypass(boolean enabled) {
        return setBatteryChargingEnabled(!enabled);
    }

    public boolean setCoolDown(int level) {
        ICharger charger = getCharger();
        if (charger != null) {
            try {
                return charger.setChargerCoolDown(Integer.toString(level)) >= 0;
            } catch (Exception e) {
                Log.w(TAG, "setChargerCoolDown failed", e);
            }
        }
        return false;
    }

    public boolean setSlowCharge(boolean enabled) {
        ICharger charger = getCharger();
        if (charger != null) {
            try {
                return charger.setPsySlowChgEn(enabled ? "1" : "0") >= 0;
            } catch (Exception e) {
                Log.w(TAG, "setPsySlowChgEn failed", e);
            }
        }
        String node = Sysfs.firstExisting("/sys/class/oplus_chg/battery/slow_chg_en");
        if (node != null) {
            return Sysfs.write(node, enabled ? "0,5,1" : "0,0,0");
        }
        return false;
    }

    public boolean setEndurance(boolean enabled) {
        ICharger charger = getCharger();
        if (charger == null) {
            return false;
        }
        try {
            return charger.setSuperEnduranceStatus(enabled ? "1" : "0") >= 0;
        } catch (Exception e) {
            Log.w(TAG, "setSuperEnduranceStatus failed", e);
            return false;
        }
    }

    public boolean enforceChargeLimit(boolean enabled, int limit, int resume) {
        if (!enabled || !isPlugged()) {
            return false;
        }
        int level = getLevel();
        if (level < 0) {
            return false;
        }
        if (level >= limit && isBatteryChargingEnabled()) {
            return setBatteryChargingEnabled(false);
        }
        if (level <= resume && !isBatteryChargingEnabled()) {
            return setBatteryChargingEnabled(true);
        }
        return false;
    }

    private Intent batteryIntent() {
        return mContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }
}
