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
import android.view.MotionEvent;
import android.view.View;

/**
 * Vertical glass exposure rail that drops in beside the focus point. Range is
 * expressed in EV steps; the knob floats on a spring and the track fills with
 * the petal accent gradient as you move away from 0 EV.
 */
public class ExposureSliderView extends View {

    public interface OnExposureListener {
        void onExposureChanged(float ev);
    }

    private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mKnob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();

    private float mMin = -3f;
    private float mMax = 3f;
    private float mEv = 0f;
    private float mDensity;

    private final PetalSpring mKnobSpring = new PetalSpring(0f);
    private final PetalSpring mFade = new PetalSpring(0f);
    private long mLastFrame;
    private boolean mTicking;
    private boolean mDragging;

    private OnExposureListener mListener;

    public ExposureSliderView(Context context) {
        this(context, null);
    }

    public ExposureSliderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mTrack.setColor(0x40FFFFFF);
        mTrack.setStyle(Paint.Style.STROKE);
        mTrack.setStrokeWidth(3f * mDensity);
        mTrack.setStrokeCap(Paint.Cap.ROUND);
        mFill.setColor(0xFFFFC46B);
        mFill.setStyle(Paint.Style.STROKE);
        mFill.setStrokeWidth(3f * mDensity);
        mFill.setStrokeCap(Paint.Cap.ROUND);
        mKnob.setColor(0xFFFFFFFF);
        mText.setColor(0xFFFFFFFF);
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
    }

    public void setOnExposureListener(OnExposureListener l) {
        mListener = l;
    }

    public void setRange(float min, float max) {
        mMin = min;
        mMax = max;
        if (getHeight() > 0) {
            mKnobSpring.snap(yAt(mEv));
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // knob starts life at y=0 otherwise, which reads as pinned to the top
        mKnobSpring.snap(yAt(mEv));
    }

    public float getEv() {
        return mEv;
    }

    public void reveal() {
        mFade.set(1f);
        startTicking();
        removeCallbacks(mHide);
        postDelayed(mHide, 4000);
    }

    // yank it off screen now, pro mode doesnt want the ev rail sat on its sliders
    public void dismiss() {
        removeCallbacks(mHide);
        mDragging = false;
        mFade.set(0f);
        startTicking();
    }

    private final Runnable mHide = () -> {
        if (!mDragging) {
            mFade.set(0f);
            startTicking();
        }
    };

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
            mKnobSpring.step(dt, 380f, 0.8f);
            mFade.step(dt, 300f, 0.9f);
            invalidate();
            if (!mKnobSpring.resting() || !mFade.resting()) {
                postOnAnimation(this);
            } else {
                mTicking = false;
            }
        }
    };

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDragging = true;
                removeCallbacks(mHide);
                updateFromY(event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                updateFromY(event.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mDragging = false;
                postDelayed(mHide, 2500);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void updateFromY(float y) {
        float top = 26f * mDensity;
        float bottom = getHeight() - 26f * mDensity;
        float t = 1f - Math.max(0f, Math.min(1f, (y - top) / (bottom - top)));
        float ev = mMin + t * (mMax - mMin);
        if (ev != mEv) {
            mEv = ev;
            mKnobSpring.set(yAt(ev));
            startTicking();
            if (mListener != null) {
                mListener.onExposureChanged(mEv);
            }
        }
    }

    private float yAt(float ev) {
        float top = 26f * mDensity;
        float bottom = getHeight() - 26f * mDensity;
        float t = (ev - mMin) / (mMax - mMin);
        return bottom - t * (bottom - top);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float alpha = mFade.value;
        if (alpha <= 0.01f) {
            return;
        }
        int a = (int) (alpha * 255);
        float cx = getWidth() / 2f;
        float top = 26f * mDensity;
        float bottom = getHeight() - 26f * mDensity;

        mRect.set(cx - 18f * mDensity, 0, cx + 18f * mDensity, getHeight());
        float r = 18f * mDensity;
        Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
        glass.setColor((0x3D << 24) | 0x181A22);
        canvas.drawRoundRect(mRect, r, r, glass);
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(mDensity);
        rim.setColor(multiplyAlpha(0x59FFFFFF, alpha));
        canvas.drawRoundRect(mRect, r, r, rim);

        mTrack.setColor(multiplyAlpha(0x40FFFFFF, alpha));
        canvas.drawLine(cx, top, cx, bottom, mTrack);

        float knobY = mKnobSpring.value;
        float midY = yAt(0f);
        mFill.setColor(multiplyAlpha(0xFFFFC46B, alpha));
        canvas.drawLine(cx, midY, cx, knobY, mFill);

        mKnob.setColor(multiplyAlpha(0xFFFFFFFF, alpha));
        canvas.drawCircle(cx, knobY, 9f * mDensity, mKnob);

        mText.setColor(multiplyAlpha(0xFFFFFFFF, alpha));
        mText.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
        String label = (mEv > 0 ? "+" : "") + String.format(java.util.Locale.US, "%.1f", mEv);
        canvas.drawText(label, cx, 18f * mDensity, mText);
        canvas.drawText("+", cx, bottom + 18f * mDensity, mText);
    }

    private static int multiplyAlpha(int color, float alpha) {
        int a = (int) (((color >>> 24) & 0xFF) * alpha);
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
