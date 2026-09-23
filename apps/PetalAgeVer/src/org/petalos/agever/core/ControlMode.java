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

/** How the avatar is driven during a verification session. */
public enum ControlMode {
    /** On-screen joystick (overlay or in-app) moves the avatar. */
    JOYSTICK("joystick"),
    /** No input; the avatar holds still (or gently idles). */
    IDLE("idle");

    private final String mKey;

    ControlMode(String key) {
        mKey = key;
    }

    public String key() {
        return mKey;
    }

    public static ControlMode fromKey(String key) {
        if (key != null) {
            for (ControlMode m : values()) {
                if (m.mKey.equals(key)) {
                    return m;
                }
            }
        }
        return JOYSTICK;
    }
}
