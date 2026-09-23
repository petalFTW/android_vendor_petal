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

package org.petalos.cam.ui;

/**
 * Hand rolled critically damped spring, the same integrator the petalOS dynamic
 * island uses. Everything that moves in PetalCam goes through one of these so
 * the whole app shares the island's motion language.
 */
public final class PetalSpring {

    public float value;
    public float target;
    public float velocity;

    public PetalSpring(float initialValue) {
        value = initialValue;
        target = initialValue;
        velocity = 0f;
    }

    public void set(float targetValue) {
        target = targetValue;
    }

    public void snap(float v) {
        value = v;
        target = v;
        velocity = 0f;
    }

    public void step(float dt, float stiffness, float dampingRatio) {
        int steps = Math.max(1, (int) Math.ceil(dt / 0.004f));
        float h = dt / steps;
        float c = 2f * dampingRatio * (float) Math.sqrt(stiffness);
        for (int i = 0; i < steps; i++) {
            float a = -stiffness * (value - target) - c * velocity;
            velocity += a * h;
            value += velocity * h;
        }
    }

    public boolean resting() {
        return Math.abs(velocity) < 0.0015f && Math.abs(value - target) < 0.0015f;
    }
}
