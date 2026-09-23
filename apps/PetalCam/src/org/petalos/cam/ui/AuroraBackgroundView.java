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
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * The petal aurora wash. Three soft colour blobs drift on slow sine orbits so
 * the glass above always has something alive to bend. Mirrors the petalOS Hub
 * window background, but animated.
 */
public class AuroraBackgroundView extends View {

    private static final int PINK = 0x40FF6FAE;
    private static final int VIOLET = 0x4D7C5CFF;
    private static final int TEAL = 0x3357E0D2;

    private final Paint mBase = new Paint();
    private final Paint mBlob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] mColors = {PINK, VIOLET, TEAL};
    private final float[] mPhase = {0f, 2.1f, 4.2f};
    private final float[] mSpeed = {0.00010f, -0.00013f, 0.00008f};

    private long mStart;
    private boolean mAnimating;

    public AuroraBackgroundView(Context context) {
        this(context, null);
    }

    public AuroraBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mBase.setColor(0xFF05060A);
        mStart = SystemClock.uptimeMillis();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAnimating = true;
        mStart = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        mAnimating = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && mAnimating) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final int w = getWidth();
        final int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }

        canvas.drawRect(0, 0, w, h, mBase);

        final float t = (SystemClock.uptimeMillis() - mStart);
        final float radius = Math.max(w, h) * 0.9f;

        for (int i = 0; i < mColors.length; i++) {
            float angle = mPhase[i] + t * mSpeed[i];
            float cx = w * (0.5f + 0.42f * (float) Math.cos(angle));
            float cy = h * (0.42f + 0.38f * (float) Math.sin(angle * 0.9f + i));
            mBlob.setShader(new RadialGradient(cx, cy, radius,
                    mColors[i], Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, radius, mBlob);
        }

        if (mAnimating) {
            postInvalidateOnAnimation();
        }
    }
}
