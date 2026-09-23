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

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Collections;

/**
 * SplitterNet (CVPR 2024, MIDD-trained) on TF Lite, float32. Tiles at 256px
 * with a feathered overlap, GPU delegate first, XNNPACK CPU fallback. NHWC,
 * sRGB 0..1 in and out.
 */
public final class DenoiseEngine {

    private static final int TILE = 256;
    private static final int OVERLAP = 32;
    // cap for the everyday shots, ultrawide runs uncapped at full sensor res
    private static final int MAX_EDGE = 1536;
    private static final String MODEL = "petal_splitternet.tflite";
    private static final String TAG = "PetalCamDenoise";

    private static volatile DenoiseEngine sInstance;

    private final Context mApp;
    private Interpreter mInterpreter;
    private GpuDelegate mGpu;
    private final FloatBuffer mIn = ByteBuffer
            .allocateDirect(TILE * TILE * 3 * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer();
    private final FloatBuffer mOut = ByteBuffer
            .allocateDirect(TILE * TILE * 3 * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer();
    private final int[] mTilePx = new int[TILE * TILE];

    private DenoiseEngine(Context context) throws Exception {
        mApp = context.getApplicationContext();
        Interpreter gpu = null;
        try {
            gpu = buildInterpreter(readModel(mApp), true);
            configure(gpu);
            warmup(gpu);
            mInterpreter = gpu;
            android.util.Log.i(TAG, "denoiser running on gpu");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "gpu denoiser no good, falling back to cpu", t);
            if (gpu != null) {
                try {
                    gpu.close();
                } catch (Throwable ignored) {
                }
            }
            mInterpreter = buildInterpreter(readModel(mApp), false);
            configure(mInterpreter);
        }
    }

    // the graph ships with a dynamic input, pin it to our tile before any run
    private void configure(Interpreter interpreter) {
        try {
            interpreter.resizeInput(0, new int[]{1, TILE, TILE, 3});
            interpreter.allocateTensors();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "couldn't pin denoiser input to " + TILE, t);
        }
    }

    // one silent tile so a broken delegate blows up here, not on a real shot
    private void warmup(Interpreter interpreter) {
        mIn.rewind();
        for (int i = 0; i < TILE * TILE * 3; i++) {
            mIn.put(0.5f);
        }
        mIn.rewind();
        mOut.rewind();
        interpreter.run(mIn, mOut);
    }

    private Interpreter buildInterpreter(ByteBuffer model, boolean gpu) {
        Interpreter.Options opts = new Interpreter.Options().setNumThreads(4);
        if (gpu) {
            mGpu = new GpuDelegate();
            opts.addDelegate(mGpu);
        } else {
            opts.setUseXNNPACK(true);
        }
        return new Interpreter(model, opts);
    }

