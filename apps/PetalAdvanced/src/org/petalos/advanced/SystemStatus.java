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

package org.petalos.advanced;

import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Context;
import android.service.quicksettings.TileService;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/** keeps the status bar icon and the QS tile in sync with the real state */
final class SystemStatus {

    private static final String TAG = "PetalAdvanced";
    private static final String SLOT = "petal_blocker";

    /** true while an enable/disable is running, so the tile can grey itself out */
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    private SystemStatus() {}

    /** claim the toggle; false means something is already in flight */
    static boolean begin() {
        return BUSY.compareAndSet(false, true);
    }

    static boolean isBusy() {
        return BUSY.get();
    }

    static void end(Context c) {
        BUSY.set(false);
        refresh(c);
    }

    /** read the actual system flag, not just the app's own preference */
    static boolean isOn(Context c) {
        return HostsWriter.isStateOn(c);
    }

    static void refresh(Context c) {
        final boolean on = isOn(c);
        statusBar(c, on);
        requestTile(c);
    }

    static void statusBar(Context c, boolean on) {
        final StatusBarManager sbm = c.getSystemService(StatusBarManager.class);
        if (sbm == null) {
            return;
        }
        try {
            if (on) {
                sbm.setIcon(SLOT, R.drawable.ic_advanced, 0, c.getString(R.string.tile_label));
            } else {
                sbm.removeIcon(SLOT);
            }
        } catch (Throwable t) {
            Log.w(TAG, "status bar icon failed", t);
        }
    }

    static void requestTile(Context c) {
        try {
            TileService.requestListeningState(c,
                    new ComponentName(c, BlocklistTileService.class));
        } catch (Throwable t) {
            Log.w(TAG, "tile refresh failed", t);
        }
    }
}
