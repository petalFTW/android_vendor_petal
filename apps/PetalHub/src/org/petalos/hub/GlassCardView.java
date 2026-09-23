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

package org.petalos.hub;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** view that paints a glass card */
public class GlassCardView extends FrameLayout {

    private float mRadiusDp = 24f;
    private float mBlurRadiusDp = 28f;
    private final PetalGlassDrawable mGlass;

    public GlassCardView(@NonNull Context context) {
        this(context, null);
    }

    public GlassCardView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GlassCardView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        final float density = context.getResources().getDisplayMetrics().density;
        int tint = context.getResources().getColor(R.color.petal_glass_tint, context.getTheme());
        int rimTop = context.getResources().getColor(R.color.petal_glass_rim_top, context.getTheme());
        int rimBottom = context.getResources().getColor(R.color.petal_glass_rim_bottom,
                context.getTheme());
        int grain = 0x10;
        float sheen = -1f;

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.GlassCardView,
                    defStyleAttr, 0);
            mRadiusDp = a.getDimension(R.styleable.GlassCardView_glassCornerRadius,
                    mRadiusDp * density) / density;
            mBlurRadiusDp = a.getDimension(R.styleable.GlassCardView_glassBlurRadius,
                    mBlurRadiusDp * density) / density;
            tint = a.getColor(R.styleable.GlassCardView_glassTint, tint);
            rimTop = a.getColor(R.styleable.GlassCardView_glassRimTop, rimTop);
            rimBottom = a.getColor(R.styleable.GlassCardView_glassRimBottom, rimBottom);
            grain = a.getInteger(R.styleable.GlassCardView_glassGrainAlpha, grain);
            sheen = a.getFloat(R.styleable.GlassCardView_glassSheen, sheen);
            a.recycle();
        }

        mGlass = PetalGlass.newCardDrawable(context, mRadiusDp, tint, rimTop, rimBottom, grain);
        if (sheen >= 0f) {
            mGlass.setSpecularProgress(sheen);
        }
        setBackground(mGlass);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                float radius = mRadiusDp * getResources().getDisplayMetrics().density;
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        PetalGlass.applyBackdropBlur(this, mBlurRadiusDp);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        invalidateOutline();
    }
}
