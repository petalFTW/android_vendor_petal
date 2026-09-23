package org.petalos.depth;

import android.graphics.Bitmap;

final class BackgroundBlur {
    private BackgroundBlur() {}

    static Bitmap apply(Bitmap source, int amount) {
        int width = Math.min(240, source.getWidth());
        int height = Math.max(1, Math.round(source.getHeight() * width
                / (float) source.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(source, width, height, true);
        int[] pixels = new int[width * height];
        int[] scratch = new int[pixels.length];
        small.getPixels(pixels, 0, width, 0, 0, width, height);
        int radius = Math.max(1, Math.round(Math.max(0, Math.min(100, amount)) * width / 4800f));
        for (int pass = 0; pass < 3; pass++) {
            blur(pixels, scratch, width, height, radius, true);
            blur(scratch, pixels, width, height, radius, false);
        }
        Bitmap result = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        Bitmap full = Bitmap.createScaledBitmap(result, source.getWidth(), source.getHeight(), true);
        if (small != source) small.recycle();
        if (result != full) result.recycle();
        return full;
    }

    static Bitmap feather(Bitmap source, int amount) {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);
        byte[] alpha = new byte[pixels.length];
        for (int i = 0; i < pixels.length; i++) alpha[i] = (byte) (pixels[i] >>> 24);
        int radius = Math.max(1, Math.round(width / 360f
                * (0.75f + 3f * Math.max(0, Math.min(100, amount)) / 100f)));
        byte[] softened = MaskAlpha.feather(alpha, width, height, radius);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (pixels[i] & 0x00ffffff) | ((softened[i] & 255) << 24);
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    private static void blur(int[] input, int[] output, int width, int height,
            int radius, boolean horizontal) {
        int lines = horizontal ? height : width;
        int length = horizontal ? width : height;
        int step = horizontal ? 1 : width;
        int count = radius * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int start = horizontal ? line * width : line;
            int red = 0, green = 0, blue = 0;
            for (int offset = -radius; offset <= radius; offset++) {
                int color = input[start + Math.max(0, Math.min(length - 1, offset)) * step];
                red += (color >>> 16) & 255;
                green += (color >>> 8) & 255;
                blue += color & 255;
            }
            for (int position = 0; position < length; position++) {
                output[start + position * step] = 0xff000000
                        | (red / count) << 16 | (green / count) << 8 | blue / count;
                int remove = input[start + Math.max(0, position - radius) * step];
                int add = input[start + Math.min(length - 1, position + radius + 1) * step];
                red += ((add >>> 16) & 255) - ((remove >>> 16) & 255);
                green += ((add >>> 8) & 255) - ((remove >>> 8) & 255);
                blue += (add & 255) - (remove & 255);
            }
        }
    }
}
