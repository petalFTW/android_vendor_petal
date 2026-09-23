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

package org.petalos.depth;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** custom views for the studio sheet */
final class DepthWidgets {

    private DepthWidgets() {
    }

    /** glass colours used by the widgets */
    static final int GLASS = 0x2EFFFFFF;
    static final int GLASS_STROKE = 0x1FFFFFFF;
    static final int TEXT = 0xF2FFFFFF;
    static final int TEXT_DIM = 0x99FFFFFF;

    /** label and value on top, track below */
    static final class FancySlider extends View {

        interface OnChange {
            void onChanged(int value);
        }

        private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mThumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mValue = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mTrackRect = new RectF();

        private final String mLabel;
        private final String mUnit;
        private final int mMin;
        private final int mMax;
        private int mValueNow;
        private OnChange mListener;

        FancySlider(Context context, String label, String unit, int min, int max, int value) {
            super(context);
            mLabel = label;
            mUnit = unit;
            mMin = min;
            mMax = max;
            mValueNow = value;
            mText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            mText.setTextSize(sp(13));
            mText.setColor(TEXT_DIM);
            mValue.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            mValue.setTextSize(sp(13));
            mValue.setColor(TEXT);
            setClickable(true);
        }

        void setListener(OnChange listener) {
            mListener = listener;
        }

        void setValue(int value) {
            mValueNow = Math.max(mMin, Math.min(mMax, value));
            invalidate();
        }

        int getValue() {
            return mValueNow;
        }

        private float fraction() {
            return (mValueNow - mMin) / (float) (mMax - mMin);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(
                    resolveSize(dp(120), widthMeasureSpec), resolveSize(dp(58), heightMeasureSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawText(mLabel, dp(2), dp(16), mText);
            String value = mValueNow + mUnit;
            float vw = mValue.measureText(value);
            canvas.drawText(value, getWidth() - vw - dp(2), dp(16), mValue);

            float trackTop = dp(32);
            float trackBottom = dp(44);
            float radius = (trackBottom - trackTop) / 2f;
            mTrackRect.set(0, trackTop, getWidth(), trackBottom);
            mTrack.setColor(0x24FFFFFF);
            canvas.drawRoundRect(mTrackRect, radius, radius, mTrack);

            float fillEnd = Math.max(trackTop + radius, dp(2) + (getWidth() - dp(4)) * fraction());
            mFill.setColor(0xE6FFFFFF);
            mTrackRect.set(0, trackTop, fillEnd, trackBottom);
            canvas.drawRoundRect(mTrackRect, radius, radius, mFill);

            mThumb.setColor(Color.WHITE);
            canvas.drawCircle(fillEnd, (trackTop + trackBottom) / 2f, dp(11), mThumb);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                case android.view.MotionEvent.ACTION_MOVE: {
                    float f = (event.getX() - dp(2)) / (float) (getWidth() - dp(4));
                    int next = Math.round(mMin + (mMax - mMin) * Math.max(0f, Math.min(1f, f)));
                    if (next != mValueNow) {
                        mValueNow = next;
                        invalidate();
                        if (mListener != null) {
                            mListener.onChanged(mValueNow);
                        }
                    }
                    return true;
                }
                default:
                    return super.onTouchEvent(event);
            }
        }

        private int dp(int v) {
            return (int) (v * getResources().getDisplayMetrics().density);
        }

        private float sp(int v) {
            return v * getResources().getDisplayMetrics().scaledDensity;
        }
    }

    /** colour swatch with a selection ring */
    static final class InkSwatch extends View {

        static final int AUTO = 0;

        private final Paint mBody = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int mColor;
        private boolean mSelected;
        private Runnable mPick;

        InkSwatch(Context context, int color) {
            super(context);
            mColor = color;
            mRing.setStyle(Paint.Style.STROKE);
            setClickable(true);
        }

        void setPick(Runnable pick) {
            mPick = pick;
        }

