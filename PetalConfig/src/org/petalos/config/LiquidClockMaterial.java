/* Copyright (C) 2026 The petalOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.petalos.config;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.util.LruCache;
import android.util.Log;

/** Wallpaper lenses with a rounded meniscus and thin-film colour along the glass edge. */
final class LiquidClockMaterial {
    private static final String SHADER = """
            uniform shader wallpaper;
            uniform shader surface;
            uniform float2 origin;
            uniform float2 maskOrigin;
            uniform float textSize;
            uniform float3 tint;
            float field(float2 p) {
                return surface.eval((p - origin + maskOrigin) * 0.5).a;
            }
            half4 main(float2 p) {
                float stepSize = max(1.0, textSize * 0.009);
                float2 gradient = float2(
                    field(p + float2(stepSize, 0)) - field(p - float2(stepSize, 0)),
                    field(p + float2(0, stepSize)) - field(p - float2(0, stepSize)));
                float edge = clamp(length(gradient) * 4.0, 0.0, 1.0);
                float2 normal = -gradient / max(length(gradient), 0.0001);
                float2 q = (p - origin) / textSize;
                float2 ripple = float2(sin(q.y * 9.0 + q.x * 3.0),
                                       cos(q.x * 8.0 - q.y * 2.0));
                float2 bend = normal * edge * textSize * 0.105
                            + ripple * textSize * 0.012;
                // Separate wavelengths bend slightly differently through the rounded edge.
                float3 glass = float3(wallpaper.eval(p + bend * 1.12).r,
                                      wallpaper.eval(p + bend).g,
                                      wallpaper.eval(p + bend * 0.88).b);
                float facing = dot(normal, normalize(float2(-0.65, -0.76)));
                float highlight = pow(max(facing, 0.0), 5.0) * edge;
                float shadow = max(-facing, 0.0) * edge;
                float3 film = 0.5 + 0.5 * cos(6.28318 *
                    (q.y * 0.65 + q.x * 0.22 + edge * 0.8 + float3(0.0, 0.33, 0.67)));
                float sheen = pow(0.5 + 0.5 * sin(q.y * 5.0 + q.x * 2.0), 18.0);
                float3 colour = glass * (0.92 - shadow * 0.30)
                    + tint * 0.035 + film * edge * 0.24
                    + highlight * 0.78 + sheen * 0.085;
                return half4(clamp(colour, 0.0, 1.0), 1.0);
            }
            """;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path glyphs = new Path();
    private final RectF bounds = new RectF();
    private final Matrix wallpaperMatrix = new Matrix();
    // Two clock runs plus a little room for changing digits. Do not recycle masks still in a display list.
    private final LruCache<String, Mask> masks = new LruCache<>(4);
    private RuntimeShader shader;
    private boolean shaderUnavailable;
    private Bitmap lastBackdrop;
    private BitmapShader wallpaper;

    private static final class Mask {
        final BitmapShader shader;
        final float left, top;
        Mask(BitmapShader shader, float left, float top) {
            this.shader = shader;
            this.left = left;
            this.top = top;
        }
    }

    void draw(Canvas canvas, String text, float x, float y, Paint source,
            Bitmap backdrop, int width, int height) {
        paint.set(source);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.clearShadowLayer();
        float size = source.getTextSize();
        glyphs.reset();
        paint.getTextPath(text, 0, text.length(), x, y, glyphs);
        glyphs.computeBounds(bounds, true);
        if (bounds.isEmpty()) return;

        if (shader == null && !shaderUnavailable) {
            try {
                shader = new RuntimeShader(SHADER);
            } catch (IllegalArgumentException error) {
                shaderUnavailable = true;
                Log.w("LiquidClock", "Using glass fallback", error);
            }
        }
        int save = canvas.save();
        canvas.clipPath(glyphs);
        if (shader != null && backdrop != null && !backdrop.isRecycled()
                && canvas.isHardwareAccelerated()) {
            if (lastBackdrop != backdrop) {
                lastBackdrop = backdrop;
                wallpaper = new BitmapShader(backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            }
            wallpaperMatrix.setScale(width / (float) backdrop.getWidth(),
                    height / (float) backdrop.getHeight());
            wallpaper.setLocalMatrix(wallpaperMatrix);
            Mask mask = maskFor(text, source);
            shader.setInputShader("wallpaper", wallpaper);
            shader.setInputShader("surface", mask.shader);
            shader.setFloatUniform("origin", x, y);
            shader.setFloatUniform("maskOrigin", -mask.left, -mask.top);
            shader.setFloatUniform("textSize", size);
            int ink = source.getColor();
            shader.setFloatUniform("tint", Color.red(ink) / 255f,
                    Color.green(ink) / 255f, Color.blue(ink) / 255f);
            paint.setColor(Color.WHITE);
            paint.setShader(shader);
        } else {
            // Legible material for empty-photo previews and software-rendered thumbnails.
            paint.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                    new int[]{0xFFEBFAFF, 0x886D8C9A, 0x334E5867, 0xAAC6AED9, 0xFFF1FFF9},
                    new float[]{0f, 0.18f, 0.48f, 0.8f, 1f}, Shader.TileMode.CLAMP));
        }
        canvas.drawRect(bounds, paint);
        canvas.restoreToCount(save);

        // A hairline rim keeps transparent digits readable against both bright and dark photos.
        paint.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                new int[]{0xF5FFFFFF, 0x99DFFAFF, 0x30363050, 0x99F4C9EB, 0xDDE9FFFB},
                null, Shader.TileMode.CLAMP));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(Math.max(0.75f, size * 0.005f));
        canvas.drawPath(glyphs, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
    }

    private Mask maskFor(String text, Paint source) {
        String key = text + ":" + source.getTextSize() + ":" + source.getTypeface();
        Mask cached = masks.get(key);
        if (cached != null) return cached;
        Paint maskPaint = new Paint(source);
        maskPaint.setShader(null);
        maskPaint.setColor(Color.WHITE);
        maskPaint.setStyle(Paint.Style.FILL);
        maskPaint.clearShadowLayer();
        Path path = new Path();
        maskPaint.getTextPath(text, 0, text.length(), 0, 0, path);
        RectF box = new RectF();
        path.computeBounds(box, true);
        float blur = Math.max(1f, source.getTextSize() * 0.035f);
        box.inset(-blur * 3f, -blur * 3f);
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, (int) Math.ceil(box.width() * 0.5f)),
                Math.max(1, (int) Math.ceil(box.height() * 0.5f)), Bitmap.Config.ARGB_8888);
        Canvas maskCanvas = new Canvas(bitmap);
        maskCanvas.scale(0.5f, 0.5f);
        maskCanvas.translate(-box.left, -box.top);
        maskPaint.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
        maskCanvas.drawPath(path, maskPaint);
        Mask result = new Mask(new BitmapShader(bitmap, Shader.TileMode.CLAMP,
                Shader.TileMode.CLAMP), box.left, box.top);
        masks.put(key, result);
        return result;
    }
}
