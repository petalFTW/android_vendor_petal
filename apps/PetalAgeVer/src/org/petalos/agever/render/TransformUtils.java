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

package org.petalos.agever.render;

import android.opengl.Matrix;

/** Column-major 4x4 helpers shared by the avatar rig. */
final class TransformUtils {

    private TransformUtils() {
    }

    static float[] identity() {
        float[] m = new float[16];
        Matrix.setIdentityM(m, 0);
        return m;
    }

    /** Rotation matrix for the given euler angles (radians), Y-X-Z order. */
    static float[] euler(float pitch, float yaw, float roll) {
        float[] m = new float[16];
        Matrix.setIdentityM(m, 0);
        Matrix.rotateM(m, 0, (float) Math.toDegrees(yaw), 0f, 1f, 0f);
        Matrix.rotateM(m, 0, (float) Math.toDegrees(pitch), 1f, 0f, 0f);
        Matrix.rotateM(m, 0, (float) Math.toDegrees(roll), 0f, 0f, 1f);
        return m;
    }

    static float[] scale(float sx, float sy, float sz) {
        float[] m = new float[16];
        Matrix.setIdentityM(m, 0);
        Matrix.scaleM(m, 0, sx, sy, sz);
        return m;
    }

    static float[] translate(float x, float y, float z) {
        float[] m = new float[16];
        Matrix.setIdentityM(m, 0);
        Matrix.translateM(m, 0, x, y, z);
        return m;
    }

    /** {@code out = a * b}. */
    static void multiply(float[] out, float[] a, float[] b) {
        Matrix.multiplyMM(out, 0, a, 0, b, 0);
    }

    static float[] multiply(float[] a, float[] b) {
        float[] out = new float[16];
        multiply(out, a, b);
        return out;
    }
}
