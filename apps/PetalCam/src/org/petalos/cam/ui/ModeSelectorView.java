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
 * Horizontal mode strip. All capsules are the same height; one white blob
 * lives on the strip and springs across to the selected mode, stretching while
 * it travels and settling into the new capsule. Same mechanic as the zoom row.
 */
public class ModeSelectorView extends View {

    public interface OnModeSelectedListener {
        void onModeSelected(int index);
    }

    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final RectF mBlob = new RectF();

    private final List<String> mModes = new ArrayList<>();
    private int mSelected = 0;

    private final PetalSpring mScroll = new PetalSpring(0f);
    private final PetalSpring mPress = new PetalSpring(0f);
    private int mPressedIndex = -1;

    // blob tracks from the old chip to the new one, in content space
    private final PetalSpring mBlobT = new PetalSpring(1f);
    private float mBlobFromL, mBlobFromR, mBlobToL, mBlobToR;

    private float mDensity;
    private long mLastFrame;
    private boolean mTicking;

    private float mDownX;
    private float mDownScroll;
    private boolean mDragging;
    // finger is down; freeze the auto-centering so the strip cant slide out
    // from under the tap between down and up
    private boolean mTouchDown;
    private int mActivePointerId = -1;

    private OnModeSelectedListener mListener;

    public ModeSelectorView(Context context) {
        this(context, null);
    }

