package org.petalos.config;

import android.graphics.*;

public final class DepthClockMaterials {
    public static final int HEAVY = 10;
    public static final int SOFT = 11;
    public static final int FROST = 12;
    public static final int SMOKE = 13;
    public static final int BLOOM = 14;
    public static final int PEARL = 15;
    // square ones put hours on top, minutes below
    public static final int FROST_SQUARE = 16;
    public static final int SMOKE_SQUARE = 17;
    public static final int BLOOM_SQUARE = 18;
    public static final int LIQUID = 19;
    public static final int LAST = LIQUID;

    public static boolean isSquare(int style) {
        return style >= FROST_SQUARE && style <= BLOOM_SQUARE;
    }
    private final LiquidClockMaterial liquid = new LiquidClockMaterial();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path glyphs = new Path();
    private final RectF destination = new RectF();

    // our bundled clock face, one set per process
    private static Typeface sBase;

    public static void setBaseTypeface(Typeface base) {
        sBase = base;
    }

    // rubik if it loaded, otherwise old mate sans
    public static Typeface weighted(int weight, boolean serif) {
        if (!serif && sBase != null) {
            return Typeface.create(sBase, weight, false);
        }
        return Typeface.create(
                Typeface.create(serif ? "serif" : "sans-serif", Typeface.NORMAL), weight, false);
    }

    public static Typeface typeface(int style) {
        return weighted(style == SOFT || style == PEARL || style == LIQUID ? 800 : 900, false);
    }

    public boolean draw(Canvas canvas, String text, float x, float y, int style,
            Paint source, Bitmap backdrop, int width, int height) {
        if (style < HEAVY || style > LAST) return false;
        if (style == LIQUID) {
            liquid.draw(canvas, text, x, y, source, backdrop, width, height);
            return true;
        }
        paint.set(source);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        int ink = source.getColor();
        float size = source.getTextSize();
        if (style == HEAVY || style == SOFT) {
            paint.setShadowLayer(size * 0.035f, 0, size * 0.02f, 0x55000000);
            canvas.drawText(text, x, y, paint);
            paint.clearShadowLayer();
            return true;
        }
        // squares just borrow the base material
        int base = style == FROST_SQUARE ? FROST
                : style == SMOKE_SQUARE ? SMOKE
                : style == BLOOM_SQUARE ? BLOOM : style;
        if (base == FROST || base == SMOKE) {
            glyphs.reset();
            paint.getTextPath(text, 0, text.length(), x, y, glyphs);
            int save = canvas.save();
            canvas.clipPath(glyphs);
            if (backdrop != null) {
                destination.set(0, 0, width, height);
                canvas.drawBitmap(backdrop, null, destination, paint);
            }
            paint.setShader(new LinearGradient(0, y - size, size, y,
                    base == FROST ? new int[]{0xCCFFFFFF, 0x507FADC0, 0xA0FFFFFF}
                            : new int[]{0xC04B4D58, 0xB0181920, 0x805E6270},
                    null, Shader.TileMode.CLAMP));
            canvas.drawPaint(paint);
            canvas.restoreToCount(save);
            paint.setShader(new LinearGradient(0, y - size, 0, y,
                    0xEEFFFFFF, 0x407FA5B0, Shader.TileMode.CLAMP));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * 0.008f);
            canvas.drawText(text, x, y, paint);
        } else {
            paint.setShader(new LinearGradient(x, y - size, x + size * 2, y,
                    base == BLOOM ? new int[]{0xFFB8EEE3, 0xFFD0C5F2, 0xFFF3C8DD, ink}
                            : new int[]{0xFFFFFFFF, 0xFFDCEAE8, 0xFFB9AACD, 0xFFFFF0E6},
                    null, Shader.TileMode.CLAMP));
            canvas.drawText(text, x, y, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * 0.006f);
            paint.setColor(0x99FFFFFF);
            canvas.drawText(text, x, y, paint);
        }
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        return true;
    }
}
