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

package org.petalos.agever.ui;

import android.content.Context;
import android.graphics.Canvas;
import org.petalos.agever.R;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** A minimal analog stick. Reports axes in {@code [-1, 1]} (x right, y up). */
public final class JoystickView extends View {

    public interface Listener {
        void onMove(float x, float y);

        void onRelease();
    }

    private final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mKnobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float mKnobX;
    private float mKnobY;
    private boolean mTracking;
    private Listener mListener;
    private int mPointerId = -1;

    public JoystickView(Context context) {
        this(context, null);
    }

    public JoystickView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public JoystickView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mBasePaint.setColor(context.getColor(R.color.agever_surface_alt));
        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setStrokeWidth(dp(1.5f));
        mRingPaint.setColor(context.getColor(R.color.agever_outline));
        mKnobPaint.setColor(context.getColor(R.color.agever_accent));
        setClickable(true);
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) - dp(6f);
        canvas.drawCircle(cx, cy, radius, mBasePaint);
        canvas.drawCircle(cx, cy, radius, mRingPaint);

        float knobRadius = radius * 0.4f;
        float kx = cx + mKnobX * (radius - knobRadius);
        float ky = cy - mKnobY * (radius - knobRadius);
        canvas.drawCircle(kx, ky, knobRadius, mKnobPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) - dp(6f);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) mPointerId = event.getPointerId(0);
                int pointer = event.findPointerIndex(mPointerId);
                if (pointer < 0) return false;
                mTracking = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                float dx = (event.getX(pointer) - cx) / Math.max(1f, radius);
                float dy = (event.getY(pointer) - cy) / Math.max(1f, radius);
                float len = (float) Math.hypot(dx, dy);
                if (len > 1f) {
                    dx /= len;
                    dy /= len;
                }
                mKnobX = dx;
                mKnobY = -dy;
                if (mListener != null) {
                    mListener.onMove(mKnobX, mKnobY);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mTracking) {
                    mTracking = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    mPointerId = -1;
                    mKnobX = 0f;
                    mKnobY = 0f;
                    if (mListener != null) {
                        mListener.onRelease();
                    }
                    invalidate();
                }
                performClick();
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT);
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT);
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP);
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN);
    }

    @Override public boolean performAccessibilityAction(int action, android.os.Bundle args) {
        if (action == android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId()) {
            mKnobX = Math.max(-1f, mKnobX - .1f);
        } else if (action == android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId()) {
            mKnobX = Math.min(1f, mKnobX + .1f);
        } else if (action == android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId()) {
            mKnobY = Math.min(1f, mKnobY + .1f);
        } else if (action == android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()) {
            mKnobY = Math.max(-1f, mKnobY - .1f);
        } else return super.performAccessibilityAction(action, args);
        if (mListener != null) mListener.onMove(mKnobX, mKnobY);
        invalidate();
        return true;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
