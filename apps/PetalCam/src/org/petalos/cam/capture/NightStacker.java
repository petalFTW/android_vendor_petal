/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */

package org.petalos.cam.capture;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.util.List;

/** Software night mode: stacks a handheld burst into one cleaner, brighter shot. */
public final class NightStacker {

    private static final String TAG = "PetalCamNight";

    private static final int THUMB_SAMPLE = 8;
    private static final int THUMB_SEARCH = 12;
    private static final int TILE = 512;
    // rgb distance past this means it moved, leave it out or you get ghosts
    private static final int GHOST_LIMIT = 90;
    // scenes brighter than this get no shadow lift at all
    private static final int DARK_LUMA = 96;
    private static final float MAX_LIFT = 0.4f;

    private NightStacker() {
    }

    /** Null means give up and save a plain frame. */
    @Nullable
    public static byte[] merge(List<byte[]> frames, int jpegQuality) {
        Bitmap base = null;
        try {
            Thumb[] thumbs = new Thumb[frames.size()];
            int best = -1;
            long bestSharp = -1;
            for (int i = 0; i < thumbs.length; i++) {
                thumbs[i] = Thumb.of(frames.get(i));
                if (thumbs[i] != null && thumbs[i].sharpness() > bestSharp) {
                    best = i;
                    bestSharp = thumbs[i].sharpness();
                }
            }
            if (best < 0) {
                return null;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inMutable = true;
            byte[] baseJpeg = frames.get(best);
            base = BitmapFactory.decodeByteArray(baseJpeg, 0, baseJpeg.length, opts);
            if (base == null) {
                return null;
            }
            int w = base.getWidth();
            int h = base.getHeight();
            int[] basePx = new int[w * h];
            base.getPixels(basePx, 0, w, 0, 0, w, h);

            // 8 frames of 255 sits fine in a char, ints would be 3x the ram for nothing
            char[] sumR = new char[w * h];
            char[] sumG = new char[w * h];
            char[] sumB = new char[w * h];
            byte[] count = new byte[w * h];
            for (int i = 0; i < basePx.length; i++) {
                int c = basePx[i];
                sumR[i] = (char) ((c >> 16) & 255);
                sumG[i] = (char) ((c >> 8) & 255);
                sumB[i] = (char) (c & 255);
                count[i] = 1;
            }

            int merged = 1;
            int[] row = new int[w];
            for (int i = 0; i < thumbs.length; i++) {
                if (i == best || thumbs[i] == null) {
                    continue;
                }
                byte[] jpeg = frames.get(i);
                Bitmap frame = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                if (frame == null) {
                    continue;
                }
                if (frame.getWidth() == w && frame.getHeight() == h) {
                    int[] shift = thumbs[best].shiftTo(thumbs[i]);
                    refine(basePx, frame, w, h, shift);
                    accumulate(basePx, frame, w, h, shift, row, sumR, sumG, sumB, count);
                    merged++;
                }
                frame.recycle();
            }

            int[] curve = toneCurve(thumbs[best].meanLuma());
            for (int i = 0; i < basePx.length; i++) {
                int n = count[i];
                basePx[i] = 0xFF000000
                        | curve[sumR[i] * 4 / n] << 16
                        | curve[sumG[i] * 4 / n] << 8
                        | curve[sumB[i] * 4 / n];
            }
            base.setPixels(basePx, 0, w, 0, 0, w, h);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            base.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out);
            Log.i(TAG, "stacked " + merged + "/" + thumbs.length + " frames");
            return out.toByteArray();
        } catch (Throwable t) {
            // oom on a huge sensor mostly, a plain frame still beats losing the shot
            Log.w(TAG, "Stack failed", t);
            return null;
        } finally {
            if (base != null) {
                base.recycle();
            }
        }
    }

    // thumbs only get us within 8px, that's a blurry mess, so nail it on a centre tile
    private static void refine(int[] basePx, Bitmap frame, int w, int h, int[] shift) {
        int size = Math.min(TILE, Math.min(w, h) / 2);
        int left = (w - size) / 2;
        int top = (h - size) / 2;
        int range = THUMB_SAMPLE;
        int padded = size + range * 2;
        int fx = left + shift[0] - range;
        int fy = top + shift[1] - range;
        if (fx < 0 || fy < 0 || fx + padded > w || fy + padded > h) {
            return;
        }
        int[] tile = new int[padded * padded];
        frame.getPixels(tile, 0, padded, fx, fy, padded, padded);
        long bestCost = Long.MAX_VALUE;
        int bestX = 0;
        int bestY = 0;
        for (int oy = -range; oy <= range; oy++) {
            for (int ox = -range; ox <= range; ox++) {
                long cost = 0;
                for (int y = 0; y < size; y += 2) {
                    int b = (top + y) * w + left;
                    int t = (y + oy + range) * padded + ox + range;
                    for (int x = 0; x < size; x += 2) {
                        cost += Math.abs(((basePx[b + x] >> 8) & 255)
                                - ((tile[t + x] >> 8) & 255));
                    }
                }
                if (cost < bestCost) {
                    bestCost = cost;
                    bestX = ox;
                    bestY = oy;
                }
            }
        }
        shift[0] += bestX;
        shift[1] += bestY;
    }

