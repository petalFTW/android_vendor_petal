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
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * Tap to focus reticle. On tap the ring drops in oversized then springs down to
 * a tight circle; the petal engine then either settles it to warm gold (locked)
 * or flashes it pink (failed).
 */
public class FocusRingView extends View {

    private static final int SUCCESS = 0xFFFFE08A;
    private static final int FAIL = 0xFFFF6FAE;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PetalSpring mRadius = new PetalSpring(0f);
    private final PetalSpring mAlpha = new PetalSpring(0f);

    private float mX;
    private float mY;
    private float mDensity;
    private int mColor = SUCCESS;
    private boolean mLocked;
    private long mLastFrame;
    private boolean mTicking;

    public FocusRingView(Context context) {
        this(context, null);
    }

    public FocusRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(2f * mDensity);
    }

    public void focusAt(float x, float y) {
        mX = x;
        mY = y;
        mColor = 0xFFFFFFFF;
        mLocked = false;
        mRadius.snap(58f * mDensity);
        mRadius.set(30f * mDensity);
        mAlpha.snap(1f);
        scheduleDismiss();
        startTicking();
    }

    // tap and hold: ring goes gold and grows a padlock, sticks around
    public void lockAt(float x, float y) {
        mX = x;
        mY = y;
        mColor = SUCCESS;
        mLocked = true;
        mRadius.snap(58f * mDensity);
        mRadius.set(30f * mDensity);
        mAlpha.snap(1f);
        removeCallbacks(mAutoDismiss);
        startTicking();
    }

    public void unlock() {
        mLocked = false;
    }

    public void focusSuccess() {
        mColor = SUCCESS;
        mRadius.set(24f * mDensity);
        scheduleDismiss();
        startTicking();
    }

    public void focusFail() {
        mColor = FAIL;
        mRadius.set(24f * mDensity);
        scheduleDismiss();
        startTicking();
    }

    public void dismiss() {
        mLocked = false;
        mAlpha.set(0f);
        startTicking();
    }

    // the ring used to hang around forever like a bad smell
    private void scheduleDismiss() {
        if (mLocked) {
            // locked means stay put until the user taps again
            return;
        }
        removeCallbacks(mAutoDismiss);
        postDelayed(mAutoDismiss, 2200);
    }

    private final Runnable mAutoDismiss = this::dismiss;

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
            float dt = (now - mLastFrame) / 1000f;
            mLastFrame = now;
            mRadius.step(dt, 320f, 0.68f);
            mAlpha.step(dt, 260f, 0.9f);
            invalidate();
            if (!mRadius.resting() || !mAlpha.resting()) {
                postOnAnimation(this);
            } else {
                mTicking = false;
            }
        }
    };

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float alpha = mAlpha.value;
        if (alpha <= 0.01f) {
            return;
        }
        int a = (int) (alpha * 255);
        int rgb = mColor & 0x00FFFFFF;
        mPaint.setColor(rgb | (a << 24));
        canvas.drawCircle(mX, mY, mRadius.value, mPaint);
        if (mLocked) {
            drawLock(canvas, rgb | (a << 24));
        } else {
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(rgb | ((a / 3) << 24));
            canvas.drawCircle(mX, mY, 3f * mDensity, mPaint);
            mPaint.setStyle(Paint.Style.STROKE);
        }
    }

    // little padlock in the middle of the reticle
    private void drawLock(Canvas canvas, int color) {
        float s = 7f * mDensity;
        mPaint.setColor(color);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(1.9f * mDensity);
        RectF arch = new RectF(mX - s * 0.62f, mY - s * 1.28f, mX + s * 0.62f, mY + s * 0.22f);
        canvas.drawArc(arch, 180f, 180f, false, mPaint);
        mPaint.setStyle(Paint.Style.FILL);
        RectF body = new RectF(mX - s, mY - s * 0.25f, mX + s, mY + s * 1.15f);
        canvas.drawRoundRect(body, 2f * mDensity, 2f * mDensity, mPaint);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(2f * mDensity);
    }
}
