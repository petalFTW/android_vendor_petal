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

package org.petalos.hub;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;

import java.util.function.Consumer;

/** glass + backdrop blur plumbing */
public final class PetalGlass {

    private PetalGlass() {
    }

    public static PetalGlassDrawable newCardDrawable(Context context, float radiusDp,
            int tint, int rimTop, int rimBottom, int grainAlpha) {
        final float density = context.getResources().getDisplayMetrics().density;
        PetalGlassDrawable glass = new PetalGlassDrawable();
        glass.setCornerRadius(radiusDp * density);
        glass.setRimWidth(Math.max(1f, density));
        glass.setRim(rimTop, rimBottom);
        glass.setGrainAlpha(grainAlpha);
        glass.setTintColor(tint);
        return glass;
    }

    /** slap glass background and blur on a view */
    public static void applyCard(View view, float radiusDp, float blurRadiusDp) {
        Context context = view.getContext();
        final float density = context.getResources().getDisplayMetrics().density;
        final float radiusPx = radiusDp * density;

        PetalGlassDrawable glass = newCardDrawable(context, radiusDp,
                view.getResources().getColor(R.color.petal_glass_tint, context.getTheme()),
                view.getResources().getColor(R.color.petal_glass_rim_top, context.getTheme()),
                view.getResources().getColor(R.color.petal_glass_rim_bottom, context.getTheme()),
                0x10);
        view.setBackground(glass);
        view.setClipToOutline(true);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View target, Outline outline) {
                outline.setRoundRect(0, 0, target.getWidth(), target.getHeight(), radiusPx);
            }
        });
        applyBackdropBlur(view, blurRadiusDp);
    }

    /** backdrop blur, when the window manager supports it */
    public static void applyBackdropBlur(final View view, final float blurRadiusDp) {
        final Context context = view.getContext();
        final WindowManager windowManager = context.getSystemService(WindowManager.class);
        if (windowManager == null) {
            return;
        }
        final float density = context.getResources().getDisplayMetrics().density;
        final RenderEffect blur = RenderEffect.createBlurEffect(
                blurRadiusDp * density, blurRadiusDp * density, Shader.TileMode.CLAMP);

        final Consumer<Boolean> listener = enabled -> {
            if (view.isAttachedToWindow()) {
                view.setBackdropRenderEffect(enabled ? blur : null);
            }
        };

        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View target) {
                windowManager.addCrossWindowBlurEnabledListener(Runnable::run, listener);
            }

            @Override
            public void onViewDetachedFromWindow(View target) {
                windowManager.removeCrossWindowBlurEnabledListener(listener);
                target.setBackdropRenderEffect(null);
            }
        });

        if (view.isAttachedToWindow()) {
            windowManager.addCrossWindowBlurEnabledListener(Runnable::run, listener);
        }
    }
}
