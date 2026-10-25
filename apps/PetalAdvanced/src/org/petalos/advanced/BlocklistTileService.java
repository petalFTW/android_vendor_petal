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

import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** quick settings toggle for the blocker */
public class BlocklistTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        // ignore taps while an enable/disable is still running
        if (!SystemStatus.begin()) {
            return;
        }
        final boolean on = !SystemStatus.isOn(this);
        Prefs.setEnabled(this, on);
        // grey it out right away so it can't be spammed mid-toggle
        updateTile();
        final android.content.Context ctx = getApplicationContext();
        BlocklistManager.run(() -> {
            BlocklistManager.setEnabled(ctx, on);
            BlocklistManager.post(() -> {
                SystemStatus.end(ctx);
                updateTile();
            });
        });
    }

    private void updateTile() {
        final Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_advanced));
        tile.setLabel(getString(R.string.tile_label));
        if (SystemStatus.isBusy()) {
            tile.setState(Tile.STATE_UNAVAILABLE);
        } else {
            tile.setState(SystemStatus.isOn(this) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        }
        tile.updateTile();
    }
}
