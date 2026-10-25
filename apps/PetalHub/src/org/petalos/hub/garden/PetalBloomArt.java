/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.hub.garden;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

// The First Bloom symbol and palettes, kept in the pack's 1000px coordinates.
final class PetalBloomArt {
    private static final float[] STOPS = {0f, 0.25f, 0.5f, 0.76f, 1f};
    private static final int[][] COLORS = {
            {0xFFF5DCEB, 0xFFD9BDE4, 0xFFAF98D4, 0xFF786DA9, 0xFF434668},
            {0xFFE5EDCE, 0xFFBBD0AF, 0xFF88AC97, 0xFF557D6B, 0xFF294F44},
            {0xFFE4F8B2, 0xFF9DE8C5, 0xFF64D1D3, 0xFF5D9ED0, 0xFF465384},
            {0xFFFFF0B5, 0xFFF5CF95, 0xFFEAA782, 0xFFCD7C76, 0xFF975B6A},
            {0xFFE4FFF0, 0xFFACEDDC, 0xFF6DCABA, 0xFF339B94, 0xFF175E60},
            {0xFFF2F7AD, 0xFFBCE69A, 0xFF69BD91, 0xFF20886E, 0xFF104D40},
            {0xFFFFF0C9, 0xFFF2D694, 0xFFD9B35F, 0xFFAE8944, 0xFF6E5738},
    };
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path[] petals = new Path[3];
    private final Shader[][] gradients = new Shader[COLORS.length][3];

    PetalBloomArt() {
        Path center = new Path();
        center.moveTo(500, 772);
        center.cubicTo(480, 655, 432, 539, 395, 467);
        center.cubicTo(349, 375, 372, 294, 402, 238);
        center.cubicTo(433, 181, 480, 132, 492, 122);
        center.cubicTo(497, 117, 503, 117, 508, 122);
        center.cubicTo(520, 132, 567, 181, 598, 238);
        center.cubicTo(628, 294, 651, 375, 605, 467);
        center.cubicTo(568, 539, 520, 655, 500, 772);
        center.close();
        petals[0] = center;
        Path left = new Path();
        left.moveTo(485, 800);
        left.cubicTo(428, 744, 389, 716, 311, 679);
        left.cubicTo(213, 634, 169, 596, 131, 511);
        left.cubicTo(107, 455, 91, 414, 61, 374);
        left.cubicTo(48, 357, 53, 348, 75, 343);
        left.cubicTo(174, 320, 285, 374, 363, 464);
        left.cubicTo(433, 545, 469, 678, 485, 800);
        left.close();
        petals[1] = left;
        Path right = new Path();
        right.moveTo(515, 800);
        right.cubicTo(572, 744, 611, 716, 689, 679);
        right.cubicTo(787, 634, 831, 596, 869, 511);
        right.cubicTo(893, 455, 909, 414, 939, 374);
        right.cubicTo(952, 357, 947, 348, 925, 343);
        right.cubicTo(826, 320, 715, 374, 637, 464);
        right.cubicTo(567, 545, 531, 678, 515, 800);
        right.close();
        petals[2] = right;
        for (int i = 0; i < COLORS.length; i++) {
            gradients[i][0] = new LinearGradient(473.7886f, 118.25f, 526.2114f, 772f,
                    COLORS[i], STOPS, Shader.TileMode.CLAMP);
            gradients[i][1] = new LinearGradient(140.1284f, 337.702f, 420.3366f, 800f,
                    COLORS[i], STOPS, Shader.TileMode.CLAMP);
            gradients[i][2] = new LinearGradient(881.4261f, 337.702f, 579.6634f, 800f,
                    COLORS[i], STOPS, Shader.TileMode.CLAMP);
        }
    }

    void draw(Canvas canvas, int variety, boolean golden, float centerX, float bottom,
            float width, float height, boolean centerOnly) {
        int save = canvas.save();
        canvas.translate(centerX - width / 2f, bottom - height);
        canvas.scale(width / 1000f, height / 800f);
        int palette = golden ? COLORS.length - 1 : variety;
        for (int i = 0; i < (centerOnly ? 1 : petals.length); i++) {
            paint.setShader(gradients[palette][i]);
            canvas.drawPath(petals[i], paint);
        }
        canvas.restoreToCount(save);
    }
}
