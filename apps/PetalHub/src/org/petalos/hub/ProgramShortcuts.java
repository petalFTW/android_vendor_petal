/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.petalos.hub;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;

import java.util.Collections;

/** Publishes one home-screen entry per imported Windows program. */
final class ProgramShortcuts {
    static final String EXTRA_PROGRAM_ID = "org.petalos.proton4a.PROGRAM_ID";
    static final String EXTRA_PROGRAM_NAME = "org.petalos.proton4a.PROGRAM_NAME";

    private ProgramShortcuts() {}

    static String shortcutId(String programId) {
        return "proton4a_" + programId;
    }

    static boolean pin(Context context, String programId, String name) {
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) return false;

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setClassName(context.getPackageName(), Proton4ARunActivity.class.getName());
        intent.putExtra(EXTRA_PROGRAM_ID, programId);
        intent.putExtra(EXTRA_PROGRAM_NAME, name);

        ShortcutInfo info = new ShortcutInfo.Builder(context, shortcutId(programId))
                .setShortLabel(name)
                .setLongLabel(name)
                .setIcon(Icon.createWithResource(context, R.drawable.ic_hub_proton4a))
                .setIntent(intent)
                .build();

        try {
            for (ShortcutInfo pinned : manager.getPinnedShortcuts()) {
                if (pinned.getId().equals(info.getId())) {
                    manager.updateShortcuts(Collections.singletonList(info));
                    return true;
                }
            }
            return manager.requestPinShortcut(info, null);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static void remove(Context context, String programId) {
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null) return;
        try {
            manager.removeLongLivedShortcuts(Collections.singletonList(shortcutId(programId)));
        } catch (RuntimeException ignored) {
            // nothing pinned
        }
    }
}
