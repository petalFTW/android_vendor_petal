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
 * Immutable pose + expression sample consumed by the renderer.
 *
 * <p>Angles are radians. Expression weights are {@code 0..1}. The renderer never
 * touches {@link ControlState}; it only ever reads snapshots, so the control and
 * render threads never share mutable state.
 */
public final class ControlSnapshot {

    public final float headYaw;
    public final float headPitch;
    public final float headRoll;
    public final float bodyYaw;
    public final float bodyPitch;
    /** Model placement from the on-screen stick: x = across, y = height. */
    public final float offsetX;
    public final float offsetY;
    /** Distance stick: + = closer to the virtual camera, - = further away. */
    public final float offsetZ;
    public final float mouthOpen;
    public final float mouthSmile;
    public final float mouthFrown;
    public final float browUp;
    public final float browDown;
    public final float eyeWide;
    public final float eyeBlinkLeft;
    public final float eyeBlinkRight;
    public final boolean faceTracked;
    public final long timestampMs;

    public ControlSnapshot(
            float headYaw,
            float headPitch,
            float headRoll,
            float bodyYaw,
            float bodyPitch,
            float offsetX,
            float offsetY,
            float offsetZ,
            float mouthOpen,
            float mouthSmile,
            float mouthFrown,
            float browUp,
            float browDown,
            float eyeWide,
            float eyeBlinkLeft,
            float eyeBlinkRight,
            boolean faceTracked,
            long timestampMs) {
        this.headYaw = headYaw;
        this.headPitch = headPitch;
        this.headRoll = headRoll;
        this.bodyYaw = bodyYaw;
        this.bodyPitch = bodyPitch;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.mouthOpen = mouthOpen;
        this.mouthSmile = mouthSmile;
        this.mouthFrown = mouthFrown;
        this.browUp = browUp;
        this.browDown = browDown;
        this.eyeWide = eyeWide;
        this.eyeBlinkLeft = eyeBlinkLeft;
        this.eyeBlinkRight = eyeBlinkRight;
        this.faceTracked = faceTracked;
        this.timestampMs = timestampMs;
    }

    public static ControlSnapshot neutral() {
        return new ControlSnapshot(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, false, 0L);
    }
}
