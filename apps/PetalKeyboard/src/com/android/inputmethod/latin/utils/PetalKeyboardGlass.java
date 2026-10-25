/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.inputmethod.latin.utils;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.os.Build;

/**
 * One shared slab shader, painted over every key so the whole board reads as glass.
 * Same lighting model as the island glass, just without the animated bits.
 */
public final class PetalKeyboardGlass {

    private static RuntimeShader sShader;
    private static boolean sInitialized;

    private PetalKeyboardGlass() {
    }

    private static RuntimeShader shader() {
        if (!sInitialized) {
            sInitialized = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    sShader = new RuntimeShader(SLAB_AGSL);
                } catch (RuntimeException e) {
                    // compiler hated it, keys just stay flat. never take the ime down
                    sShader = null;
                }
            }
        }
        return sShader;
    }

    /** draw the glass light/shade over one key. caller has already translated to the key. */
    public static void drawKey(final Canvas canvas, final Paint paint, final float width,
            final float height, final float radius, final boolean dark, final float density) {
        // runtime shaders need a gpu canvas, software previews would chuck a wobbly
        if (!canvas.isHardwareAccelerated()) return;
        final RuntimeShader shader = shader();
        if (shader == null || width <= 0f || height <= 0f) return;
        final float r = Math.min(radius, Math.min(width, height) * 0.5f);
        shader.setFloatUniform("center", width * 0.5f, height * 0.5f);
        shader.setFloatUniform("halfSize", width * 0.5f, height * 0.5f);
        shader.setFloatUniform("radius", r);
        shader.setFloatUniform("bandWidth", Math.max(1f, Math.min(width, height) * 0.14f));
        shader.setFloatUniform("gain", dark ? 0.75f : 0.6f);
        shader.setFloatUniform("surface", dark ? 0f : 1f);
        shader.setColorUniform("tint", dark ? 0x16FFFFFF : 0x1CFFFFFF);
        paint.setShader(shader);
        canvas.drawRoundRect(0f, 0f, width, height, r, r, paint);
        paint.setShader(null);
    }

    private static final String SLAB_AGSL = """
        uniform float2 center;
        uniform float2 halfSize;
        uniform float radius;
        uniform float bandWidth;
        uniform float gain;
        uniform float surface;
        layout(color) uniform half4 tint;

        half4 main(float2 xy) {
            float2 p = xy - center;
            float2 q = abs(p) - halfSize + radius;
            float2 outside = max(q, 0.0);
            float d = length(outside) + min(max(q.x, q.y), 0.0) - radius;
            float u = clamp(1.0 + d / bandWidth, 0.0, 1.0);
            float shoulder = smoothstep(0.0, 1.0, u);
            float2 edgeNormal = length(outside) > 0.001 ? normalize(outside)
                    : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
            edgeNormal *= sign(p);
            float3 n = float3(edgeNormal * u, sqrt(max(1.0 - u * u, 0.0)));

            float3 lightDir = normalize(float3(-0.45, -0.60, 0.80));
            float3 halfway = normalize(lightDir + float3(0.0, 0.0, 1.0));
            float reflection = pow(max(dot(n, halfway), 0.0), 18.0);
            float facing = dot(edgeNormal, normalize(float2(-0.45, -0.60)));
            float fresnel = u * u * u * u;
            float darkMode = 1.0 - surface;
            float highlight = gain * shoulder * (mix(0.12, 0.20, darkMode) * reflection
                    + mix(0.06, 0.10, darkMode) * fresnel * max(facing, 0.0));
            float shade = gain * shoulder * mix(0.045, 0.13, darkMode) * max(-facing, 0.0);

            float clearK = mix(0.18, 0.55, darkMode);
            half a = tint.a * half(1.0 - clearK * shoulder * min(gain, 1.0));
            half4 col = half4(tint.rgb * a, a);
            half darkAlpha = half(clamp(shade, 0.0, 0.22));
            col = half4(col.rgb * (1.0 - darkAlpha), darkAlpha + col.a * (1.0 - darkAlpha));
            half lightAlpha = half(clamp(highlight, 0.0, 0.32));
            return half4(half3(lightAlpha) + col.rgb * (1.0 - lightAlpha),
                         lightAlpha + col.a * (1.0 - lightAlpha));
        }
        """;
}
