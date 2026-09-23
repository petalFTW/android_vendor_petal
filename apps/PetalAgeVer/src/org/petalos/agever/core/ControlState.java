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

package org.petalos.agever.core;

/**
 * Thread-safe model-placement bus. The on-screen stick writes here; the render
 * thread reads immutable {@link ControlSnapshot}s.
 */
public final class ControlState {

    /** How far the stick can push the model, in world units. */
    private static final float OFFSET_X_RANGE = 0.35f;
    private static final float OFFSET_Y_RANGE = 0.5f;
    /** Max head yaw from the turn stick, radians (~50 degrees). */
    private static final float YAW_RANGE = 0.87f;

    private final Object mLock = new Object();

    private float mOffsetX;
    private float mOffsetY;
    private float mHeadYaw;

    /** Move stick axes in {@code [-1, 1]}. x = across, y = up (height). */
    public void applyJoystick(float x, float y) {
        synchronized (mLock) {
            mOffsetX = clamp(x, -1f, 1f) * OFFSET_X_RANGE;
            mOffsetY = clamp(y, -1f, 1f) * OFFSET_Y_RANGE;
        }
    }

    /** Turn stick, x in {@code [-1, 1]} -> head yaw left/right. */
    public void applyHeadYaw(float x) {
        synchronized (mLock) {
            mHeadYaw = clamp(x, -1f, 1f) * YAW_RANGE;
        }
    }

    public ControlSnapshot snapshot() {
        synchronized (mLock) {
            return new ControlSnapshot(
                    mHeadYaw, 0f, 0f, 0f, 0f,
                    mOffsetX, mOffsetY,
                    0f, 0f, 0f, 0f, 0f,
                    false, System.currentTimeMillis());
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