    public ModeSelectorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mText.setLetterSpacing(0.06f);
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setTextSize(11.5f * getResources().getDisplayMetrics().scaledDensity);
        mFill.setStyle(Paint.Style.FILL);
    }

    public void setOnModeSelectedListener(OnModeSelectedListener l) {
        mListener = l;
    }

    public void setModes(List<String> modes) {
        boolean same = modes.size() == mModes.size();
        if (same) {
            for (int i = 0; i < modes.size(); i++) {
                if (!modes.get(i).equals(mModes.get(i))) {
                    same = false;
                    break;
                }
            }
        }
        mModes.clear();
        mModes.addAll(modes);
        if (mSelected < 0 || mSelected >= mModes.size()) {
            mSelected = 0;
        }
        mPressedIndex = -1;
        mPress.snap(0f);
        if (same) {
            // list didn't change (camera rebind); leave the blob mid-flight
            return;
        }
        mScroll.snap(scrollFor(mSelected));
        snapBlob(mSelected);
        invalidate();
    }

    public int getSelected() {
        return mSelected;
    }

    public String getSelectedMode() {
        if (mSelected >= 0 && mSelected < mModes.size()) {
            return mModes.get(mSelected);
        }
        return null;
    }

    public void setSelected(int index) {
        setSelected(index, true);
    }

    public void setSelected(int index, boolean animate) {
        if (index < 0 || index >= mModes.size()) {
            return;
        }
        float target = scrollFor(index);
        if (animate && index == mSelected && mBlobT.value >= 1f) {
            // already parked here, a rebind is just re-announcing it
            mBlobToL = chipLeft(index);
            mBlobToR = chipLeft(index) + chipWidth(index);
            mScroll.set(target);
            startTicking();
            return;
        }
        if (animate) {
            // start from wherever the blob is right now so mid-flight retargets
            mBlobFromL = blobLeft();
            mBlobFromR = blobRight();
        }
        mSelected = index;
        mBlobToL = chipLeft(index);
        mBlobToR = chipLeft(index) + chipWidth(index);
        if (animate) {
            // restart the 0..1 run from the captured position, never jump
            mBlobT.snap(0f);
            mBlobT.set(1f);
        } else {
            mBlobT.snap(1f);
        }
        if (animate) {
            mScroll.set(target);
        } else {
            mScroll.snap(target);
        }
        startTicking();
        invalidate();
    }

    private void snapBlob(int index) {
        mBlobT.snap(1f);
        mBlobFromL = mBlobToL = chipLeft(index);
        mBlobFromR = mBlobToR = chipLeft(index) + chipWidth(index);
    }

    private float blobLeft() {
        return mBlobFromL + (mBlobToL - mBlobFromL) * mBlobT.value;
    }

    private float blobRight() {
        return mBlobFromR + (mBlobToR - mBlobFromR) * mBlobT.value;
    }

    // ---- live geometry --------------------------------------------------

    private float gap() {
        return 8f * mDensity;
    }

    private float chipWidth(int i) {
        return mText.measureText(mModes.get(i)) + 18f * mDensity;
    }

    private float chipLeft(int i) {
        float cursor = 0f;
        for (int j = 0; j < i; j++) {
            cursor += chipWidth(j) + gap();
        }
        return cursor;
    }

    private float contentWidth() {
        int n = mModes.size();
        if (n == 0) {
            return 0f;
        }
        return chipLeft(n - 1) + chipWidth(n - 1);
    }

    private float centerOf(int index) {
        return chipLeft(index) + chipWidth(index) / 2f;
    }

    private float maxScroll() {
        return Math.max(0f, contentWidth() - getWidth());
    }

    // chips are centred in a match_parent view, so screen->content needs this
    private float leftOffset() {
        float cw = contentWidth();
        return cw <= getWidth() ? (getWidth() - cw) / 2f : 0f;
    }

    private float scrollFor(int index) {
        float cw = contentWidth();
        if (cw <= getWidth()) {
            return 0f;
        }
        float target = centerOf(index) - getWidth() / 2f;
        return Math.max(0f, Math.min(target, cw - getWidth()));
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = (int) (40 * mDensity);
        int h = resolveSize(height, heightMeasureSpec);
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mScroll.snap(scrollFor(mSelected));
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mActivePointerId = event.getPointerId(0);
                mDownX = event.getX();
                mDownScroll = mScroll.value;
                mDragging = false;
                mTouchDown = true;
                mScroll.snap(mScroll.value);
                mPressedIndex = chipAt(event.getX());
                if (mPressedIndex >= 0) {
                    mPress.set(1f);
                    startTicking();
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                int idx = event.findPointerIndex(mActivePointerId);
                if (idx < 0) {
                    return true;
                }
                float dx = event.getX(idx) - mDownX;
                if (Math.abs(dx) > 8 * mDensity) {
                    mDragging = true;
                    mPressedIndex = -1;
                }
                float max = maxScroll();
                float v = Math.max(0f, Math.min(mDownScroll - dx, max));
                mScroll.snap(v);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                releasePress();
                mTouchDown = false;
                if (!mDragging) {
                    // a tap anywhere near a chip counts, gaps included
                    int hit = chipAt(event.getX());
                    if (hit >= 0 && hit < mModes.size()) {
                        setSelected(hit);
                        if (mListener != null) {
                            mListener.onModeSelected(hit);
                        }
                    }
                }
                mDragging = false;
                startTicking();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                releasePress();
                mTouchDown = false;
                mDragging = false;
                startTicking();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void releasePress() {
        if (mPressedIndex >= 0) {
            mPress.set(0f);
            startTicking();
        }
    }

    // whatever chip the tap is actually over; in a gap, the nearer edge wins.
    // never compare centre distances, that steals taps from wide neighbours.
    private int chipAt(float x) {
        float scroll = Math.max(0f, Math.min(mScroll.value, maxScroll()));
        // drop the centering offset, otherwise taps land one chip to the right
        float contentX = x - leftOffset() + scroll;
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < mModes.size(); i++) {
            float left = chipLeft(i);
            float right = left + chipWidth(i);
            float d;
            if (contentX < left) {
                d = left - contentX;
            } else if (contentX > right) {
                d = contentX - right;
            } else {
                return i;
            }
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
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
            boolean moving = false;
            mBlobT.step(dt, 300f, 0.85f);
            if (!mBlobT.resting()) {
                moving = true;
            }
            mPress.step(dt, 520f, 0.42f);
            if (!mPress.resting()) {
                moving = true;
            } else {
                mPressedIndex = -1;
            }
            if (!mDragging && !mTouchDown) {
                mScroll.set(scrollFor(mSelected));
            }
            mScroll.step(dt, 300f, 0.9f);
            if (!mScroll.resting()) {
                moving = true;
            }
            invalidate();
            if (moving) {
                postOnAnimation(this);
            } else {
                mTicking = false;
            }
        }
    };

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mModes.isEmpty()) {
            return;
        }

        final float viewW = getWidth();
        final float cw = contentWidth();
        final float leftOffset = cw <= viewW ? (viewW - cw) / 2f : 0f;
        final float scroll = clamp(mScroll.value, 0f, Math.max(0f, cw - viewW));

        final float h = getHeight();
        final float cy = h / 2f;
        final float top = 6f * mDensity;
        final float bottom = h - 6f * mDensity;

        // dark capsules
        for (int i = 0; i < mModes.size(); i++) {
            float left = leftOffset + chipLeft(i) - scroll;
            mRect.set(left, top, left + chipWidth(i), bottom);
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

        // the white blob, stretched by travel speed
        float st = Math.min(1f, Math.abs(mBlobT.velocity) / 6f);
        float ex = 10f * mDensity * st;
        float bl = leftOffset + blobLeft() - scroll - ex;
        float br = leftOffset + blobRight() - scroll + ex;
        mBlob.set(bl, top - 2f * mDensity, br, bottom + 2f * mDensity);
        float br2 = mBlob.height() / 2f;
        mFill.setColor(0xFFFFFFFF);
        canvas.drawRoundRect(mBlob, br2, br2, mFill);

        // labels crossfade to dark under the blob
        for (int i = 0; i < mModes.size(); i++) {
            float left = leftOffset + chipLeft(i) - scroll;
            float right = left + chipWidth(i);
            float ix = Math.max(0f, Math.min(right, mBlob.right) - Math.max(left, mBlob.left));
            float cover = Math.min(1f, ix / Math.max(1f, chipWidth(i)));
            int save = canvas.save();
            float press = i == mPressedIndex ? mPress.value : 0f;
            if (press != 0f) {
                canvas.scale(1f + 0.16f * press, 1f - 0.12f * press,
                        (left + right) / 2f, cy);
            }
            mText.setColor(androidx.core.graphics.ColorUtils.blendARGB(
                    0xFFFFFFFF, 0xFF15171C, cover));
            canvas.drawText(mModes.get(i), (left + right) / 2f,
                    cy - (mText.ascent() + mText.descent()) / 2f, mText);
            canvas.restoreToCount(save);
        }
    }
}
