/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */

package org.petalos.hub;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** the aurora hero, but it's a soap bubble now */
public class PetalHeroDrawable extends Drawable {

    private static final int AURORA_START = 0xF2262A66;
    private static final int AURORA_MID = 0xF27A5CFF;
    private static final int AURORA_END = 0xF2FF7FBF;

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mSheenMatrix = new Matrix();

    private final Path mPath = new Path();
    private final Path mRimPath = new Path();
    private final RectF mRect = new RectF();
    private final RectF mRimRect = new RectF();

    private LinearGradient mSheenShader;

    private float mRadiusPx;
    private float mRimWidth = 2f;
    private float mPhase;
    private boolean mDirty = true;

    public PetalHeroDrawable() {
        mRim.setStyle(Paint.Style.STROKE);
        mShade.setStyle(Paint.Style.FILL);
        mSheen.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
    }

    public void setCornerRadius(float radiusPx) {
        if (mRadiusPx != radiusPx) {
            mRadiusPx = radiusPx;
            mDirty = true;
            invalidateSelf();
        }
    }

    public void setRimWidth(float widthPx) {
        if (mRimWidth != widthPx) {
            mRimWidth = widthPx;
            mDirty = true;
            invalidateSelf();
        }
    }

    // 0..1 wraps the sheen across the bubble
    public void setPhase(float phase) {
        mPhase = phase - (float) Math.floor(phase);
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        final Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }
        if (mDirty) {
            rebuild(bounds);
        }

        canvas.drawPath(mPath, mFill);

        final int save = canvas.save();
        canvas.clipPath(mPath);
        canvas.drawRect(mRect, mShade);
        canvas.drawRect(mRect, mGloss);
        canvas.drawRect(mRect, mHot);
        drawSheen(canvas);
        canvas.restoreToCount(save);

        canvas.drawPath(mRimPath, mRim);
    }

    private void drawSheen(Canvas canvas) {
        final float w = mRect.width();
        final float h = mRect.height();
        final float bandW = w * 0.55f;
        final float travel = w * 1.7f;
        final float x = mRect.left - bandW + mPhase * travel;

        mSheenMatrix.setTranslate(x, 0f);
        if (mSheenShader != null) {
            mSheenShader.setLocalMatrix(mSheenMatrix);
        }

        final int save = canvas.save();
        canvas.rotate(-24f, mRect.centerX(), mRect.centerY());
        canvas.drawRect(x, mRect.top - h, x + bandW, mRect.bottom + h, mSheen);
        canvas.restoreToCount(save);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        mDirty = true;
    }

    private void rebuild(Rect bounds) {
        mRect.set(bounds);
        final float w = mRect.width();
        final float h = mRect.height();

        mPath.reset();
        mPath.addRoundRect(mRect, mRadiusPx, mRadiusPx, Path.Direction.CW);
        mRimRect.set(mRect);
        mRimRect.inset(mRimWidth / 2f, mRimWidth / 2f);
        mRimPath.reset();
        mRimPath.addRoundRect(mRimRect, mRadiusPx, mRadiusPx, Path.Direction.CW);

        mFill.setShader(new LinearGradient(mRect.left, mRect.top, mRect.right, mRect.bottom,
                new int[]{AURORA_START, AURORA_MID, AURORA_END},
                new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));

        mRim.setStrokeWidth(mRimWidth);
        mRim.setShader(new LinearGradient(0f, mRect.top, 0f, mRect.bottom,
                new int[]{0x9AFFFFFF, 0x40FFFFFF, 0x1AFFFFFF, 0x4DFFFFFF}, null,
                Shader.TileMode.CLAMP));

        // big soft window reflection up top
        mGloss.setShader(new RadialGradient(
                mRect.left + w * 0.26f, mRect.top + h * 0.12f, w * 0.66f,
                new int[]{0x59FFFFFF, 0x1FFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));

        // little hard hotspot, the one that sells "bubble"
        mHot.setShader(new RadialGradient(
                mRect.left + w * 0.2f, mRect.top + h * 0.08f, w * 0.15f,
                new int[]{0xB3FFFFFF, 0x2EFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));

        // bottom curls away so it reads round
        mShade.setShader(new LinearGradient(0f, mRect.top + h * 0.45f, 0f, mRect.bottom,
                new int[]{0x00000000, 0x1F000000}, null, Shader.TileMode.CLAMP));

        // the sliding band, faint pink at one edge for that oily refraction
        mSheenShader = new LinearGradient(0f, 0f, bandWOf(w), 0f,
                new int[]{0x00FFFFFF, 0x30FFFFFF, 0x66FFFFFF, 0x29FFD1EC, 0x00FFFFFF},
                new float[]{0f, 0.35f, 0.6f, 0.78f, 1f}, Shader.TileMode.CLAMP);
        mSheen.setShader(mSheenShader);

        mDirty = false;
    }

    private static float bandWOf(float w) {
        return w * 0.55f;
    }

    @Override
    public void setAlpha(int alpha) {
        // baked into the colours
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // unused
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
