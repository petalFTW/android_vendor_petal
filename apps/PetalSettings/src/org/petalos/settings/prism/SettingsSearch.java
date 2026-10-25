/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.settings.prism;

import android.provider.Settings;
import java.util.List;
import java.util.Locale;

/** Searchable destinations. A null action opens the Petal section containing the control. */
final class SettingsSearch {
    static final class Entry {
        final String panelId;
        final String title;
        final String keywords;
        final String action;

        Entry(String panelId, String title, String keywords, String action) {
            this.panelId = panelId;
            this.title = title;
            this.keywords = keywords;
            this.action = action;
        }

        boolean matches(String query) {
            String haystack = (title + " " + keywords + " " + Panels.byId(panelId).title)
                    .toLowerCase(Locale.ROOT);
            for (String word : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
                if (!haystack.contains(word)) return false;
            }
            return true;
        }
    }

    static final List<Entry> ENTRIES = List.of(
            new Entry("connect", "Wi-Fi", "network internet wireless", Settings.ACTION_WIFI_SETTINGS),
            new Entry("connect", "Bluetooth", "pair headphones devices", Settings.ACTION_BLUETOOTH_SETTINGS),
            new Entry("connect", "Mobile data", "sim usage cellular", Settings.ACTION_DATA_USAGE_SETTINGS),
            new Entry("connect", "Airplane mode", "flight radios", Settings.ACTION_AIRPLANE_MODE_SETTINGS),
            new Entry("display", "Brightness", "screen light", null),
            new Entry("display", "Depth lock screen", "wallpaper foreground", null),
            new Entry("display", "Text size & display", "font scaling appearance dark theme", Settings.ACTION_DISPLAY_SETTINGS),
            new Entry("notifications", "Notification settings", "alerts lock screen", "android.settings.NOTIFICATION_SETTINGS"),
            new Entry("notifications", "Notification history", "dismissed recent", "android.settings.NOTIFICATION_HISTORY"),
            new Entry("notifications", "Custom notification position", "lock screen placement", null),
            new Entry("sound", "Media volume", "music audio", null),
            new Entry("sound", "Do Not Disturb", "quiet focus interruptions", "android.settings.ZEN_MODE_SETTINGS"),
            new Entry("sound", "Sound & vibration", "ringtone haptics touch", Settings.ACTION_SOUND_SETTINGS),
            new Entry("privacy", "Privacy", "permissions camera microphone", "android.settings.PRIVACY_SETTINGS"),
            new Entry("privacy", "Location access", "gps requests", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            new Entry("privacy", "Security", "screen lock password pin fingerprint", Settings.ACTION_SECURITY_SETTINGS),
            new Entry("battery", "Battery saver", "power energy", Settings.ACTION_BATTERY_SAVER_SETTINGS),
            new Entry("battery", "Battery usage", "charging drain", "android.intent.action.POWER_USAGE_SUMMARY"),
            new Entry("apps", "All apps", "installed applications manage", Settings.ACTION_APPLICATION_SETTINGS),
            new Entry("apps", "Storage", "space cleanup files", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
            new Entry("apps", "Default apps", "browser phone launcher home", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            new Entry("about", "Device info", "android version build software petalos", Settings.ACTION_DEVICE_INFO_SETTINGS),
            new Entry("display", "Accessibility", "screen reader talkback magnification hearing", Settings.ACTION_ACCESSIBILITY_SETTINGS),
            new Entry("about", "System", "language keyboard date time backup reset", "com.android.settings/.Settings$SystemDashboardActivity"),
            new Entry("network", "Network & internet", "wifi mobile hotspot tethering", Settings.ACTION_WIRELESS_SETTINGS),
            new Entry("connected", "Connected devices", "bluetooth nfc cast", Settings.ACTION_BLUETOOTH_SETTINGS),
            new Entry("storage", "Storage", "space cleanup storage manager", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
            new Entry("security", "Security", "screen lock advanced security", Settings.ACTION_SECURITY_SETTINGS),
            new Entry("location", "Location", "gps recent requests permissions", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            new Entry("safety", "Safety Center", "safety security privacy", "android.intent.action.SAFETY_CENTER"),
            new Entry("emergency", "Safety & emergency", "medical information contacts", "android.settings.EMERGENCY_SETTINGS"),
            new Entry("accounts", "Passwords & accounts", "accounts sign in add", "android.settings.SYNC_SETTINGS"),
            new Entry("accessibility", "Accessibility", "screen reader text size", Settings.ACTION_ACCESSIBILITY_SETTINGS),
            new Entry("wallpaper", "Wallpaper", "background dark theme", "android.settings.WALLPAPER_SETTINGS"),
            new Entry("supervision", "Parental controls", "supervision limits", "android.settings.SUPERVISION_SETTINGS")
    );

    private SettingsSearch() {}
}
