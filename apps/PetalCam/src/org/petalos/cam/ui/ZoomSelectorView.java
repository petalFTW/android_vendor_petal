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
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Row of zoom ratio chips (0.6x / 1x / 2x ...). Every chip is the same size;
 * one white blob lives on the row and springs across to whichever chip is
 * selected, stretching on the way and settling back into a pill.
 */
public class ZoomSelectorView extends View {

    public interface OnZoomSelectedListener {
        void onZoomSelected(float ratio);
    }

    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final RectF mBlob = new RectF();

    private final List<Float> mRatios = new ArrayList<>();
    private final List<String> mLabels = new ArrayList<>();

    private int mSelected = 0;
    // one blob for the whole row, springs from chip to chip
    private final PetalSpring mBlobX = new PetalSpring(0f);
    private final PetalSpring mPress = new PetalSpring(0f);
    private int mPressedIndex = -1;

    private float mDensity;
    private float mChipW;
    // room either side so the blob never gets clipped on the 0.6x / 20x ends
    private float mPad;
    // last ratio the user picked, so a rebind rebuild keeps sitting on it
    private float mCurrentRatio = Float.NaN;
    private boolean mTicking;
    private long mLastFrame;

    private OnZoomSelectedListener mListener;

    public ZoomSelectorView(Context context) {
        this(context, null);
    }

