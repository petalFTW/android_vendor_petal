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

package org.petalos.agever;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.annotation.RequiresApi;

import org.petalos.agever.output.ProviderFrameSink;

/** Quick Settings tile: mirrors whether the front camera is overridden. */
@RequiresApi(Build.VERSION_CODES.N)
public final class StageTile extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        boolean running = StageService.isRunning();
        Intent intent = new Intent(this, StageService.class)
                .setAction(running ? StageService.ACTION_STOP : StageService.ACTION_START);
        if (running) {
            startService(intent);
        } else {
            startForegroundService(intent);
        }
        // Optimistic flip, then re-sync once the override has armed/disarmed.
        setTileState(!running);
        new Handler(Looper.getMainLooper()).postDelayed(this::updateTile, 1500L);
    }

    private void updateTile() {
        setTileState(ProviderFrameSink.isOverrideArmed());
    }

    /** Ask SystemUI to re-run {@link #onStartListening()} so the tile reflects a change. */
    public static void requestRefresh(Context context) {
        requestListeningState(context, new ComponentName(context, StageTile.class));
    }

    private void setTileState(boolean active) {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
