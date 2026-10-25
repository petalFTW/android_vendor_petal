/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.agever;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import org.petalos.agever.output.ProviderFrameSink;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class StageTile extends TileService {
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private boolean mDestroyed;
    @Override public void onStartListening() { super.onStartListening(); refresh(); }
    @Override public void onTileAdded() { super.onTileAdded(); refresh(); }
    @Override public void onClick() {
        super.onClick();
        unlockAndRun(() -> mWorker.execute(() -> {
            int actual = ProviderFrameSink.queryOverrideState();
            mMain.post(() -> {
                if (mDestroyed) return;
                Intent intent = new Intent(this, StageService.class);
                boolean stop = actual > 0 || StageService.isCameraRequested();
                intent.setAction(stop ? StageService.ACTION_STOP : StageService.ACTION_START);
                try {
                    if (stop) startService(intent); else startForegroundService(intent);
                } catch (RuntimeException e) {
                    Tile tile = getQsTile();
                    if (tile != null) {
                        tile.setState(Tile.STATE_UNAVAILABLE);
                        tile.setSubtitle(getString(R.string.state_error));
                        tile.updateTile();
                    }
                }
            });
        }));
    }
    private void refresh() {
        mWorker.execute(() -> {
            int actual = ProviderFrameSink.queryOverrideState();
            mMain.post(() -> {
                if (mDestroyed) return;
                Tile tile = getQsTile();
                if (tile == null) return;
                tile.setState(actual < 0 ? Tile.STATE_UNAVAILABLE
                        : actual > 0 ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
                tile.setSubtitle(getString(actual == 1 ? R.string.state_camera_blocked
                        : actual == 2 ? R.string.state_camera_ready : StageService.state().label));
                tile.updateTile();
            });
        });
    }
    @Override public void onDestroy() {
        mDestroyed = true;
        mWorker.shutdownNow();
        mMain.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
    public static void requestRefresh(Context context) {
        requestListeningState(context, new ComponentName(context, StageTile.class));
    }
}
