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

package org.petalos.petaltuner;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.petalos.petaltuner.core.GmConfig;

public final class GameModeTile extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean enabled = new GmConfig(this).overlayEnabled();
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        GmConfig config = new GmConfig(this);
        boolean enabled = !config.overlayEnabled();
        config.setOverlayEnabled(enabled);
        if (enabled || config.hasWork()) {
            OverlayService.start(this);
        } else {
            OverlayService.stop(this);
        }
        onStartListening();
    }
}
