/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.settings.prism;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;

// blurred home wallpaper w/ a scrim so text dont drown
final class WallpaperBackdrop extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF bounds = new RectF();
    private final int paper;
    private final Drawable wallpaper;
    private final int sourceWidth;
    private final int sourceHeight;
    private Shader scrim;
    private int width;
    private int height;

    WallpaperBackdrop(Context context) {
        super(context);
        paper = context.getColor(R.color.paper);
        Drawable drawable = null;
        try {
            drawable = WallpaperManager.getInstance(context).getDrawable();
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            Log.w("PetalSettings", "Wallpaper unavailable", unavailable);
        }
        wallpaper = drawable;
        // some wallpapers dont report a size, cheers
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int w = wallpaper == null ? 0 : wallpaper.getIntrinsicWidth();
        int h = wallpaper == null ? 0 : wallpaper.getIntrinsicHeight();
        sourceWidth = w > 0 ? w : metrics.widthPixels;
        sourceHeight = h > 0 ? h : metrics.heightPixels;
        if (wallpaper != null) {
            float radius = 52f * metrics.density;
            setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }
        Log.i("PetalSettings", "Wallpaper " + (wallpaper == null ? "unavailable"
                : sourceWidth + "x" + sourceHeight));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        width = w;
        height = h;
        scrim = new LinearGradient(0, 0, 0, h,
                new int[] {getContext().getColor(R.color.backdrop_top),
                        getContext().getColor(R.color.backdrop_mid),
                        getContext().getColor(R.color.backdrop_bottom)},
                new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        if (wallpaper == null || w == 0 || h == 0) return;
        float scale = Math.max(w / (float) sourceWidth, h / (float) sourceHeight);
        float dx = (w - sourceWidth * scale) / 2f;
        float dy = (h - sourceHeight * scale) / 2f;
        matrix.setScale(scale, scale);
        matrix.postTranslate(dx, dy);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(paper);
        if (wallpaper != null) {
            canvas.save();
            canvas.concat(matrix);
            wallpaper.setBounds(0, 0, sourceWidth, sourceHeight);
            wallpaper.draw(canvas);
            canvas.restore();
        }
        paint.setShader(scrim);
        bounds.set(0, 0, width, height);
        canvas.drawRect(bounds, paint);
        paint.setShader(null);
    }
}
