/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */

package org.petalos.hub;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

/** hero container that keeps the bubble sheen drifting */
public class PetalHeroView extends FrameLayout {

    private final PetalHeroDrawable mHero = new PetalHeroDrawable();
    private ValueAnimator mAnimator;

    public PetalHeroView(Context context) {
        this(context, null);
    }

    public PetalHeroView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PetalHeroView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        final float density = getResources().getDisplayMetrics().density;
        mHero.setCornerRadius(getResources().getDimension(R.dimen.petal_hero_radius));
        mHero.setRimWidth(Math.max(1f, density * 1.25f));
        setBackground(mHero);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                float radius = getResources().getDimension(R.dimen.petal_hero_radius);
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        PetalGlass.applyBackdropBlur(this, 28f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        invalidateOutline();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mAnimator == null) {
            mAnimator = ValueAnimator.ofFloat(0f, 1f);
            mAnimator.setDuration(6800L);
            mAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mAnimator.setInterpolator(new LinearInterpolator());
            mAnimator.addUpdateListener(a -> mHero.setPhase((float) a.getAnimatedValue()));
        }
        mAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAnimator != null) {
            mAnimator.cancel();
        }
    }
}