        int getColor() {
            return mColor;
        }

        void setSwatchSelected(boolean selected) {
            mSelected = selected;
            setSelected(selected);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(resolveSize(dp(48), widthMeasureSpec),
                    resolveSize(dp(48), heightMeasureSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = dp(13);
            if (mColor == AUTO) {
                mBody.setShader(new LinearGradient(
                        cx - r, cy, cx + r, cy, 0xFFFFFFFF, 0xFF26262B,
                        android.graphics.Shader.TileMode.CLAMP));
            } else {
                mBody.setShader(null);
                mBody.setColor(mColor);
            }
            canvas.drawCircle(cx, cy, r, mBody);
            if (mColor == AUTO) {
                mDot.setColor(0xFF26262B);
                canvas.drawCircle(cx - r * 0.35f, cy, dp(2), mDot);
                mDot.setColor(0xFFFFFFFF);
                canvas.drawCircle(cx + r * 0.35f, cy, dp(2), mDot);
            }
            if (mSelected) {
                mRing.setColor(0xF2FFFFFF);
                mRing.setStrokeWidth(dp(2));
                canvas.drawCircle(cx, cy, r + dp(3), mRing);
            }
        }

        @Override
        public boolean performClick() {
            if (mPick != null) {
                mPick.run();
            }
            return super.performClick();
        }

        private int dp(int v) {
            return (int) (v * getResources().getDisplayMetrics().density);
        }
    }

    /** small live clock card for a preset */
    static final class PresetCard extends View {

        private final DepthClockRenderer mRenderer = new DepthClockRenderer();
        private final Paint mBackdrop = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final DepthPresets.Preset mPreset;
        private final String mTitle;
        private boolean mSelected;
        private Runnable mPick;

        PresetCard(Context context, DepthPresets.Preset preset, String title) {
            super(context);
            mPreset = preset;
            mTitle = title;
            mBackdrop.setShader(new LinearGradient(
                    0f, 0f, 0f, 1f, new int[] {0xFF3A3F4B, 0xFF191B21, 0xFF0D0E12},
                    new float[] {0f, 0.55f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            mRing.setStyle(Paint.Style.STROKE);
            mLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            mLabel.setTextSize(sp(11));
            mLabel.setColor(TEXT_DIM);
            setContentDescription(title);
            setClickable(true);
        }

        void setPick(Runnable pick) {
            mPick = pick;
        }

        void setCardSelected(boolean selected) {
            mSelected = selected;
            setSelected(selected);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = dp(84);
            int h = dp(104);
            setMeasuredDimension(
                    resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cardH = getHeight() - dp(22);
            float radius = dp(18);
            RectF card = new RectF(0, 0, getWidth(), cardH);
            canvas.drawRoundRect(card, radius, radius, mBackdrop);

            mRenderer.draw(canvas, getWidth(), (int) cardH, mPreset.style,
                    mPreset.stackVertical, mPreset.anchorXPct / 100f, 0.52f,
                    mPreset.scalePct / 100f * 0.92f,
                    mPreset.ink, false,
                    mPreset.stackVertical ? "10" : "10:", "08", "");

            if (mSelected) {
                mRing.setColor(0xF2FFFFFF);
                mRing.setStrokeWidth(dp(2));
            } else {
                mRing.setColor(GLASS_STROKE);
                mRing.setStrokeWidth(dp(1));
            }
            canvas.drawRoundRect(card, radius, radius, mRing);

            float tw = mLabel.measureText(mTitle);
            canvas.drawText(mTitle, (getWidth() - tw) / 2f, getHeight() - dp(8), mLabel);
        }

        @Override
        public boolean performClick() {
            if (mPick != null) {
                mPick.run();
            }
            return super.performClick();
        }

        private int dp(int v) {
            return (int) (v * getResources().getDisplayMetrics().density);
        }

        private float sp(int v) {
            return v * getResources().getDisplayMetrics().scaledDensity;
        }
    }
}
