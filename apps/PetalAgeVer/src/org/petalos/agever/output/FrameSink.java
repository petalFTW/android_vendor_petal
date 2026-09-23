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

package org.petalos.agever.output;

import android.view.Surface;

import androidx.annotation.NonNull;

/**
 * Where the rendered avatar frames go.
 *
 * <p>The renderer only ever knows about a {@link Surface} it can draw into. That
 * keeps the same 3D pipeline usable for the on-screen preview today and for the
 * camera-provider HAL input surface later, with no renderer changes.
 */
public interface FrameSink {

    interface Callback {
        /** The sink produced a surface the stage should render into. */
        void onSurfaceReady(@NonNull Surface surface, int width, int height);

        /** The previous surface is gone; the stage must stop drawing. */
        void onSurfaceLost();
    }

    /** Starts producing surfaces. Must be called on the main thread. */
    void start(@NonNull Callback callback);

    /** Stops and releases everything. Must be called on the main thread. */
    void stop();

    boolean isActive();

    /** Human-readable id for logs / status UI. */
    @NonNull
    String name();
}
