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

package org.petalos.petaltuner.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.petalos.petaltuner.OverlayService;

public final class StateReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        TunerEngine engine = TunerEngine.get(context);
        boolean connected = Intent.ACTION_POWER_CONNECTED.equals(action);
        if (!connected && !Intent.ACTION_POWER_DISCONNECTED.equals(action)) return;
        if (connected && (engine.prefs().getBool(Prefs.CHARGE_LIMIT_ENABLED, false)
                || engine.prefs().getBool(Prefs.BYPASS_ENABLED, false))) {
            OverlayService.start(context);
        }
        PendingResult result = goAsync();
        engine.run(() -> {
            try {
                if (connected && engine.prefs().getBool(Prefs.BYPASS_AUTO, false)
                        && engine.foreground().isForegroundGame()) {
                    engine.prefs().putBool(Prefs.GM_BYPASS_BY_GM, true);
                }
                engine.onPowerChanged();
            } finally {
                result.finish();
            }
        });
    }
}
