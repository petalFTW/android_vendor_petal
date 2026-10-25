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

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

public final class StateBackup {

    private final SharedPreferences mStore;

    public StateBackup(Context context) {
        mStore = context.getSharedPreferences(Prefs.BACKUP_FILE, Context.MODE_PRIVATE);
    }

    public void capture(String path) {
        if (path == null || mStore.contains(path)) {
            return;
        }
        String value = Sysfs.read(path);
        if (value != null) {
            mStore.edit().putString(path, value).apply();
        }
    }

    public String original(String path) {
        return mStore.getString(path, null);
    }

    public boolean restore(String path) {
        String value = mStore.getString(path, null);
        if (value == null) {
            return false;
        }
        return Sysfs.write(path, value);
    }

    public boolean restoreAll() {
        boolean ok = true;
        for (Map.Entry<String, ?> entry : mStore.getAll().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                if (!Sysfs.write(entry.getKey(), (String) value)) {
                    ok = false;
                }
            }
        }
        mStore.edit().clear().apply();
        return ok;
    }

    public void clear() {
        mStore.edit().clear().apply();
    }
}
