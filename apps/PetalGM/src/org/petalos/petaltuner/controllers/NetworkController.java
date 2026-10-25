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

package org.petalos.petaltuner.controllers;

import android.content.Context;
import android.provider.Settings;

import org.petalos.petaltuner.core.StateBackup;
import org.petalos.petaltuner.core.Sysfs;

public final class NetworkController {

    private static final String NET = "/proc/sys/net";
    private static final String CORE = NET + "/core";
    private static final String IPV4 = NET + "/ipv4";

    private final Context mContext;
    private final StateBackup mBackup;

    public NetworkController(Context context) {
        mContext = context.getApplicationContext();
        mBackup = new StateBackup(context);
    }

    public String getCongestion() {
        return Sysfs.read(IPV4 + "/tcp_congestion_control");
    }

    public String[] getAvailableCongestions() {
        String value = Sysfs.read(IPV4 + "/tcp_available_congestion_control");
        return value == null ? new String[0] : value.trim().split("\\s+");
    }

    public boolean setCongestion(String algorithm) {
        mBackup.capture(IPV4 + "/tcp_congestion_control");
        return Sysfs.write(IPV4 + "/tcp_congestion_control", algorithm);
    }

    public boolean applyLowLatency() {
        boolean ok = false;
        ok |= write(CORE + "/rmem_max", "8388608");
        ok |= write(CORE + "/wmem_max", "8388608");
        ok |= write(CORE + "/rmem_default", "1048576");
        ok |= write(CORE + "/wmem_default", "1048576");
        ok |= write(CORE + "/netdev_max_backlog", "5000");
        ok |= write(CORE + "/somaxconn", "1024");
        ok |= write(IPV4 + "/tcp_fastopen", "3");
        ok |= write(IPV4 + "/tcp_syncookies", "0");
        return ok;
    }

    private boolean write(String path, String value) {
        if (!Sysfs.exists(path)) {
            return false;
        }
        mBackup.capture(path);
        return Sysfs.write(path, value);
    }

    public void setWifiScanEnabled(boolean enabled) {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.WIFI_SCAN_ALWAYS_AVAILABLE, enabled ? 1 : 0);
    }

    public void setBleScanEnabled(boolean enabled) {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE, enabled ? 1 : 0);
    }
}
