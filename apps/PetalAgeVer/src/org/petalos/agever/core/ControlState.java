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

import android.os.SystemClock;

/**
 * Thread-safe model-placement bus. The on-screen stick writes here; the render
 * thread reads immutable {@link ControlSnapshot}s.
 */
public final class ControlState {

    /** How far the stick can push the model, in world units. */
    private static final float OFFSET_X_RANGE = 0.35f;
    private static final float OFFSET_Y_RANGE = 0.5f;
    /** Distance stick: how far the head can dolly toward/away from the camera. */
    private static final float OFFSET_Z_NEAR_RANGE = 0.8f;
    private static final float OFFSET_Z_FAR_RANGE = 3.0f;
    /** Max head yaw from the turn stick, radians (~50 degrees). */
    private static final float YAW_RANGE = 0.87f;

    private final Object mLock = new Object();

    private float mOffsetX;
    private float mOffsetY;
    private float mOffsetZ;
    private float mHeadYaw;

    private float mMouthOpen;
    private float mMouthSmile;
    private float mMouthFrown;
    private float mBrowUp;
    private float mBrowDown;
    private float mEyeWide;
    private float mMouthOpenTarget;
    private float mMouthSmileTarget;
    private float mMouthFrownTarget;
    private float mBrowUpTarget;
    private float mBrowDownTarget;
    private float mEyeWideTarget;
    private float mExpressionSpeed = 0.75f;
    private long mLastSnapshotMs;

    /** Move stick axes in {@code [-1, 1]}. x = across, y = up (height). */
    public void applyJoystick(float x, float y) {
        synchronized (mLock) {
            mOffsetX = clamp(x, -1f, 1f) * OFFSET_X_RANGE;
            mOffsetY = clamp(y, -1f, 1f) * OFFSET_Y_RANGE;
        }
    }

    /** Distance stick, y in {@code [-1, 1]}: up = closer to the camera. */
    public void applyDepth(float y) {
        synchronized (mLock) {
            float axis = clamp(y, -1f, 1f);
            mOffsetZ = axis * (axis >= 0f ? OFFSET_Z_NEAR_RANGE : OFFSET_Z_FAR_RANGE);
        }
    }

    /** Turn stick, x in {@code [-1, 1]} -> head yaw left/right. */
    public void applyHeadYaw(float x) {
        synchronized (mLock) {
            mHeadYaw = clamp(x, -1f, 1f) * YAW_RANGE;
        }
    }

    /**
     * Press/release an expression button. NEUTRAL wipes every expression; the
     * others latch their weights while held.
     */
    public void setExpression(Expression e, boolean on) {
        synchronized (mLock) {
            switch (e) {
                case SMILE:
                    mMouthSmileTarget = on ? 1f : 0f;
                    break;
                case FROWN:
                    mMouthFrownTarget = on ? 1f : 0f;
                    break;
                case MOUTH_OPEN:
                    mMouthOpenTarget = on ? 1f : 0f;
                    break;
                case BROW_UP:
                    mBrowUpTarget = on ? 1f : 0f;
                    break;
                case BROW_DOWN:
                    mBrowDownTarget = on ? 1f : 0f;
                    break;
                case SURPRISE:
                    mMouthOpenTarget = on ? 0.7f : 0f;
                    mBrowUpTarget = on ? 1f : 0f;
                    mEyeWideTarget = on ? 1f : 0f;
                    break;
                case NEUTRAL:
                default:
                    mMouthOpenTarget = 0f;
                    mMouthSmileTarget = 0f;
                    mMouthFrownTarget = 0f;
                    mBrowUpTarget = 0f;
                    mBrowDownTarget = 0f;
                    mEyeWideTarget = 0f;
                    break;
            }
        }
    }

    /** Slider position: zero takes two seconds, one takes a tenth of a second. */
    public void setExpressionSpeed(float speed) {
        synchronized (mLock) {
            mExpressionSpeed = clamp(speed, 0f, 1f);
        }
    }

    public float getExpressionSpeed() {
        synchronized (mLock) {
            return mExpressionSpeed;
        }
    }

    public ControlSnapshot snapshot() {
        synchronized (mLock) {
            long now = SystemClock.elapsedRealtime();
            long elapsed = mLastSnapshotMs == 0L ? 0L : Math.min(100L, now - mLastSnapshotMs);
            mLastSnapshotMs = now;
            float durationMs = 2000f - 1900f * mExpressionSpeed;
            float step = Math.max(0f, elapsed) / durationMs;
            mMouthOpen = approach(mMouthOpen, mMouthOpenTarget, step);
            mMouthSmile = approach(mMouthSmile, mMouthSmileTarget, step);
            mMouthFrown = approach(mMouthFrown, mMouthFrownTarget, step);
            mBrowUp = approach(mBrowUp, mBrowUpTarget, step);
            mBrowDown = approach(mBrowDown, mBrowDownTarget, step);
            mEyeWide = approach(mEyeWide, mEyeWideTarget, step);
            return new ControlSnapshot(
                    mHeadYaw, 0f, 0f, 0f, 0f,
                    mOffsetX, mOffsetY, mOffsetZ,
                    mMouthOpen, mMouthSmile, mMouthFrown,
                    mBrowUp, mBrowDown, mEyeWide,
                    0f, 0f,
                    false, System.currentTimeMillis());
        }
    }

    private static float approach(float current, float target, float step) {
        return current < target ? Math.min(target, current + step)
                : Math.max(target, current - step);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
