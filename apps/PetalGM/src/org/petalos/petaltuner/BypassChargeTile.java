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

import org.petalos.petaltuner.core.Prefs;
import org.petalos.petaltuner.core.TunerEngine;

public final class BypassChargeTile extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        TunerEngine engine = TunerEngine.get(this);
        if (!engine.charging().isSupported()) {
            tile.setState(Tile.STATE_UNAVAILABLE);
        } else if (engine.charging().isBypassActive()) {
            tile.setState(Tile.STATE_ACTIVE);
        } else {
            tile.setState(Tile.STATE_INACTIVE);
        }
        tile.updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        TunerEngine engine = TunerEngine.get(this);
        boolean next = !engine.charging().isBypassActive();
        engine.charging().setBypass(next);
        engine.prefs().putBool(Prefs.BYPASS_ENABLED, next);
        onStartListening();
    }
}
