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

import android.app.Application;

/** Process-wide entry point. Kept intentionally tiny. */
public final class PetalAgeVerApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        final long startedAt = System.currentTimeMillis();
        new Thread(() -> {
            java.io.File[] pending = new java.io.File(getFilesDir(), "avatars")
                    .listFiles((dir, name) -> name.endsWith(".pending"));
            if (pending != null) for (java.io.File file : pending) {
                if (file.lastModified() < startedAt) file.delete();
            }
        }, "agever-import-cleanup").start();
    }
}
