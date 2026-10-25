/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.settings.prism;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** NXE's green atmospheric sky and silver floor, behind the receding tile deck. */
final class NxeBackdrop extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader sky;
    private Shader floor;
    private Shader haze;

    NxeBackdrop(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        sky = new LinearGradient(0, 0, w, h * .48f,
                new int[] {0xFF406B08, 0xFF8EAB64, 0xFFB9C7BC}, null, Shader.TileMode.CLAMP);
        floor = new LinearGradient(0, h * .40f, 0, h,
                new int[] {0xFF687477, 0xFFB5C0C2, 0xFF505B60}, null, Shader.TileMode.CLAMP);
        haze = new RadialGradient(w * .18f, h * .23f, w * .9f,
                0x88C5FA42, 0x00C5FA42, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        paint.setShader(sky);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(floor);
        canvas.drawRect(0, getHeight() * .40f, getWidth(), getHeight(), paint);
        paint.setShader(haze);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(null);
    }
}