    public static DenoiseEngine get(Context context) throws Exception {
        if (sInstance == null) {
            synchronized (DenoiseEngine.class) {
                if (sInstance == null) {
                    sInstance = new DenoiseEngine(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private static ByteBuffer readModel(Context context) throws Exception {
        try (java.io.InputStream in = context.getAssets().open(MODEL)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[65536];
            int n;
            while ((n = in.read(chunk)) > 0) {
                bos.write(chunk, 0, n);
            }
            ByteBuffer buf = ByteBuffer.allocateDirect(bos.size());
            buf.put(bos.toByteArray());
            buf.rewind();
            return buf;
        }
    }

    /**
     * Denoises src with NAFNet, blending by strength 0..1. Never mutates src
     * (decoded jpegs are immutable), returns a fresh bitmap. Big shots run at
     * MAX_EDGE then get blended back so the wait stays human.
     */
    public Bitmap denoise(Bitmap src, float strength) {
        int w = src.getWidth();
        int h = src.getHeight();
        float k = Math.max(0f, Math.min(1f, strength));
        if (k <= 0f || w < 8 || h < 8) {
            return src;
        }

        if (k >= 0.95f) {
            // ultrawide gets the whole hog, full sensor res and no upscale
            // games, otherwise the fine grain just survives the correction
            Bitmap work = src.isMutable() ? src : src.copy(Bitmap.Config.ARGB_8888, true);
            denoiseInPlace(work, 1f);
            return work;
        }

        if (Math.max(w, h) <= MAX_EDGE) {
            Bitmap work = src.isMutable() ? src : src.copy(Bitmap.Config.ARGB_8888, true);
            denoiseInPlace(work, k);
            return work;
        }

        float scale = MAX_EDGE / (float) Math.max(w, h);
        int sw = Math.max(8, Math.round(w * scale));
        int sh = Math.max(8, Math.round(h * scale));
        Bitmap small = Bitmap.createScaledBitmap(src, sw, sh, true);
        Bitmap den = small.copy(Bitmap.Config.ARGB_8888, true);
        denoiseInPlace(den, 1f);
        Bitmap denUp = Bitmap.createScaledBitmap(den, w, h, true);
        Bitmap baseUp = Bitmap.createScaledBitmap(small, w, h, true);
        small.recycle();
        den.recycle();

        // residual correction: orig full-res detail + k * (denoised - original),
        // upscaling a smooth frame straight in smears the image into blotches
        int[] a = new int[w];
        int[] b = new int[w];
        int[] c = new int[w];
        for (int y = 0; y < h; y++) {
            src.getPixels(a, 0, w, 0, y, w, 1);
            denUp.getPixels(b, 0, w, 0, y, w, 1);
            baseUp.getPixels(c, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int o = a[x];
                int dn = b[x];
                int bs = c[x];
                int r = (o >> 16) & 0xFF;
                int g = (o >> 8) & 0xFF;
                int bl = o & 0xFF;
                r += Math.round(k * (((dn >> 16) & 0xFF) - ((bs >> 16) & 0xFF)));
                g += Math.round(k * (((dn >> 8) & 0xFF) - ((bs >> 8) & 0xFF)));
                bl += Math.round(k * ((dn & 0xFF) - (bs & 0xFF)));
                b[x] = (o & 0xFF000000)
                        | (Math.max(0, Math.min(255, r)) << 16)
                        | (Math.max(0, Math.min(255, g)) << 8)
                        | Math.max(0, Math.min(255, bl));
            }
            denUp.setPixels(b, 0, w, 0, y, w, 1);
        }
        baseUp.recycle();
        return denUp;
    }

    /** Runs the tiled inference straight into the bitmap, no big buffers. */
    private void denoiseInPlace(Bitmap src, float k) {
        int w = src.getWidth();
        int h = src.getHeight();
        int step = TILE - OVERLAP;
        for (int ty = 0; ty < h; ty += step) {
            for (int tx = 0; tx < w; tx += step) {
                processTile(src, w, h, tx, ty, k);
            }
        }
    }

    private void processTile(Bitmap src, int w, int h, int tx, int ty, float k) {
        int tw = Math.min(TILE, w - tx);
        int th = Math.min(TILE, h - ty);
        if (tw <= 0 || th <= 0) {
            return;
        }
        src.getPixels(mTilePx, 0, tw, tx, ty, tw, th);

        // NHWC, sRGB 0..1, edge-replicated out to the full tile
        mIn.rewind();
        for (int y = 0; y < th; y++) {
            int row = y * tw;
            for (int x = 0; x < tw; x++) {
                putRgb(mIn, mTilePx[row + x]);
            }
            for (int x = tw; x < TILE; x++) {
                putRgb(mIn, mTilePx[row + tw - 1]);
            }
        }
        int lastRow = (th - 1) * tw;
        for (int y = th; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                putRgb(mIn, mTilePx[lastRow + Math.min(x, tw - 1)]);
            }
        }
        mIn.rewind();
        mOut.rewind();

        try {
            mInterpreter.run(mIn, mOut);
            mOut.rewind();

            // blend denoised rgb back, feather the overlap band
            for (int y = 0; y < th; y++) {
                float fy = edgeWeight(ty == 0 ? 1f : Math.min(1f, y / (float) OVERLAP));
                int row = y * tw;
                for (int x = 0; x < tw; x++) {
                    float fx = edgeWeight(tx == 0 ? 1f
                            : Math.min(1f, x / (float) OVERLAP));
                    float f = fy * fx * k;
                    if (f <= 0f) {
                        continue;
                    }
                    int i = row + x;
                    int c = mTilePx[i];
                    int r = (c >> 16) & 0xFF;
                    int g = (c >> 8) & 0xFF;
                    int b = c & 0xFF;
                    int p = (y * TILE + x) * 3;
                    // clamp the net output, it loves overshooting into clipping
                    int dr = Math.max(0, Math.min(255, Math.round(mOut.get(p) * 255f)));
                    int dg = Math.max(0, Math.min(255, Math.round(mOut.get(p + 1) * 255f)));
                    int db = Math.max(0, Math.min(255, Math.round(mOut.get(p + 2) * 255f)));
                    r = Math.round(r + (dr - r) * f);
                    g = Math.round(g + (dg - g) * f);
                    b = Math.round(b + (db - b) * f);
                    mTilePx[i] = (c & 0xFF000000)
                            | (Math.max(0, Math.min(255, r)) << 16)
                            | (Math.max(0, Math.min(255, g)) << 8)
                            | Math.max(0, Math.min(255, b));
                }
            }
            src.setPixels(mTilePx, 0, tw, tx, ty, tw, th);
        } catch (Throwable t) {
            // one bad tile never kills the photo
        }
    }

    // one ARGB pixel -> three normalised sRGB floats, in R G B order
    private static void putRgb(FloatBuffer out, int c) {
        out.put(((c >> 16) & 0xFF) / 255f);
        out.put(((c >> 8) & 0xFF) / 255f);
        out.put((c & 0xFF) / 255f);
    }

    private static float edgeWeight(float v) {
        // smooth ramp so tile seams dont show up as banding
        return v * v * (3f - 2f * v);
    }
}