    private static void accumulate(int[] basePx, Bitmap frame, int w, int h, int[] shift,
            int[] row, char[] sumR, char[] sumG, char[] sumB, byte[] count) {
        for (int y = 0; y < h; y++) {
            int fy = y + shift[1];
            if (fy < 0 || fy >= h) {
                continue;
            }
            frame.getPixels(row, 0, w, 0, fy, w, 1);
            int line = y * w;
            for (int x = 0; x < w; x++) {
                int fx = x + shift[0];
                if (fx < 0 || fx >= w) {
                    continue;
                }
                int c = row[fx];
                int p = basePx[line + x];
                int r = (c >> 16) & 255;
                int g = (c >> 8) & 255;
                int b = c & 255;
                int distance = Math.abs(r - ((p >> 16) & 255))
                        + Math.abs(g - ((p >> 8) & 255)) + Math.abs(b - (p & 255));
                if (distance < GHOST_LIMIT) {
                    sumR[line + x] += r;
                    sumG[line + x] += g;
                    sumB[line + x] += b;
                    count[line + x]++;
                }
            }
        }
    }

    // indexed in quarter steps so the lift doesn't band the darks
    private static int[] toneCurve(int meanLuma) {
        float dark = Math.max(0f, (DARK_LUMA - meanLuma) / (float) DARK_LUMA);
        double gamma = 1.0 - MAX_LIFT * dark;
        int[] curve = new int[1024];
        for (int i = 0; i < curve.length; i++) {
            curve[i] = (int) Math.round(255.0 * Math.pow(i / 1023.0, gamma));
        }
        return curve;
    }

    private static final class Thumb {
        final int[] luma;
        final int w;
        final int h;

        private Thumb(int[] luma, int w, int h) {
            this.luma = luma;
            this.w = w;
            this.h = h;
        }

        @Nullable
        static Thumb of(byte[] jpeg) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = THUMB_SAMPLE;
            Bitmap small = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
            if (small == null) {
                return null;
            }
            int w = small.getWidth();
            int h = small.getHeight();
            int[] px = new int[w * h];
            small.getPixels(px, 0, w, 0, 0, w, h);
            small.recycle();
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                px[i] = (((c >> 16) & 255) * 2 + ((c >> 8) & 255) * 5 + (c & 255)) >> 3;
            }
            return new Thumb(px, w, h);
        }

        long sharpness() {
            long sum = 0;
            for (int y = 1; y < h - 1; y += 2) {
                for (int x = 1; x < w - 1; x += 2) {
                    int i = y * w + x;
                    sum += Math.abs(luma[i] - luma[i - 1]) + Math.abs(luma[i] - luma[i - w]);
                }
            }
            return sum;
        }

        int meanLuma() {
            long sum = 0;
            for (int v : luma) {
                sum += v;
            }
            return (int) (sum / Math.max(1, luma.length));
        }

        /** full res x,y to add to a base coord to land on the other frame */
        int[] shiftTo(Thumb other) {
            int[] best = {0, 0};
            if (other.w != w || other.h != h) {
                return best;
            }
            int margin = THUMB_SEARCH;
            long bestCost = Long.MAX_VALUE;
            for (int oy = -margin; oy <= margin; oy++) {
                for (int ox = -margin; ox <= margin; ox++) {
                    long cost = 0;
                    for (int y = margin; y < h - margin; y += 2) {
                        for (int x = margin; x < w - margin; x += 2) {
                            cost += Math.abs(luma[y * w + x]
                                    - other.luma[(y + oy) * w + x + ox]);
                        }
                    }
                    if (cost < bestCost) {
                        bestCost = cost;
                        best[0] = ox * THUMB_SAMPLE;
                        best[1] = oy * THUMB_SAMPLE;
                    }
                }
            }
            return best;
        }
    }
}
