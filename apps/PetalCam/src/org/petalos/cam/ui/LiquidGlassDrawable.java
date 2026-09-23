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

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.util.Random;

/**
 * Java port of the petalOS SystemUI {@code LiquidGlassDrawable}: glass fill,
 * baked grain, a top lit rim, an optional moving specular sheen and an optional
 * neon comet that chases the rim. Used for every glass surface in PetalCam.
 */
public class LiquidGlassDrawable extends Drawable {

    private static final int DEFAULT_GRAIN_ALPHA = 0x12;
    private static final int NOISE_SIZE = 96;
    private static final float NEON_CHAIN_FRACTION = 0.24f;
    private static final float NEON_FADE_FRACTION = 0.12f;

    private static volatile Bitmap sCachedNoise;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGrainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mNeonCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mNeonGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path mPath = new Path();
    private final Path mRimPath = new Path();
    private final Path mNeonMeasurePath = new Path();
    private final Path mNeonSegment = new Path();
    private final Path mWrappedNeon = new Path();
    private final PathMeasure mNeonMeasure = new PathMeasure();
    private final RectF mRect = new RectF();
    private final RectF mRimRect = new RectF();
    private final Matrix mSheenMatrix = new Matrix();

    private boolean mGeometryDirty = true;
    private boolean mRimShaderDirty = true;
    private BlurMaskFilter mNeonBlur;

    private int mTint = Color.TRANSPARENT;
    private int mRimTop = Color.TRANSPARENT;
    private int mRimBottom = Color.TRANSPARENT;
    private float mCornerRadius = 0f;
    private float mRimWidthPx = 1f;
    private int mGrainAlpha = DEFAULT_GRAIN_ALPHA;

    private float mSpecularProgress = -1f;
    private LinearGradient mSheenShader;

    private float mNeonProgress = -1f;
    private int mNeonColor = 0xFFFFFFFF;

    private final BitmapShader mGrainShader;

    public LiquidGlassDrawable() {
        mGrainShader = new BitmapShader(noiseBitmap(), Shader.TileMode.REPEAT,
                Shader.TileMode.REPEAT);
        mGrainPaint.setShader(mGrainShader);
        mGrainPaint.setAlpha(mGrainAlpha);
        mRimPaint.setStyle(Paint.Style.STROKE);
        mNeonCorePaint.setStyle(Paint.Style.STROKE);
        mNeonGlowPaint.setStyle(Paint.Style.STROKE);
        mSheenPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
    }

    public void setTintColor(int color) {
        if (mTint != color) {
            mTint = color;
            invalidateSelf();
        }
    }

    public void setRim(int top, int bottom) {
        if (mRimTop != top || mRimBottom != bottom) {
            mRimTop = top;
            mRimBottom = bottom;
            mRimShaderDirty = true;
            invalidateSelf();
        }
    }

    public void setCornerRadius(float radiusPx) {
        if (mCornerRadius != radiusPx) {
            mCornerRadius = radiusPx;
            mGeometryDirty = true;
            invalidateSelf();
        }
    }

    public void setRimWidth(float widthPx) {
        if (mRimWidthPx != widthPx) {
            mRimWidthPx = widthPx;
            mGeometryDirty = true;
            invalidateSelf();
        }
    }

    public void setGrainAlpha(int alpha) {
        if (mGrainAlpha != alpha) {
            mGrainAlpha = alpha;
            mGrainPaint.setAlpha(alpha);
            invalidateSelf();
        }
    }

    public void setSpecularProgress(float progress) {
        if (mSpecularProgress != progress) {
            mSpecularProgress = progress;
            invalidateSelf();
        }
    }

    public void setNeonColor(int color) {
        mNeonColor = color;
    }

    public void setNeonProgress(float progress) {
        if (mNeonProgress != progress) {
            mNeonProgress = progress;
            invalidateSelf();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        final Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }
        updateGeometry();

        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setShader(null);
        mPaint.setColor(mTint);
        canvas.drawPath(mPath, mPaint);

        canvas.drawPath(mPath, mGrainPaint);

        if (mRimShaderDirty) {
            mRimPaint.setShader(new LinearGradient(0f, mRect.top, 0f, mRect.bottom,
                    mRimTop, mRimBottom, Shader.TileMode.CLAMP));
            mRimShaderDirty = false;
        }
        canvas.drawPath(mRimPath, mRimPaint);

        if (mNeonProgress >= 0f) {
            drawNeon(canvas);
        }
        if (mSpecularProgress >= 0f && mSpecularProgress <= 1f) {
            drawSheen(canvas);
        }
    }

