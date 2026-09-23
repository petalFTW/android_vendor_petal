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
import android.util.AttributeSet;
import android.view.View;

/** Rule-of-thirds / golden-ratio composition grid with a soft safe frame. */
public class GridOverlayView extends View {

    public static final int NONE = 0;
    public static final int THIRDS = 1;
    public static final int GOLDEN = 2;
    public static final int CENTER = 3;

    private static final float GOLDEN_A = 0.382f;
    private static final float GOLDEN_B = 0.618f;

    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int mMode = THIRDS;
    private float mDensity;

    public GridOverlayView(Context context) {
        this(context, null);
    }

    public GridOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mLine.setColor(0x33FFFFFF);
        mLine.setStrokeWidth(1f * mDensity);
    }

    public void setMode(int mode) {
        mMode = mode;
        setVisibility(mode == NONE ? GONE : VISIBLE);
        invalidate();
    }

    public int getMode() {
        return mMode;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mMode == NONE) {
            return;
        }
        final float w = getWidth();
        final float h = getHeight();

        if (mMode == THIRDS) {
            for (int i = 1; i <= 2; i++) {
                canvas.drawLine(w * i / 3f, 0, w * i / 3f, h, mLine);
                canvas.drawLine(0, h * i / 3f, w, h * i / 3f, mLine);
            }
        } else if (mMode == GOLDEN) {
            canvas.drawLine(w * GOLDEN_A, 0, w * GOLDEN_A, h, mLine);
            canvas.drawLine(w * GOLDEN_B, 0, w * GOLDEN_B, h, mLine);
            canvas.drawLine(0, h * GOLDEN_A, w, h * GOLDEN_A, mLine);
            canvas.drawLine(0, h * GOLDEN_B, w, h * GOLDEN_B, mLine);
        } else if (mMode == CENTER) {
            float cx = w / 2f;
            float cy = h / 2f;
            float arm = Math.min(w, h) * 0.06f;
            canvas.drawLine(cx - arm, cy, cx + arm, cy, mLine);
            canvas.drawLine(cx, cy - arm, cx, cy + arm, mLine);
        }
    }
}
