/* Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.petalos.hub;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** draws the little phone bezel preview */
public final class BezelPlacementView extends View {
    public interface Listener {
        void onPositionChanged(int edge, int percent);
    }

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mScreen = new RectF();
    private final Path mBend = new Path();
    private final Path mClip = new Path();
    private int mEdge;
    private int mPercent;
    private int mKind;
    private boolean mDragging;
    private Listener mListener;

    public BezelPlacementView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // the radio buttons and slider handle a11y
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setPosition(int edge, int percent, int kind) {
        mEdge = edge;
        mPercent = percent;
        mKind = kind;
        invalidate();
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float h = getHeight() - dp(24);
        float ratio = 0.46f;
        if (getDisplay() != null) {
            android.view.Display.Mode mode = getDisplay().getMode();
            ratio = (float) Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight())
                    / Math.max(mode.getPhysicalWidth(), mode.getPhysicalHeight());
        }
        float w = Math.min(getWidth() - dp(64), h * ratio);
        mScreen.set((getWidth() - w) / 2, dp(12), (getWidth() + w) / 2, dp(12) + h);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(0xff0c0c0e);
        canvas.drawRoundRect(mScreen.left - dp(4), mScreen.top - dp(4),
                mScreen.right + dp(4), mScreen.bottom + dp(4), dp(22), dp(22), mPaint);
        mPaint.setColor(0xffdce4eb);
        canvas.drawRoundRect(mScreen, dp(18), dp(18), mPaint);
        mPaint.setColor(0xff0c0c0e);
        canvas.drawCircle(mScreen.centerX(), mScreen.top + dp(9), dp(2.5f), mPaint);
        canvas.drawRoundRect(mScreen.centerX() - dp(14), mScreen.bottom - dp(8),
                mScreen.centerX() + dp(14), mScreen.bottom - dp(6), dp(1), dp(1), mPaint);
        float y = mScreen.top + mScreen.height() * mPercent / 100f;
        float x = mEdge == 0 ? mScreen.left : mScreen.right;
        int saved = canvas.save();
        mClip.rewind();
        mClip.addRoundRect(mScreen, dp(18), dp(18), Path.Direction.CW);
        canvas.clipPath(mClip);
        canvas.translate(x, y);
        if (mEdge == 1) canvas.scale(-1, 1);
        float d = dp(mKind == 2 ? 5 : 15);
        float half = dp(mKind == 2 ? 17 : 30);
        mBend.rewind();
        mBend.moveTo(0, -half);
        mBend.cubicTo(0, -half * .45f, d, -half * .45f, d, 0);
        mBend.cubicTo(d, half * .45f, 0, half * .45f, 0, half);
        mBend.close();
        canvas.drawPath(mBend, mPaint);
        if (mKind != 2) {
            canvas.drawRoundRect(0, -dp(23), dp(17), dp(23), dp(8), dp(8), mPaint);
            mPaint.setColor(mKind == 0 ? Color.WHITE : 0xffff453a);
            if (mKind == 0) {
                canvas.drawRoundRect(dp(6), -dp(13), dp(11), dp(15), dp(2), dp(2), mPaint);
            } else {
                canvas.drawCircle(dp(8), -dp(13), dp(4), mPaint);
                mPaint.setColor(0xff34c759);
                canvas.drawCircle(dp(8), 0, dp(4), mPaint);
                mPaint.setColor(getContext().getColor(R.color.petal_accent_primary));
                canvas.drawCircle(dp(8), dp(13), dp(4), mPaint);
            }
        }
        canvas.restoreToCount(saved);
        // keep the drag handle on top
        mPaint.setColor(getContext().getColor(R.color.petal_accent_text));
        canvas.drawCircle(x, y, dp(7), mPaint);
        mPaint.setColor(Color.WHITE);
        canvas.drawCircle(x, y, dp(2), mPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (event.getX() < mScreen.left - dp(24)
                        || event.getX() > mScreen.right + dp(24)
                        || event.getY() < mScreen.top - dp(12)
                        || event.getY() > mScreen.bottom + dp(12)) return false;
                mDragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                updatePosition(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!mDragging) return false;
                updatePosition(event);
                return true;
            case MotionEvent.ACTION_UP:
                if (!mDragging) return false;
                updatePosition(event);
                performClick();
                // fall through
            case MotionEvent.ACTION_CANCEL:
                mDragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return mDragging;
        }
    }

    private void updatePosition(MotionEvent event) {
        if (mScreen.height() <= 0) return;
        int edge = event.getX() < mScreen.centerX() ? 0 : 1;
        int percent = Math.max(0, Math.min(100,
                Math.round((event.getY() - mScreen.top) * 100 / mScreen.height())));
        if (mListener != null && (edge != mEdge || percent != mPercent)) {
            mListener.onPositionChanged(edge, percent);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
