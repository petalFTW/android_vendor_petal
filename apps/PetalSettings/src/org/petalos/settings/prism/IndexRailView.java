/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.settings.prism;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.OvershootInterpolator;

// Category ticks, with the bounce reserved for the hidden modes.
final class IndexRailView extends View {

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int count = 1;
    private int active;
    private int activeColor;
    private float cursor;
    private ValueAnimator animator;
    private boolean expressive;

    IndexRailView(Context context) {
        super(context);
        line.setStrokeCap(Paint.Cap.BUTT);
        activeColor = context.getColor(R.color.accent);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void setCount(int count) {
        this.count = Math.max(1, count);
        setContentDescription(getContext().getString(R.string.rail_description,
                active + 1, this.count));
        invalidate();
    }

    void setExpressive(boolean expressive) {
        this.expressive = expressive;
    }

    void setActive(int index, int color) {
        active = index;
        activeColor = color;
        setContentDescription(getContext().getString(R.string.rail_description,
                index + 1, count));
        if (animator != null) animator.cancel();
        if (cursor == index || !ValueAnimator.areAnimatorsEnabled()) {
            cursor = index;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(cursor, index);
        animator.setDuration(expressive ? 380 : 180);
        animator.setInterpolator(expressive ? new OvershootInterpolator(0.38f)
                : new android.view.animation.DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            cursor = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (count < 1 || getWidth() == 0) return;
        float step = Math.min(dp(16), getWidth() / (float) count);
        float start = (getWidth() - step * count) / 2f;
        int muted = getContext().getColor(R.color.muted);
        int inactive = (muted & 0x00FFFFFF) | (0x55 << 24);

        line.setStrokeWidth(dp(1.1f));
        line.setColor(inactive);
        for (int i = 0; i < count; i++) {
            float x = start + step * (i + 0.5f);
            canvas.drawLine(x - dp(4), dp(13), x + dp(4), dp(13), line);
        }

        float x = start + step * (cursor + 0.5f);
        line.setStrokeWidth(dp(2));
        line.setColor(activeColor);
        canvas.drawLine(x - dp(4), dp(13), x + dp(4), dp(13), line);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
