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

import org.petalos.config.DepthClockRenderer;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** custom views for the studio sheet */
final class DepthWidgets {

    private DepthWidgets() {
    }

    /** the site's colour roles, day or night comes from the res folder */
    static final class Tokens {
        final int canvas;
        final int surface;
        final int surfaceHi;
        final int text;
        final int text2;
        final int accent;
        final int accentContainer;
        final int onAccent;
        final int outline;
        final int outline2;
        final int danger;
        final int dangerContainer;

        Tokens(Context context) {
            canvas = context.getColor(R.color.depth_canvas);
            surface = context.getColor(R.color.depth_surface);
            surfaceHi = context.getColor(R.color.depth_surface_hi);
            text = context.getColor(R.color.depth_text);
            text2 = context.getColor(R.color.depth_text_2);
            accent = context.getColor(R.color.depth_accent);
            accentContainer = context.getColor(R.color.depth_accent_container);
            onAccent = context.getColor(R.color.depth_on_accent);
            outline = context.getColor(R.color.depth_outline);
            outline2 = context.getColor(R.color.depth_outline_2);
            danger = context.getColor(R.color.depth_danger);
            dangerContainer = context.getColor(R.color.depth_danger_container);
        }
    }

    static Typeface font(Context context, int weight) {
        return Typeface.create(context.getResources().getFont(R.font.petal_ui), weight, false);
    }

    /** label and value on top, track below */
    static final class FancySlider extends View {

        interface OnChange {
            void onChanged(int value);
        }

        private static final float THUMB_DP = 12f;
        private static final float TRACK_DP = 6f;
        private static final float GRAB_DP = 32f;

        private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mThumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mValue = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFocus = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mTrackRect = new RectF();
        private final Tokens mTokens;
        private final int mSlop;

        private final String mLabel;
        private final String mUnit;
        private final int mMin;
        private final int mMax;
        private final int mStep;
        private String mZeroText;
        private int mValueNow;
        private OnChange mListener;
        private OnChange mCommit;

        private float mDownX;
        private float mDownY;
        private float mGrabOffset;
        private int mValueAtDown;
        private boolean mDragging;
        private boolean mScrolling;
        private float mThumbScale = 1f;
        private android.animation.ValueAnimator mThumbAnim;

        FancySlider(Context context, String label, String unit, int min, int max, int step,
                int value) {
            super(context);
            mTokens = new Tokens(context);
            mSlop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
            mLabel = label;
            mUnit = unit;
            mMin = min;
            mMax = max;
            mStep = step;
            mValueNow = snap(value);
            mText.setTypeface(font(context, 400));
            mText.setTextSize(sp(14));
            mText.setColor(mTokens.text2);
            mValue.setTypeface(font(context, 500));
            mValue.setTextSize(sp(14));
            mValue.setFontFeatureSettings("tnum");
            mValue.setColor(mTokens.text);
            mTrack.setColor(mTokens.outline2);
            mFill.setColor(mTokens.accent);
            mThumb.setColor(mTokens.accent);
            mFocus.setColor(mTokens.accent);
            mFocus.setStyle(Paint.Style.STROKE);
            mFocus.setStrokeWidth(dp(2));
            setFocusable(true);
            setClickable(true);
            setContentDescription(label);
        }

        /** live while the finger's down, keep it cheap */
        void setListener(OnChange listener) {
            mListener = listener;
        }

        /** fires once on release, never when the drag got cancelled */
        void setOnCommit(OnChange commit) {
            mCommit = commit;
        }

        void setZeroText(String text) {
            mZeroText = text;
            invalidate();
        }

        void setValue(int value) {
            if (mDragging) return;
            mValueNow = snap(value);
            invalidate();
        }

        int getValue() {
            return mValueNow;
        }

        private int snap(int value) {
            int clamped = Math.max(mMin, Math.min(mMax, value));
            return Math.min(mMax, mMin + Math.round((clamped - mMin) / (float) mStep) * mStep);
        }

        private float fraction() {
            return (mValueNow - mMin) / (float) (mMax - mMin);
        }

        // the old one mixed a y value into x, so the thumb never reached the left end
        private float thumbX() {
            float inset = dp(THUMB_DP);
            float span = getWidth() - inset * 2f;
            return isRtl() ? getWidth() - inset - span * fraction() : inset + span * fraction();
        }

        private boolean isRtl() {
            return getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        }

        private int valueAt(float x) {
            float inset = dp(THUMB_DP);
            float f = (x - inset) / Math.max(1f, getWidth() - inset * 2f);
            if (isRtl()) f = 1f - f;
            return snap(Math.round(mMin + (mMax - mMin) * Math.max(0f, Math.min(1f, f))));
        }

        private void move(int next) {
            if (next == mValueNow) return;
            mValueNow = next;
            invalidate();
            performHapticFeedback(android.view.HapticFeedbackConstants.SEGMENT_FREQUENT_TICK);
            if (mListener != null) mListener.onChanged(next);
            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED);
        }

        private void settleThumb(float target) {
            if (mThumbAnim != null) mThumbAnim.cancel();
            mThumbAnim = android.animation.ValueAnimator.ofFloat(mThumbScale, target);
            mThumbAnim.setDuration(120);
            mThumbAnim.addUpdateListener(a -> {
                mThumbScale = (float) a.getAnimatedValue();
                invalidate();
            });
            mThumbAnim.start();
        }

