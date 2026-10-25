/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.settings.prism;

import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

// the carousel panels, in order. `actions` drives the compact peek and the full
// page for the generic AOSP categories; null means the panel has custom content.
final class Panels {
    static final class Action {
        final int icon;
        final String label;
        final String help;
        final String action;

        Action(int icon, String label, String help, String action) {
            this.icon = icon;
            this.label = label;
            this.help = help;
            this.action = action;
        }
    }

    static final class Panel {
        final String id;
        final String title;
        final String sub;
        final int icon;
        final int cartColor;
        final List<Action> actions;

        Panel(String id, String title, String sub, int icon, int cartColor) {
            this(id, title, sub, icon, cartColor, List.of());
        }

        Panel(String id, String title, String sub, int icon, int cartColor,
                List<Action> actions) {
            this.id = id;
            this.title = title;
            this.sub = sub;
            this.icon = icon;
            this.cartColor = cartColor;
            this.actions = actions;
        }
    }

    /** Explicit AOSP component, because some dashboards have no public action. */
    private static Action component(int icon, String label, String help, String className) {
        return new Action(icon, label, help, "com.android.settings/" + className);
    }

    static final List<Panel> ALL = new ArrayList<>();

    static {
        ALL.add(new Panel("connect", "Connections", "Wi-Fi, Bluetooth, mobile data",
                R.drawable.ic_wifi, 0xFF8D7FBE));
        ALL.add(new Panel("network", "Network & internet", "Internet, mobile network, hotspot",
                R.drawable.ic_wifi, 0xFF7E9EC9, List.of(
                        new Action(R.drawable.ic_wifi, "Internet", "Wi-Fi and mobile data",
                                Settings.ACTION_WIRELESS_SETTINGS),
                        new Action(R.drawable.ic_wifi, "Mobile network", "SIM, roaming, carrier",
                                Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
                        new Action(R.drawable.ic_wifi, "Hotspot & tethering", "Share your connection",
                                "android.settings.TETHER_SETTINGS"))));
        ALL.add(new Panel("connected", "Connected devices", "Bluetooth, NFC, casting",
                R.drawable.ic_bluetooth, 0xFF8FA9D6, List.of(
                        new Action(R.drawable.ic_bluetooth, "Bluetooth", "Pair and connect devices",
                                Settings.ACTION_BLUETOOTH_SETTINGS),
                        new Action(R.drawable.ic_wifi, "NFC", "Tap and pay, tags",
                                "android.settings.NFC_SETTINGS"),
                        new Action(R.drawable.ic_display, "Cast", "Screen mirroring",
                                "android.settings.CAST_SETTINGS"))));
        ALL.add(new Panel("display", "Display", "Brightness, text size, lock screen",
                R.drawable.ic_display, 0xFF7BA7AD));
        ALL.add(new Panel("wallpaper", "Wallpaper", "Wallpaper, dark theme, colours",
                R.drawable.ic_display, 0xFFD69FB4, List.of(
                        new Action(R.drawable.ic_display, "Wallpaper", "Change your background",
                                "android.settings.WALLPAPER_SETTINGS"),
                        new Action(R.drawable.ic_display, "Dark theme", "Display appearance",
                                Settings.ACTION_DISPLAY_SETTINGS),
                        new Action(R.drawable.ic_display, "Display", "Brightness and size",
                                Settings.ACTION_DISPLAY_SETTINGS))));
        ALL.add(new Panel("notifications", "Notifications", "App alerts, history, lock screen",
                R.drawable.ic_bell, 0xFFC98D7B));
        ALL.add(new Panel("sound", "Sound & vibration", "Volume, ringtones, Do Not Disturb",
                R.drawable.ic_sound, 0xFFA9B6D3));
        ALL.add(new Panel("privacy", "Privacy & security", "Permissions, screen lock, location",
                R.drawable.ic_shield, 0xFF9FBFB4));
        ALL.add(new Panel("security", "Security", "Screen lock, advanced security",
                R.drawable.ic_shield, 0xFF8FBF9F, List.of(
                        new Action(R.drawable.ic_shield, "Security", "Screen lock and accounts",
                                Settings.ACTION_SECURITY_SETTINGS),
                        new Action(R.drawable.ic_shield, "More security & privacy",
                                "Safety Center and advanced settings",
                                "com.android.settings.MORE_SECURITY_PRIVACY_SETTINGS"))));
        ALL.add(new Panel("location", "Location", "Access, recent requests, permissions",
                R.drawable.ic_info, 0xFF7EB8C9, List.of(
                        new Action(R.drawable.ic_info, "Location access", "On, off and recent requests",
                                Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                        new Action(R.drawable.ic_info, "App permissions", "What apps can access",
                                Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
                        new Action(R.drawable.ic_shield, "Privacy", "Camera, microphone, location",
                                "android.settings.PRIVACY_SETTINGS"))));
        ALL.add(new Panel("safety", "Safety Center", "Safety, security and privacy status",
                R.drawable.ic_shield, 0xFFA8C98F, List.of(
                        new Action(R.drawable.ic_shield, "Safety Center", "Open the safety dashboard",
                                "android.intent.action.SAFETY_CENTER"),
                        new Action(R.drawable.ic_shield, "More security & privacy",
                                "Advanced security settings",
                                "com.android.settings.MORE_SECURITY_PRIVACY_SETTINGS"),
                        new Action(R.drawable.ic_apps, "Permissions", "Manage app access",
                                Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))));
        ALL.add(new Panel("emergency", "Safety & emergency", "Medical info, emergency contacts",
                R.drawable.ic_shield, 0xFFC98F8F, List.of(
                        new Action(R.drawable.ic_info, "Emergency information", "Medical details and contacts",
                                "android.settings.EMERGENCY_SETTINGS"),
                        new Action(R.drawable.ic_bell, "Emergency alerts", "Wireless alert settings",
                                "android.settings.EMERGENCY_SETTINGS"))));
        ALL.add(new Panel("battery", "Battery", "Charging, saver, background use",
                R.drawable.ic_battery, 0xFFD9B972));
        ALL.add(new Panel("storage", "Storage", "Free space, cleanup, storage manager",
                R.drawable.ic_apps, 0xFFC9A97E, List.of(
                        new Action(R.drawable.ic_apps, "Storage", "Free space and usage",
                                Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
                        new Action(R.drawable.ic_apps, "Storage manager", "Automatic cleanup",
                                "android.settings.STORAGE_MANAGER_SETTINGS"),
                        new Action(R.drawable.ic_apps, "Apps", "Installed applications",
                                Settings.ACTION_APPLICATION_SETTINGS))));
        ALL.add(new Panel("apps", "Apps & storage", "Installed apps, defaults, free space",
                R.drawable.ic_apps, 0xFFB78FA9));
        ALL.add(new Panel("accounts", "Passwords & accounts", "Accounts, sign-in, autofill",
                R.drawable.ic_info, 0xFFB0A0D6, List.of(
                        new Action(R.drawable.ic_info, "Passwords & accounts", "Accounts on this phone",
                                "android.settings.SYNC_SETTINGS"),
                        new Action(R.drawable.ic_info, "Add account", "Email, Google and more",
                                "android.settings.ADD_ACCOUNT_SETTINGS"))));
        ALL.add(new Panel("accessibility", "Accessibility", "Display, sound and interaction help",
                R.drawable.ic_display, 0xFF9FC9C2, List.of(
                        new Action(R.drawable.ic_display, "Accessibility", "Screen reader, magnification",
                                Settings.ACTION_ACCESSIBILITY_SETTINGS),
                        new Action(R.drawable.ic_display, "Display size & text", "Make content easier to read",
                                Settings.ACTION_DISPLAY_SETTINGS),
                        new Action(R.drawable.ic_sound, "Vibration & haptics", "Touch feedback strength",
                                Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        ALL.add(new Panel("system", "System", "Languages, time, backup, reset",
                R.drawable.ic_apps, 0xFF9AA3B5, List.of(
                        new Action(R.drawable.ic_apps, "Languages & input", "Language, keyboard, gestures",
                                "android.settings.LOCALE_SETTINGS"),
                        new Action(R.drawable.ic_info, "Date & time", "Clock and time zone",
                                Settings.ACTION_DATE_SETTINGS),
                        component(R.drawable.ic_apps, "System", "Backup, reset and updates",
                                "com.android.settings.Settings$SystemDashboardActivity"))));
        ALL.add(new Panel("supervision", "Parental controls", "Supervision and screen limits",
                R.drawable.ic_shield, 0xFFB5A89A, List.of(
                        new Action(R.drawable.ic_shield, "Parental controls", "Set up supervision",
                                "android.settings.SUPERVISION_SETTINGS"))));
        ALL.add(new Panel("about", "About phone", "Device information and software",
                R.drawable.ic_info, 0xFFC9B4DE));
        ALL.add(new Panel("hub", "Your petalOS", "Hub, themes, island, updates",
                R.drawable.ic_petal, 0xFFE1A9C7));
    }

    static Panel byId(String id) {
        for (Panel panel : ALL) if (panel.id.equals(id)) return panel;
        return ALL.get(0);
    }

    private Panels() {}
}
