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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * Horizon level. Uses the gravity vector so it works with any OEM sensor HAL.
 * The bar glows petal-teal once the device is within a degree of level.
 */
public class LevelView extends View implements SensorEventListener {

    private static final float LEVEL_TOLERANCE_DEG = 1.2f;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private SensorManager mSensorManager;
    private Sensor mGravity;
    // raw off the sensor vs what we actually draw
    private float mRawRoll;
    private float mShownRoll;
    private float mDensity;
    private boolean mActive;
    private boolean mTicking;
    private long mLastFrame;

    public LevelView(Context context) {
        this(context, null);
    }

    public LevelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(2f * mDensity);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setActive(boolean active) {
        mActive = active;
        setVisibility(active ? VISIBLE : GONE);
        if (active) {
            start();
        } else {
            stop();
        }
    }

    private void start() {
        if (mSensorManager == null) {
            mSensorManager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
            mGravity = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        }
        if (mSensorManager != null && mGravity != null) {
            mSensorManager.registerListener(this, mGravity, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    private void stop() {
        if (mSensorManager != null) {
            mSensorManager.unregisterListener(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float x = event.values[0];
        float y = event.values[1];
        mRawRoll = (float) Math.toDegrees(Math.atan2(x, y));
        startTicking();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    // chase the raw roll one frame at a time, sensor noise dont stand a chance
    private void startTicking() {
        if (mTicking) {
            return;
        }
        mTicking = true;
        mLastFrame = SystemClock.uptimeMillis();
        postOnAnimation(mTicker);
    }

    private final Runnable mTicker = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.05f, (now - mLastFrame) / 1000f);
            mLastFrame = now;
            float diff = mRawRoll - mShownRoll;
            if (diff > 180f) diff -= 360f;
            if (diff < -180f) diff += 360f;
            mShownRoll += diff * (1f - (float) Math.exp(-dt * 14f));
            if (mShownRoll > 180f) mShownRoll -= 360f;
            if (mShownRoll < -180f) mShownRoll += 360f;
            if (Math.abs(mRawRoll - mShownRoll) > 0.02f) {
                invalidate();
                postOnAnimation(this);
            } else {
                mShownRoll = mRawRoll;
                mTicking = false;
                invalidate();
            }
        }
    };

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mActive) {
            return;
        }
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float half = Math.min(getWidth(), getHeight()) * 0.32f;

        boolean level = Math.abs(mShownRoll) < LEVEL_TOLERANCE_DEG;
        int color = level ? 0xFF57E0D2 : 0xCCFFFFFF;
        mPaint.setColor(color);

        int save = canvas.save();
        canvas.rotate(-mShownRoll, cx, cy);
        canvas.drawLine(cx - half, cy, cx - half * 0.2f, cy, mPaint);
        canvas.drawLine(cx + half * 0.2f, cy, cx + half, cy, mPaint);
        if (level) {
            canvas.drawLine(cx - 6f * mDensity, cy, cx + 6f * mDensity, cy, mPaint);
        }
        canvas.restoreToCount(save);
    }
}
