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
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import org.petalos.cam.R;

/**
 * A container that paints a {@link LiquidGlassDrawable} behind its children and
 * can run the island's specular / neon sweeps on demand.
 */
public class PetalGlassLayout extends FrameLayout {

    private final LiquidGlassDrawable mGlass = new LiquidGlassDrawable();
    private float mCornerRadius;
    private float mDensity;

    private float mNeon = -1f;
    private long mNeonStart;
    private long mNeonDuration = 1500L;
    private int mNeonColor = 0xFFFFFFFF;

    private float mSpecular = -1f;
    private long mSpecularStart;
    private long mSpecularDuration = 700L;

    private ViewTreeObserver.OnPreDrawListener mPreDraw;

    public PetalGlassLayout(Context context) {
        this(context, null);
    }

    public PetalGlassLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PetalGlassLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mPreDraw = () -> {
            boolean more = false;
            long now = android.os.SystemClock.uptimeMillis();
            if (mNeon >= 0f) {
                float p = (now - mNeonStart) / (float) mNeonDuration;
                if (p >= 1f) {
                    mNeon = -1f;
                    mGlass.setNeonProgress(-1f);
                } else {
                    mGlass.setNeonProgress(p);
                    more = true;
                }
            }
            if (mSpecular >= 0f) {
                float p = (now - mSpecularStart) / (float) mSpecularDuration;
                if (p >= 1f) {
                    mSpecular = -1f;
                    mGlass.setSpecularProgress(-1f);
                } else {
                    mGlass.setSpecularProgress(p);
                    more = true;
                }
            }
            if (!more && getViewTreeObserver().isAlive()) {
                getViewTreeObserver().removeOnPreDrawListener(mPreDraw);
            }
            return true;
        };
        mDensity = getResources().getDisplayMetrics().density;

        int tint = 0x3D181A22;
        int rimTop = 0x59FFFFFF;
        int rimBottom = 0x14FFFFFF;
        int grain = 0x12;
        mCornerRadius = 22f * mDensity;

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.PetalGlassLayout);
            mCornerRadius = a.getDimension(R.styleable.PetalGlassLayout_petalCornerRadius,
                    mCornerRadius);
            tint = a.getColor(R.styleable.PetalGlassLayout_petalGlassTint, tint);
            rimTop = a.getColor(R.styleable.PetalGlassLayout_petalGlassRimTop, rimTop);
            rimBottom = a.getColor(R.styleable.PetalGlassLayout_petalGlassRimBottom, rimBottom);
            grain = a.getInt(R.styleable.PetalGlassLayout_petalGlassGrain, grain);
            a.recycle();
        }

        mGlass.setTintColor(tint);
        mGlass.setRim(rimTop, rimBottom);
        mGlass.setCornerRadius(mCornerRadius);
        mGlass.setRimWidth(mDensity);
        mGlass.setGrainAlpha(grain);
        setWillNotDraw(false);
        setBackground(null);
    }

    public LiquidGlassDrawable glass() {
        return mGlass;
    }

    public void setCornerRadius(float radiusPx) {
        mCornerRadius = radiusPx;
        mGlass.setCornerRadius(radiusPx);
    }

    public void setGlassTint(int color) {
        mGlass.setTintColor(color);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mGlass.setBounds(0, 0, w, h);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        mGlass.setBounds(0, 0, getWidth(), getHeight());
        mGlass.draw(canvas);
        super.dispatchDraw(canvas);
    }

    /** Island-style neon comet racing around the rim. */
    public void playNeon(int color, long durationMs) {
        mNeonColor = color;
        mNeonDuration = durationMs;
        mGlass.setNeonColor(color);
        mNeon = 0f;
        mNeonStart = android.os.SystemClock.uptimeMillis();
        attachPreDraw();
    }

    /** Specular sweep, used when a panel/mode is activated. */
    public void playSpecular(long durationMs) {
        mSpecularDuration = durationMs;
        mSpecular = 0f;
        mSpecularStart = android.os.SystemClock.uptimeMillis();
        attachPreDraw();
    }

    private void attachPreDraw() {
        ViewTreeObserver vto = getViewTreeObserver();
        if (vto.isAlive()) {
            vto.removeOnPreDrawListener(mPreDraw);
            vto.addOnPreDrawListener(mPreDraw);
        }
        invalidate();
    }
}