    private void drawNeon(Canvas canvas) {
        final float perimeter = mNeonMeasure.getLength();
        if (perimeter <= 0f) {
            return;
        }
        final float chain = perimeter * NEON_CHAIN_FRACTION;
        final float head = mNeonProgress * (perimeter + chain);
        final float tail = Math.max(0f, head - chain);
        if (tail >= perimeter) {
            return;
        }

        mNeonSegment.reset();
        final float wrapped = head - perimeter;
        if (wrapped <= 0f) {
            mNeonMeasure.getSegment(tail, head, mNeonSegment, true);
        } else {
            mNeonMeasure.getSegment(tail, perimeter, mNeonSegment, true);
            mWrappedNeon.reset();
            mNeonMeasure.getSegment(0f, wrapped, mWrappedNeon, true);
            mNeonSegment.addPath(mWrappedNeon);
        }

        final float alpha = neonAlpha(mNeonProgress);
        mNeonGlowPaint.setColor(neonWithAlpha((int) (alpha * 0x30)));
        mNeonGlowPaint.setStrokeWidth(mRimWidthPx * 5f);
        mNeonGlowPaint.setMaskFilter(mNeonBlur);
        canvas.drawPath(mNeonSegment, mNeonGlowPaint);
        mNeonGlowPaint.setMaskFilter(null);

        mNeonCorePaint.setColor(neonWithAlpha((int) (alpha * 0xFF)));
        mNeonCorePaint.setStrokeWidth(mRimWidthPx * 1.6f);
        canvas.drawPath(mNeonSegment, mNeonCorePaint);
    }

    private float neonAlpha(float p) {
        final float fadeIn = clamp01(p / NEON_FADE_FRACTION);
        final float fadeOut = clamp01((1f - p) / NEON_FADE_FRACTION);
        return fadeIn * fadeOut;
    }

    private int neonWithAlpha(int alpha) {
        return Color.argb(alpha, Color.red(mNeonColor), Color.green(mNeonColor),
                Color.blue(mNeonColor));
    }

    private void drawSheen(Canvas canvas) {
        final float w = mRect.width();
        final float h = mRect.height();
        final float bandW = w * 0.4f;
        final float travel = w + bandW;
        final float x = mRect.left - bandW + mSpecularProgress * travel;

        mSheenMatrix.setTranslate(x, 0f);
        if (mSheenShader != null) {
            mSheenShader.setLocalMatrix(mSheenMatrix);
        }

        final int save = canvas.save();
        canvas.clipPath(mPath);
        canvas.rotate(-22f, mRect.centerX(), mRect.centerY());
        canvas.drawRect(x, mRect.top - h, x + bandW, mRect.bottom + h, mSheenPaint);
        canvas.restoreToCount(save);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        mGeometryDirty = true;
        mRimShaderDirty = true;
    }

    private void updateGeometry() {
        if (!mGeometryDirty) {
            return;
        }
        mRect.set(getBounds());
        mPath.reset();
        mPath.addRoundRect(mRect, mCornerRadius, mCornerRadius, Path.Direction.CW);
        mRimRect.set(mRect);
        mRimRect.inset(mRimWidthPx / 2f, mRimWidthPx / 2f);
        mRimPath.reset();
        mRimPath.addRoundRect(mRimRect, mCornerRadius, mCornerRadius, Path.Direction.CW);
        mRimPaint.setStrokeWidth(mRimWidthPx);
        mNeonMeasurePath.set(mRimPath);
        mNeonMeasure.setPath(mNeonMeasurePath, false);
        mNeonBlur = mRimWidthPx > 0f
                ? new BlurMaskFilter(mRimWidthPx * 5f, BlurMaskFilter.Blur.NORMAL) : null;
        mSheenShader = new LinearGradient(0f, 0f, mRect.width() * 0.4f, 0f,
                new int[]{0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
        mSheenPaint.setShader(mSheenShader);
        mGeometryDirty = false;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    @Override
    public void setAlpha(int alpha) {
        // alpha is baked into the tint
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // unused, no-op
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    private static Bitmap noiseBitmap() {
        Bitmap cached = sCachedNoise;
        if (cached != null) {
            return cached;
        }
        Bitmap bmp = Bitmap.createBitmap(NOISE_SIZE, NOISE_SIZE, Bitmap.Config.ARGB_8888);
        int[] px = new int[NOISE_SIZE * NOISE_SIZE];
        Random rnd = new Random(0x5EED);
        for (int i = 0; i < px.length; i++) {
            boolean light = rnd.nextBoolean();
            int a = rnd.nextInt(0x40);
            px[i] = light ? Color.argb(a, 0xFF, 0xFF, 0xFF) : Color.argb(a, 0x00, 0x00, 0x00);
        }
        bmp.setPixels(px, 0, NOISE_SIZE, 0, 0, NOISE_SIZE, NOISE_SIZE);
        sCachedNoise = bmp;
        return bmp;
    }
}
