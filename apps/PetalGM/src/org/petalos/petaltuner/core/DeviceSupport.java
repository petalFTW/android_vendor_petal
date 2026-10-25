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

package org.petalos.petaltuner.core;

import android.os.Build;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class DeviceSupport {

    public static final String[] BATTERY_CHARGING_NODES = {
            "/sys/class/oplus_chg/battery/mmi_charging_enable",
            "/sys/devices/virtual/oplus_chg/battery/mmi_charging_enable",
            "/sys/class/power_supply/battery/mmi_charging_enable",
            "/sys/devices/platform/soc/soc:oplus,chg_intf/oplus_chg/battery/mmi_charging_enable",
    };

    public static final String[] CHARGING_NODES = {
            "/sys/class/power_supply/battery/input_suspend",
            "/sys/class/power_supply/battery/charging_enabled",
            "/sys/class/power_supply/battery/charge_enabled",
            "/sys/class/power_supply/battery/battery_charging_enabled",
            "/sys/class/power_supply/battery/charge_disable",
            "/sys/class/qcom-battery/input_suspend",
            "/sys/class/qcom-battery/charging_enabled",
    };

    private static final Set<String> ONEPLUS_DEVICES = new HashSet<>(Arrays.asList(
            "macan", "macanc", "fairlady", "infiniti",
            "OP5D1BL1", "OP5D2BL1", "OP5D3BL1", "OP5D4BL1"));

    private DeviceSupport() {}

    public static String brand() {
        return Build.BRAND == null ? "" : Build.BRAND;
    }

    public static String device() {
        return Build.DEVICE == null ? "" : Build.DEVICE;
    }

    public static String model() {
        return Build.MODEL == null ? "" : Build.MODEL;
    }

    public static String soc() {
        return Sysfs.read("/sys/devices/soc0/soc_id") != null
                ? Build.SOC_MODEL : Build.HARDWARE;
    }

    public static boolean isOnePlus() {
        String brand = brand().toLowerCase();
        String manufacturer = (Build.MANUFACTURER == null ? "" : Build.MANUFACTURER).toLowerCase();
        return brand.contains("oneplus") || brand.contains("oplus")
                || manufacturer.contains("oneplus") || manufacturer.contains("oppo");
    }

    public static boolean isSupportedDevice() {
        return isOnePlus() || ONEPLUS_DEVICES.contains(device().toLowerCase());
    }

    public static String chargingNode() {
        String node = Sysfs.firstExisting(BATTERY_CHARGING_NODES);
        return node != null ? node : Sysfs.firstExisting(CHARGING_NODES);
    }

    public static boolean supportsBypass() {
        return chargingNode() != null;
    }

    public static String kernel() {
        String value = Sysfs.read("/proc/version");
        if (value == null) {
            return value_unknown();
        }
        String[] parts = value.split("\\s+");
        return parts.length >= 3 ? parts[2] : value;
    }

    private static String value_unknown() {
        return "unknown";
    }
}
