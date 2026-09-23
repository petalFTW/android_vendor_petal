/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.automation.model;

/** every string id we throw around. strings so json doesn't care. */
public final class Types {
    private Types() {}

    // triggers
    public static final String T_TIME = "time";
    public static final String T_APP_OPEN = "app_open";
    public static final String T_APP_CLOSE = "app_close";
    public static final String T_BATTERY = "battery";
    public static final String T_POWER = "power";
    public static final String T_WIFI = "wifi";
    public static final String T_BLUETOOTH = "bluetooth";
    public static final String T_AIRPLANE = "airplane";
    public static final String T_SCREEN = "screen";
    public static final String T_UNLOCK = "unlock";
    public static final String T_NOTIFICATION = "notification";
    public static final String T_BOOT = "boot";
    public static final String T_SHUTDOWN = "shutdown";
    public static final String T_BUTTON = "button";

    // conditions
    public static final String C_BATTERY_ABOVE = "battery_above";
    public static final String C_BATTERY_BELOW = "battery_below";
    public static final String C_CHARGING = "charging";
    public static final String C_WIFI_ON = "wifi_on";
    public static final String C_WIFI_SSID = "wifi_ssid";
    public static final String C_SCREEN_ON = "screen_on";
    public static final String C_TIME_AFTER = "time_after";
    public static final String C_TIME_BEFORE = "time_before";
    public static final String C_APP_FOREGROUND = "app_foreground";

    // actions
    public static final String A_LAUNCH = "launch_app";
    public static final String A_CLOSE = "close_app";
    public static final String A_SETTING = "set_setting";
    public static final String A_BRIGHTNESS = "brightness";
    public static final String A_VOLUME = "volume";
    public static final String A_RINGER = "ringer";
    public static final String A_TAP = "ui_tap";
    public static final String A_SWIPE = "ui_swipe";
    public static final String A_TEXT = "ui_text";
    public static final String A_INTENT = "send_intent";
    public static final String A_NOTIFY = "notify";
    public static final String A_TOAST = "toast";
    public static final String A_WAIT = "wait";

    // param keys
    public static final String P_HOUR = "hour";
    public static final String P_MINUTE = "minute";
    public static final String P_DAYS = "days";
    public static final String P_APP = "app";
    public static final String P_LEVEL = "level";
    public static final String P_STATE = "state";
    public static final String P_ON = "on";
    public static final String P_SETTING = "setting";
    public static final String P_VALUE = "value";
    public static final String P_SECONDS = "seconds";
    public static final String P_TEXT = "text";
    public static final String P_ACTION = "action";
    public static final String P_X = "x";
    public static final String P_Y = "y";
    public static final String P_X2 = "x2";
    public static final String P_Y2 = "y2";
    public static final String P_MS = "ms";
    public static final String P_TITLE = "title";
    public static final String P_PACKAGE = "package";
    public static final String P_COMPONENT = "component";
    public static final String P_KEY = "key";
    public static final String P_FILTER = "filter";

    public static String triggerLabel(String type) {
        switch (type) {
            case T_TIME: return "At a time";
            case T_APP_OPEN: return "App opened";
            case T_APP_CLOSE: return "App closed";
            case T_BATTERY: return "Battery level";
            case T_POWER: return "Charger";
            case T_WIFI: return "Wi-Fi";
            case T_BLUETOOTH: return "Bluetooth";
            case T_AIRPLANE: return "Airplane mode";
            case T_SCREEN: return "Screen";
            case T_UNLOCK: return "Unlocked";
            case T_NOTIFICATION: return "Notification";
            case T_BOOT: return "On boot";
            case T_SHUTDOWN: return "On shutdown";
            case T_BUTTON: return "Button";
            default: return type;
        }
    }

    public static String actionLabel(String type) {
        switch (type) {
            case A_LAUNCH: return "Open app";
            case A_CLOSE: return "Close app";
            case A_SETTING: return "Toggle setting";
            case A_BRIGHTNESS: return "Brightness";
            case A_VOLUME: return "Volume";
            case A_RINGER: return "Ringer mode";
            case A_TAP: return "Tap";
            case A_SWIPE: return "Swipe";
            case A_TEXT: return "Type text";
            case A_INTENT: return "Send intent";
            case A_NOTIFY: return "Notification";
            case A_TOAST: return "Toast";
            case A_WAIT: return "Wait";
            default: return type;
        }
    }

    public static String conditionLabel(String type) {
        switch (type) {
            case C_BATTERY_ABOVE: return "Battery above";
            case C_BATTERY_BELOW: return "Battery below";
            case C_CHARGING: return "Charging";
            case C_WIFI_ON: return "Wi-Fi on";
            case C_WIFI_SSID: return "On Wi-Fi network";
            case C_SCREEN_ON: return "Screen on";
            case C_TIME_AFTER: return "After time";
            case C_TIME_BEFORE: return "Before time";
            case C_APP_FOREGROUND: return "App in foreground";
            default: return type;
        }
    }
}