    public ZoomSelectorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mPad = 18f * mDensity;
        mText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
        mFill.setStyle(Paint.Style.FILL);
    }

    public void setOnZoomSelectedListener(OnZoomSelectedListener l) {
        mListener = l;
    }

    private float gap() {
        return 8f * mDensity;
    }

    private void measureChips() {
        float widest = 0f;
        for (String s : mLabels) {
            widest = Math.max(widest, mText.measureText(s));
        }
        // same capsule for all of them, 10x included
        mChipW = Math.max(widest + 20f * mDensity, 34f * mDensity);
    }

    public void setRatios(List<Float> ratios) {
        boolean first = mLabels.isEmpty();
        mRatios.clear();
        mLabels.clear();
        for (Float r : ratios) {
            mRatios.add(r);
            mLabels.add(trim(r));
        }
        measureChips();
        // a lens rebind rebuilds the chips; keep the blob on whatever we had
        int target = 0;
        if (!Float.isNaN(mCurrentRatio) && !mRatios.isEmpty()) {
            float best = Float.MAX_VALUE;
            for (int i = 0; i < mRatios.size(); i++) {
                float d = Math.abs(mRatios.get(i) - mCurrentRatio);
                if (d < best) {
                    best = d;
                    target = i;
                }
            }
        }
        mSelected = target;
        if (first) {
            mBlobX.snap(centerX(target));
        } else {
            mBlobX.set(centerX(target));
            startTicking();
        }
        mPressedIndex = -1;
        mPress.snap(0f);
        requestLayout();
        invalidate();
    }

    private static String trim(float r) {
        if (Math.abs(r - Math.round(r)) < 0.05f) {
            return String.valueOf(Math.round(r));
        }
        return String.format(java.util.Locale.US, "%.1f", r);
    }

    private float centerX(int index) {
        return mPad + index * (mChipW + gap()) + mChipW / 2f;
    }

    public void setSelected(float ratio) {
        int best = 0;
        float bestDelta = Float.MAX_VALUE;
        for (int i = 0; i < mRatios.size(); i++) {
            float d = Math.abs(mRatios.get(i) - ratio);
            if (d < bestDelta) {
                bestDelta = d;
                best = i;
            }
        }
        mCurrentRatio = ratio;
        if (best != mSelected) {
            mSelected = best;
        }
        // always retarget, the chip list may have been rebuilt under us
        mBlobX.set(centerX(best));
        startTicking();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float h = 44f * mDensity;
        setMeasuredDimension(resolveSize((int) totalWidth(), widthMeasureSpec),
                resolveSize((int) h, heightMeasureSpec));
    }

    private float totalWidth() {
        if (mLabels.isEmpty()) {
            return 0f;
        }
        return 2f * mPad + mLabels.size() * mChipW + (mLabels.size() - 1) * gap();
    }

    private int hitTest(float x) {
        for (int i = 0; i < mLabels.size(); i++) {
            float left = mPad + i * (mChipW + gap());
            if (x >= left && x <= left + mChipW) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mPressedIndex = hitTest(event.getX());
                if (mPressedIndex >= 0) {
                    mPress.set(1f);
                    startTicking();
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mPressedIndex >= 0 && hitTest(event.getX()) != mPressedIndex) {
                    mPressedIndex = -1;
                    mPress.set(0f);
                    startTicking();
                }
                return true;
            case MotionEvent.ACTION_UP: {
                if (mPressedIndex >= 0) {
                    mPress.set(0f);
                    startTicking();
                }
                int hit = hitTest(event.getX());
                if (hit >= 0) {
                    mSelected = hit;
                    mCurrentRatio = mRatios.get(hit);
                    mBlobX.set(centerX(hit));
                    startTicking();
                    invalidate();
                    if (mListener != null) {
                        mListener.onZoomSelected(mRatios.get(hit));
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                if (mPressedIndex >= 0) {
                    mPressedIndex = -1;
                    mPress.set(0f);
                    startTicking();
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
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
            float dt = Math.min(0.05f, (now - mLastFrame) / 1000f);
            mLastFrame = now;
            mBlobX.step(dt, 300f, 0.85f);
            mPress.step(dt, 520f, 0.42f);
            if (!mPress.resting()) {
                // keep the press alive, blob handles its own rest below
            } else {
                mPressedIndex = -1;
            }
            invalidate();
            if (!mBlobX.resting() || !mPress.resting()) {
                postOnAnimation(this);
            } else {
                mTicking = false;
            }
        }
    };

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mLabels.isEmpty()) {
            return;
        }
        float h = getHeight();
        float cy = h / 2f;
        float chipH = h - 4f * mDensity;
        float chipTop = cy - chipH / 2f;

        // 1) the dark chips, all identical
        for (int i = 0; i < mLabels.size(); i++) {
            mRect.set(mPad + i * (mChipW + gap()), chipTop,
                    mPad + i * (mChipW + gap()) + mChipW, chipTop + chipH);
            float r = mRect.height() / 2f;
            int save = canvas.save();
            float press = i == mPressedIndex ? mPress.value : 0f;
            if (press != 0f) {
                canvas.scale(1f + 0.16f * press, 1f - 0.12f * press,
                        mRect.centerX(), mRect.centerY());
            }
            mFill.setColor(0x99101218);
            canvas.drawRoundRect(mRect, r, r, mFill);
            canvas.restoreToCount(save);
        }

        // 2) the one white blob, stretched by how fast it is travelling
        float v = Math.abs(mBlobX.velocity);
        float st = Math.min(1f, v / (mDensity * 400f));
        float halfW = mChipW / 2f + 4f * mDensity + 12f * mDensity * st;
        float halfH = chipH / 2f - 4f * mDensity * st;
        float bx = mBlobX.value;
        mBlob.set(bx - halfW, cy - halfH, bx + halfW, cy + halfH);
        mFill.setColor(0xFFFFFFFF);
        canvas.drawRoundRect(mBlob, halfH, halfH, mFill);

        // 3) labels, dark where the blob is sitting on them
        for (int i = 0; i < mLabels.size(); i++) {
            float d = Math.abs(centerX(i) - bx);
            float cover = Math.max(0f, Math.min(1f, 1f - d / (mChipW * 0.9f)));
            int save = canvas.save();
            float press = i == mPressedIndex ? mPress.value : 0f;
            if (press != 0f) {
                canvas.scale(1f + 0.16f * press, 1f - 0.12f * press,
                        centerX(i), cy);
            }
            mText.setColor(androidx.core.graphics.ColorUtils.blendARGB(
                    0xFFFFFFFF, 0xFF15171C, cover));
            canvas.drawText(mLabels.get(i), centerX(i),
                    cy - (mText.ascent() + mText.descent()) / 2f, mText);
            canvas.restoreToCount(save);
        }
    }
}
