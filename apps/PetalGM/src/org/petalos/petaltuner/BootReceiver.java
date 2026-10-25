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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.petalos.petaltuner.core.GmConfig;
import org.petalos.petaltuner.core.Prefs;
import org.petalos.petaltuner.core.TunerEngine;

public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return;
        }
        TunerEngine engine = TunerEngine.get(context);
        // boot isn't a new game session, bin the old focus state
        engine.shutdownSession();
        engine.setFocusEnabled(false);
        GmConfig config = new GmConfig(context);
        config.requestGrants();
        if (config.hasWork()) {
            OverlayService.start(context);
        }
        engine.applyDisplayState();
        engine.applyNetworkState();
        engine.applyMemoryState();
    }
}
