/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.hub;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.provider.SearchIndexablesContract;
import android.provider.SearchIndexablesProvider;

/** feeds every hub screen to settings search. the action carries the fragment. */
public final class PetalHubSearchIndexablesProvider extends SearchIndexablesProvider {

    public static final String SEARCH_ACTION_PREFIX = "org.petalos.hub.SEARCH.";

    private static final String HUB_PACKAGE = "org.petalos.hub";
    private static final String HUB_ACTIVITY = "org.petalos.hub.HubActivity";

    private static final class Screen {
        final int title;
        final int summary;
        final int icon;
        final String fragment;
        final String targetPackage;
        final String targetClass;

        Screen(int title, int summary, int icon, String fragment) {
            this(title, summary, icon, fragment, HUB_PACKAGE, HUB_ACTIVITY);
        }

        Screen(int title, int summary, int icon, String fragment, String targetPackage,
                String targetClass) {
            this.title = title;
            this.summary = summary;
            this.icon = icon;
            this.fragment = fragment;
            this.targetPackage = targetPackage;
            this.targetClass = targetClass;
        }
    }

    private static final Screen[] SCREENS = {
            new Screen(R.string.use_petal_settings_default, R.string.petal_settings_default_summary,
                    R.drawable.ic_hub_advanced, "org.petalos.hub.HubDashboardFragment"),
            new Screen(R.string.services_title, R.string.services_summary,
                    R.drawable.ic_hub_appdrawer, "org.petalos.hub.ServicesFragment"),
            new Screen(R.string.category_quick_settings, R.string.quick_settings_summary,
                    R.drawable.ic_hub_qs, "org.petalos.hub.QuickSettingsFragment"),
            new Screen(R.string.category_launcher, R.string.launcher_summary,
                    R.drawable.ic_hub_launcher, "org.petalos.hub.LauncherFragment"),
            new Screen(R.string.category_dialogs, R.string.dialogs_summary,
                    R.drawable.ic_hub_dialogs, "org.petalos.hub.DialogsFragment"),
            new Screen(R.string.category_island, R.string.island_summary,
                    R.drawable.ic_hub_island, "org.petalos.hub.IslandFragment"),
            new Screen(R.string.category_lockscreen, R.string.depth_studio_summary,
                    R.drawable.ic_hub_lockscreen, "org.petalos.hub.LockscreenFragment"),
            new Screen(R.string.category_power, R.string.power_summary,
                    R.drawable.ic_hub_power, "org.petalos.hub.PowerFragment"),
            new Screen(R.string.category_game_mode, R.string.game_mode_summary,
                    R.drawable.ic_hub_game_mode, "org.petalos.hub.GameModeFragment"),
            new Screen(R.string.category_work_mode, R.string.work_mode_summary,
                    R.drawable.ic_hub_privacy, "org.petalos.hub.WorkModeFragment"),
            new Screen(R.string.category_bridge, R.string.bridge_summary,
                    R.drawable.ic_hub_qs, "org.petalos.hub.BridgeFragment"),
            new Screen(R.string.category_blocker, R.string.blocker_summary,
                    R.drawable.ic_hub_advanced, "home",
                    "org.petalos.advanced", "org.petalos.advanced.AdvancedActivity"),
            new Screen(R.string.depth_studio_title, R.string.depth_studio_summary,
                    R.drawable.ic_hub_lockscreen, "org.petalos.depth.DepthStudioActivity",
                    "org.petalos.depth", "org.petalos.depth.DepthStudioActivity"),
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryXmlResources(String[] projection) {
        return new MatrixCursor(SearchIndexablesContract.INDEXABLES_XML_RES_COLUMNS);
    }

    @Override
    public Cursor queryRawData(String[] projection) {
        final Context context = getContext();
        final MatrixCursor cursor =
                new MatrixCursor(SearchIndexablesContract.INDEXABLES_RAW_COLUMNS);
        if (context == null) {
            return cursor;
        }
        for (Screen screen : SCREENS) {
            final Object[] row =
                    new Object[SearchIndexablesContract.INDEXABLES_RAW_COLUMNS.length];
            row[SearchIndexablesContract.COLUMN_INDEX_RAW_RANK] = 1;
            row[SearchIndexablesContract.COLUMN_INDEX_RAW_TITLE] = context.getString(screen.title);
            row[SearchIndexablesContract.COLUMN_INDEX_RAW_SUMMARY_ON] =
                    context.getString(screen.summary);
            row[SearchIndexablesContract.COLUMN_INDEX_RAW_SUMMARY_OFF] = null;
            row[SearchIndexablesContract.COLUMN_INDEX_RAW_ENTRIES] = null;
            row[SearchIndexablesContract.COLUMN_INDEX_RAW_KEYWORDS] =
                    context.getString(screen.title) + " petal petalos";
            row[SearchIndexablesContract.COLUMN_INDEX_RAW_SCREEN_TITLE] =
                    context.getString(screen.title);
            row[SearchIndexablesContract.COLUMN_INDEX_RAW_CLASS_NAME] = null;
            row[SearchIndexablesContract.COLUMN_INDEX_RAW_ICON_RESID] = screen.icon;
            row[SearchIndexablesContract.COLUMN_INDEX_RAW_INTENT_ACTION] =
                    SEARCH_ACTION_PREFIX + screen.fragment;
            row[SearchIndexablesContract.COLUMN_INDEX_RAW_INTENT_TARGET_PACKAGE] =
                    screen.targetPackage;
            row[SearchIndexablesContract.COLUMN_INDEX_RAW_INTENT_TARGET_CLASS] = screen.targetClass;
            row[SearchIndexablesContract.COLUMN_INDEX_RAW_KEY] = screen.fragment;
            row[SearchIndexablesContract.COLUMN_INDEX_RAW_USER_ID] = -1;
            row[SearchIndexablesContract.COLUMN_INDEX_RAW_PAYLOAD_TYPE] = null;
            row[SearchIndexablesContract.COLUMN_INDEX_RAW_PAYLOAD] = null;
            cursor.addRow(row);
        }
        return cursor;
    }

    @Override
    public Cursor queryNonIndexableKeys(String[] projection) {
        return new MatrixCursor(SearchIndexablesContract.NON_INDEXABLES_KEYS_COLUMNS);
    }
}
