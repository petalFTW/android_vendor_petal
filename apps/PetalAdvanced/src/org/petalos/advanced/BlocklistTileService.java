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
        final boolean on = !Prefs.isEnabled(this);
        Prefs.setEnabled(this, on);
        final android.content.Context ctx = getApplicationContext();
        BlocklistManager.run(() -> {
            BlocklistManager.setEnabled(ctx, on);
            BlocklistManager.post(this::updateTile);
        });
        updateTile();
    }

    private void updateTile() {
        final Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_advanced));
        tile.setLabel(getString(R.string.tile_label));
        tile.setState(Prefs.isEnabled(this) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
