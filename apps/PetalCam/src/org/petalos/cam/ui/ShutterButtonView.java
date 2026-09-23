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
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * petalOS shutter. A glass ring around a spring driven core that squishes on
 * press, flips to a record square in video, and morphs to a red stop chip while
 * recording. Motion comes straight from the island's spring constants.
 */
public class ShutterButtonView extends View {

    public interface OnShutterListener {
        void onShutterClick();
        void onShutterLongPress();
        void onShutterRelease();
    }

    private static final float SPRING_STIFFNESS = 520f;
    private static final float SPRING_DAMPING = 0.72f;

    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCore = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mCoreRect = new RectF();

    private final PetalSpring mPress = new PetalSpring(1f);
    private final PetalSpring mMorph = new PetalSpring(0f);
    private final PetalSpring mRecPulse = new PetalSpring(1f);

    private boolean mVideoMode;
    private boolean mRecording;
    private boolean mEnabled = true;
    private float mDensity;

    private long mLastFrame;
    private boolean mTicking;
    private boolean mLongPressFired;

    private OnShutterListener mListener;

    public ShutterButtonView(Context context) {
        this(context, null);
    }

    public ShutterButtonView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mRing.setStyle(Paint.Style.STROKE);
        mRing.setStrokeWidth(2f * mDensity);
        mRing.setColor(0xFFFFFFFF);
        mCore.setStyle(Paint.Style.FILL);
        mCore.setColor(0xFFFFFFFF);
        setClickable(true);
    }

    public void setOnShutterListener(OnShutterListener l) {
        mListener = l;
    }

    public void setVideoMode(boolean video) {
        if (mVideoMode != video) {
            mVideoMode = video;
            mMorph.set(video ? (mRecording ? 1f : 0f) : 0f);
            startTicking();
        }
    }

    public void setRecording(boolean recording) {
        if (mRecording != recording) {
            mRecording = recording;
            mMorph.set(recording ? 1f : 0f);
            startTicking();
        }
    }

    public void setShutterEnabled(boolean enabled) {
        mEnabled = enabled;
        setAlpha(enabled ? 1f : 0.45f);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!mEnabled) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mLongPressFired = false;
                mPress.set(0.86f);
                startTicking();
                postDelayed(mLongPress, 650);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(mLongPress);
                boolean wasLong = mLongPressFired;
                mPress.set(1f);
                startTicking();
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    performClick();
                    if (wasLong) {
                        if (mListener != null) {
                            mListener.onShutterRelease();
                        }
                    } else if (mListener != null) {
                        mListener.onShutterClick();
                    }
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private final Runnable mLongPress = () -> {
        mLongPressFired = true;
        if (mListener != null) {
            mListener.onShutterLongPress();
        }
    };

    @Override
    public boolean performClick() {
        return super.performClick();
    }

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
            mPress.step(dt, SPRING_STIFFNESS, SPRING_DAMPING);
            mMorph.step(dt, 300f, 0.62f);
            mRecPulse.step(dt, 260f, 0.40f);
            invalidate();
            if (!mPress.resting() || !mMorph.resting() || !mRecPulse.resting()) {
                postOnAnimation(this);
            } else {
                mTicking = false;
            }
        }
    };

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float w = getWidth();
        final float h = getHeight();
        final float cx = w / 2f;
        final float cy = h / 2f;
        final float outer = Math.min(w, h) / 2f - mRing.getStrokeWidth();
        final float coreR = outer - 8f * mDensity;

        // Outer glass ring.
        mRing.setColor(0xFFFFFFFF);
        canvas.drawCircle(cx, cy, outer, mRing);

        if (mRecording) {
            mRecPulse.set(1f);
        }
        final float press = mPress.value;
        final float morph = mMorph.value;

        // Core: circle -> rounded square as morph goes 0..1.
        final float safePress = press;
        final float radius = coreR * safePress;
        final float squareHalf = coreR * 0.42f * safePress;
        final float corner = lerp(radius, 8f * mDensity, morph);
        final float half = lerp(radius, squareHalf, morph);

        mCore.setColor(mRecording ? 0xFFFF4D6D : 0xFFFFFFFF);
        mCoreRect.set(cx - half, cy - half, cx + half, cy + half);
        canvas.drawRoundRect(mCoreRect, corner, corner, mCore);

        // Recording pulse halo.
        if (mRecording) {
            float t = (SystemClock.uptimeMillis() % 1200L) / 1200f;
            float pulse = 1f + 0.06f * (float) Math.sin(t * Math.PI * 2);
            mRing.setColor(0xFFFF4D6D);
            mRing.setAlpha(0x66);
            canvas.drawCircle(cx, cy, outer * pulse, mRing);
            mRing.setAlpha(0xFF);
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