        private String valueText() {
            return mValueNow == 0 && mZeroText != null ? mZeroText : mValueNow + mUnit;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(
                    resolveSize(dp(120), widthMeasureSpec), resolveSize(dp(64), heightMeasureSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float base = dp(18);
            canvas.drawText(mLabel, 0, base, mText);
            String value = valueText();
            canvas.drawText(value, getWidth() - mValue.measureText(value), base, mValue);

            float cy = dp(44);
            float half = dp(TRACK_DP) / 2f;
            mTrackRect.set(0, cy - half, getWidth(), cy + half);
            canvas.drawRoundRect(mTrackRect, half, half, mTrack);
            float x = thumbX();
            if (isRtl()) {
                mTrackRect.set(x, cy - half, getWidth(), cy + half);
            } else {
                mTrackRect.set(0, cy - half, x, cy + half);
            }
            canvas.drawRoundRect(mTrackRect, half, half, mFill);
            canvas.drawCircle(x, cy, dp(10) * mThumbScale, mThumb);
            if (isFocused()) canvas.drawCircle(x, cy, dp(14), mFocus);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            if (!isEnabled()) return false;
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    // nothing moves yet, this might just be a thumb scrolling the panel
                    mDownX = event.getX();
                    mDownY = event.getY();
                    mValueAtDown = mValueNow;
                    mDragging = false;
                    mScrolling = false;
                    float offset = thumbX() - mDownX;
                    mGrabOffset = Math.abs(offset) < dp(GRAB_DP) ? offset : 0f;
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = event.getX() - mDownX;
                    float dy = event.getY() - mDownY;
                    if (!mDragging && !mScrolling) {
                        if (Math.abs(dx) > mSlop && Math.abs(dx) > Math.abs(dy)) {
                            mDragging = true;
                            getParent().requestDisallowInterceptTouchEvent(true);
                            settleThumb(1.2f);
                        } else if (Math.abs(dy) > mSlop) {
                            mScrolling = true;
                        }
                    }
                    if (mDragging) move(valueAt(event.getX() + mGrabOffset));
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                    if (mDragging) {
                        // quick flicks finish here, the last move is way short of the edge
                        move(valueAt(event.getX() + mGrabOffset));
                        endDrag();
                    } else if (!mScrolling) {
                        move(valueAt(event.getX()));
                    }
                    if (!mScrolling && mValueNow != mValueAtDown && mCommit != null) {
                        mCommit.onChanged(mValueNow);
                    }
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (mDragging) {
                        endDrag();
                        move(mValueAtDown);
                    }
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private void endDrag() {
            mDragging = false;
            getParent().requestDisallowInterceptTouchEvent(false);
            settleThumb(1f);
        }

        private boolean nudge(int direction) {
            int next = snap(mValueNow + direction * mStep);
            if (next == mValueNow) return false;
            move(next);
            if (mCommit != null) mCommit.onChanged(next);
            return true;
        }

        @Override
        public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
            int direction = keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ? 1
                    : keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
            if (direction != 0 && isEnabled() && nudge(isRtl() ? -direction : direction)) return true;
            return super.onKeyDown(keyCode, event);
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(
                android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(android.widget.SeekBar.class.getName());
            info.setStateDescription(valueText());
            info.setRangeInfo(android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(
                    android.view.accessibility.AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                    mMin, mMax, mValueNow));
            if (isEnabled()) {
                info.addAction(android.view.accessibility.AccessibilityNodeInfo
                        .AccessibilityAction.ACTION_SCROLL_FORWARD);
                info.addAction(android.view.accessibility.AccessibilityNodeInfo
                        .AccessibilityAction.ACTION_SCROLL_BACKWARD);
            }
        }

        @Override
        public boolean performAccessibilityAction(int action, android.os.Bundle args) {
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                return nudge(1);
            }
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                return nudge(-1);
            }
            return super.performAccessibilityAction(action, args);
        }

        private int dp(float v) {
            return Math.round(v * getResources().getDisplayMetrics().density);
        }

        private float sp(int v) {
            return v * getResources().getDisplayMetrics().scaledDensity;
        }
    }

    static final int PREVIEW_STAGE = 0xFF0A0B10;

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
            mRing.setColor(context.getColor(R.color.depth_accent));
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
        private final int mRingOn;
        private final int mRingOff;
        private boolean mSelected;
        private Runnable mPick;

        PresetCard(Context context, DepthPresets.Preset preset, String title) {
            super(context);
            mPreset = preset;
            mTitle = title;
            // clocks are drawn for photos, so this stays dark in day mode too
            mBackdrop.setColor(PREVIEW_STAGE);
            mRing.setStyle(Paint.Style.STROKE);
            mRingOn = context.getColor(R.color.depth_accent);
            mRingOff = context.getColor(R.color.depth_outline_2);
            mLabel.setTypeface(font(context, 500));
            mLabel.setTextSize(sp(12));
            mLabel.setColor(context.getColor(R.color.depth_text_2));
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
            float radius = dp(12);
            RectF card = new RectF(0, 0, getWidth(), cardH);
            canvas.drawRoundRect(card, radius, radius, mBackdrop);

            mRenderer.draw(canvas, getWidth(), (int) cardH, mPreset.style,
                    mPreset.stackVertical, mPreset.anchorXPct / 100f, 0.52f,
                    mPreset.scalePct / 100f * 0.92f,
                    mPreset.ink, false,
                    mPreset.stackVertical ? "10" : "10:", "08", "");

            if (mSelected) {
                mRing.setColor(mRingOn);
                mRing.setStrokeWidth(dp(2));
            } else {
                mRing.setColor(mRingOff);
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
