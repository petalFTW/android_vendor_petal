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

package org.petalos.cam.capture;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Depth-of-field (portrait) engine.
 *
 * <p>Runs Depth Anything V2 Small (Apache-2.0, int8) on ONNX Runtime to get a
 * relative depth map, then composes a synthetic bokeh: the nearest subject
 * stays sharp while everything behind it is blurred by a depth-derived amount.
 *
 * <p>This is a post-capture effect, not realtime. It runs on the same
 * background executor as the denoiser, so the shutter and the ripple never
 * stall.
 */
public final class PortraitEngine {

    private static final String TAG = "PetalCamPortrait";
    private static final String MODEL = "petal_depth.onnx";
    private static final String INPUT_NAME = "pixel_values";
    private static final String OUTPUT_NAME = "predicted_depth";
    private static final int INPUT = 518;
    // Depth Anything V2 normalization
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};
    // near>=FOCUS_NEAR is razor sharp, near<=FOCUS_FAR is fully defocused
    private static final float FOCUS_NEAR = 0.65f;
    private static final float FOCUS_FAR = 0.30f;

    private static volatile PortraitEngine sInstance;

    private final OrtEnvironment mEnv = OrtEnvironment.getEnvironment();
    private final float[] mInput = new float[3 * INPUT * INPUT];
    private final int[] mSquarePixels = new int[INPUT * INPUT];

    private OrtSession mSession;

    private PortraitEngine(Context context) throws Exception {
        File model = modelFile(context);
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(4);
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            mSession = mEnv.createSession(model.getAbsolutePath(), options);
        }
    }

    public static PortraitEngine get(Context context) throws Exception {
        if (sInstance == null) {
            synchronized (PortraitEngine.class) {
                if (sInstance == null) {
                    sInstance = new PortraitEngine(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    /** True when the model loads; lets callers skip the stage cleanly. */
    public static boolean isAvailable(Context context) {
        try {
            get(context);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Portrait model unavailable", t);
            return false;
        }
    }

    private File modelFile(Context context) throws Exception {
        File directory = new File(context.getNoBackupFilesDir(), "depth-anything-v2-small");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new java.io.IOException("Cannot create model directory");
        }
        File target = new File(directory, MODEL);
        if (target.isFile() && target.length() > 0) {
            return target;
        }
        File staging = new File(directory, MODEL + ".tmp");
        try (InputStream input = context.getAssets().open(MODEL);
                FileOutputStream output = new FileOutputStream(staging)) {
            byte[] buffer = new byte[1 << 16];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
        if (!staging.renameTo(target)) {
            throw new java.io.IOException("Cannot save depth model");
        }
        return target;
    }

    /**
     * Returns a new bitmap with depth-of-field applied. {@code src} must be
     * upright (rotation already baked) so the depth map lines up. Never mutates
     * the source.
     */
    public Bitmap process(Bitmap src, float strength) {
        float k = Math.max(0f, Math.min(1f, strength));
        if (k <= 0f || src == null || src.getWidth() < 8 || src.getHeight() < 8) {
            return src;
        }
        final int w = src.getWidth();
        final int h = src.getHeight();

        float[] depth = infer(src);
        if (depth == null) {
            return src;
        }
        int[] mask = buildDefocusMask(depth, k);

        // low-res gaussian-ish blur of the source, then upscale to full res
        int bw = Math.max(1, w / 4);
        int bh = Math.max(1, h / 4);
        Bitmap blurSmall = Bitmap.createScaledBitmap(src, bw, bh, true)
                .copy(Bitmap.Config.ARGB_8888, true);
        int[] blurPixels = new int[bw * bh];
        blurSmall.getPixels(blurPixels, 0, bw, 0, 0, bw, bh);
        int radius = Math.max(1, Math.round(Math.min(bw, bh) * 0.015f * (0.6f + 0.6f * k)));
        boxBlur(blurPixels, bw, bh, radius);
        boxBlur(blurPixels, bw, bh, Math.max(1, radius / 2));
        blurSmall.setPixels(blurPixels, 0, bw, 0, 0, bw, bh);
        Bitmap blurFull = Bitmap.createScaledBitmap(blurSmall, w, h, true);

        // upscale the depth-derived mask to the frame
        Bitmap maskSmall = Bitmap.createBitmap(mask, INPUT, INPUT, Bitmap.Config.ARGB_8888);
        Bitmap maskFull = Bitmap.createScaledBitmap(maskSmall, w, h, true);

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        composite(src, blurFull, maskFull, out, w, h);

        blurSmall.recycle();
        blurFull.recycle();
        maskSmall.recycle();
        maskFull.recycle();
        return out;
    }

    private float[] infer(Bitmap src) {
        try {
            Bitmap square = Bitmap.createScaledBitmap(src, INPUT, INPUT, true);
            square.getPixels(mSquarePixels, 0, INPUT, 0, 0, INPUT, INPUT);
            if (square != src) {
                square.recycle();
            }
            final int plane = INPUT * INPUT;
            for (int i = 0; i < mSquarePixels.length; i++) {
                int c = mSquarePixels[i];
                float r = ((c >> 16) & 0xFF) / 255f;
                float g = ((c >> 8) & 0xFF) / 255f;
                float b = (c & 0xFF) / 255f;
                mInput[i] = (r - MEAN[0]) / STD[0];
                mInput[plane + i] = (g - MEAN[1]) / STD[1];
                mInput[2 * plane + i] = (b - MEAN[2]) / STD[2];
            }
            long start = SystemClock.uptimeMillis();
            try (OnnxTensor input = OnnxTensor.createTensor(mEnv, FloatBuffer.wrap(mInput),
                    new long[]{1, 3, INPUT, INPUT})) {
                try (OrtSession.Result result = mSession.run(
                        Collections.singletonMap(INPUT_NAME, input))) {
                    OnnxTensor tensor = (OnnxTensor) result.get(OUTPUT_NAME).get();
                    FloatBuffer buffer = tensor.getFloatBuffer();
                    buffer.rewind();
                    float[] depth = new float[plane];
                    buffer.get(depth);
                    Log.d(TAG, "depth inference took "
                            + (SystemClock.uptimeMillis() - start) + "ms");
                    return depth;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Depth inference failed", t);
            return null;
        }
    }

    /** Grayscale bokeh mask: 0 = keep sharp, 255 = fully defocused. */
    private int[] buildDefocusMask(float[] depth, float strength) {
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (float d : depth) {
            if (!Float.isFinite(d)) {
                continue;
            }
            if (d < min) {
                min = d;
            }
            if (d > max) {
                max = d;
            }
        }
        float span = Math.max(1e-6f, max - min);
        int[] mask = new int[depth.length];
        for (int i = 0; i < depth.length; i++) {
            float near = (depth[i] - min) / span;
            // 1 when far, 0 when at/inside the focus plane
            float far = 1f - smoothstep(FOCUS_FAR, FOCUS_NEAR, near);
            int value = Math.round(Math.max(0f, Math.min(1f, far * strength)) * 255f);
            mask[i] = 0xFF000000 | (value << 16) | (value << 8) | value;
        }
        return mask;
    }

    private static void composite(Bitmap sharp, Bitmap blurred, Bitmap mask, Bitmap out,
            int w, int h) {
        final int band = 128;
        int[] s = new int[band * w];
        int[] b = new int[band * w];
        int[] m = new int[band * w];
        int[] o = new int[band * w];
        for (int y = 0; y < h; y += band) {
            int rows = Math.min(band, h - y);
            int n = rows * w;
            sharp.getPixels(s, 0, w, 0, y, w, rows);
            blurred.getPixels(b, 0, w, 0, y, w, rows);
            mask.getPixels(m, 0, w, 0, y, w, rows);
            for (int i = 0; i < n; i++) {
                int a = (m[i] >> 16) & 0xFF;
                int sp = s[i];
                int bp = b[i];
                int sr = (sp >> 16) & 0xFF;
                int sg = (sp >> 8) & 0xFF;
                int sb = sp & 0xFF;
                int br = (bp >> 16) & 0xFF;
                int bg = (bp >> 8) & 0xFF;
                int bb = bp & 0xFF;
                int r = sr + (br - sr) * a / 255;
                int g = sg + (bg - sg) * a / 255;
                int bl = sb + (bb - sb) * a / 255;
                o[i] = 0xFF000000 | (r << 16) | (g << 8) | bl;
            }
            out.setPixels(o, 0, w, 0, y, w, rows);
        }
    }

    /** Separable box blur, run twice, for a cheap smooth defocus. */
    private static void boxBlur(int[] pixels, int width, int height, int radius) {
        if (radius < 1) {
            return;
        }
        int[] scratch = new int[pixels.length];
        blurPass(pixels, scratch, width, height, radius, true);
        blurPass(scratch, pixels, width, height, radius, false);
    }

    private static void blurPass(int[] input, int[] output, int width, int height,
            int radius, boolean horizontal) {
        int lines = horizontal ? height : width;
        int length = horizontal ? width : height;
        int step = horizontal ? 1 : width;
        int count = radius * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int start = horizontal ? line * width : line;
            int red = 0;
            int green = 0;
            int blue = 0;
            for (int offset = -radius; offset <= radius; offset++) {
                int color = input[start + Math.max(0, Math.min(length - 1, offset)) * step];
                red += (color >> 16) & 0xFF;
                green += (color >> 8) & 0xFF;
                blue += color & 0xFF;
            }
            for (int position = 0; position < length; position++) {
                output[start + position * step] = 0xFF000000
                        | ((red / count) << 16) | ((green / count) << 8) | (blue / count);
                int remove = input[start + Math.max(0, position - radius) * step];
                int add = input[start + Math.min(length - 1, position + radius + 1) * step];
                red += ((add >> 16) & 0xFF) - ((remove >> 16) & 0xFF);
                green += ((add >> 8) & 0xFF) - ((remove >> 8) & 0xFF);
                blue += (add & 0xFF) - (remove & 0xFF);
            }
        }
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = (x - edge0) / (edge1 - edge0);
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }
}
