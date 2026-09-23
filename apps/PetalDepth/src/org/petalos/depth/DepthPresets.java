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

package org.petalos.depth;

import org.petalos.config.PetalConfig;

// clock presets
public final class DepthPresets {

    /** one saved clock config */
    public static class Preset {
        public final int nameRes;
        public final int style;
        public final int anchorPct;
        public final int anchorXPct;
        public final int scalePct;
        public final boolean stackVertical;
        public final int ink;

        Preset(int nameRes, int style, int anchorPct, int anchorXPct, int scalePct,
                boolean stackVertical,
                int ink) {
            this.nameRes = nameRes;
            this.style = style;
            this.anchorPct = anchorPct;
            this.anchorXPct = anchorXPct;
            this.scalePct = scalePct;
            this.stackVertical = stackVertical;
            this.ink = ink;
        }
    }

    /** big red poster digits, low */
    public static final Preset CINEMA = new Preset(
            R.string.depth_preset_cinema, DepthClockRenderer.STYLE_POSTER,
            30, 27, 94, true, 0xFFFF3B30);

    /** solid white digits, high up */
    public static final Preset SPOTLIGHT = new Preset(
            R.string.depth_preset_spotlight, DepthClockRenderer.STYLE_SOLID,
            43, 50, 118, false, 0xFFF2F2F2);

    /** glass outline over the photo */
    public static final Preset FIREWORKS = new Preset(
            R.string.depth_preset_fireworks, DepthClockRenderer.STYLE_GLASS,
            25, 50, 120, false, 0xFFEAF2FF);

    /** gradient that fades downward */
    public static final Preset FADE = new Preset(
            R.string.depth_preset_fade, DepthClockRenderer.STYLE_FADE,
            66, 50, 124, false, 0xFFF2F2F2);

    /** solid and outline alternating */
    public static final Preset MIX = new Preset(
            R.string.depth_preset_mix, DepthClockRenderer.STYLE_MIX,
            28, 50, 104, false, 0xFFFFFFFF);

    /** fake 3d extrude, gold */
    public static final Preset MONUMENT = new Preset(
            R.string.depth_preset_monument, DepthClockRenderer.STYLE_MONO,
            60, 50, 110, false, 0xFFFFC94D);

    /** serif with wide tracking */
    public static final Preset EDITORIAL = new Preset(
            R.string.depth_preset_editorial, DepthClockRenderer.STYLE_VOGUE,
            22, 50, 88, true, 0xFFFFFFFF);

    /** glow, good on dark photos */
    public static final Preset NEON = new Preset(
            R.string.depth_preset_neon, DepthClockRenderer.STYLE_NEON,
            24, 50, 100, false, 0xFF9BE8FF);

    /** order shown in the picker */
    public static final Preset[] ALL = {
        new Preset(R.string.depth_preset_liquid, org.petalos.config.DepthClockMaterials.LIQUID,
                30, 50, 100, false, 0xFFFFFFFF),
        new Preset(R.string.depth_preset_liquid_stack, org.petalos.config.DepthClockMaterials.LIQUID,
                35, 50, 100, true, 0xFFFFFFFF),
        new Preset(R.string.depth_preset_heavy, 10, 28, 50, 100, false, 0xFFF2F2F2),
        new Preset(R.string.depth_preset_soft, 11, 30, 50, 100, true, 0xFFF5DDD9),
        new Preset(R.string.depth_preset_frost, 12, 30, 50, 100, false, 0xFFFFFFFF),
        new Preset(R.string.depth_preset_smoke, 13, 30, 50, 100, false, 0xFFBFC5D2),
        new Preset(R.string.depth_preset_bloom, 14, 28, 50, 100, false, 0xFFF1CCD9),
        new Preset(R.string.depth_preset_pearl, 15, 30, 50, 100, true, 0xFFF2EEEE),
        // squares stack the digits
        new Preset(R.string.depth_preset_frost_square, 16, 30, 50, 100, true, 0xFFFFFFFF),
        new Preset(R.string.depth_preset_smoke_square, 17, 30, 50, 100, true, 0xFFBFC5D2),
        new Preset(R.string.depth_preset_bloom_square, 18, 28, 50, 100, true, 0xFFF1CCD9),
        CINEMA, SPOTLIGHT, FIREWORKS, FADE, MIX, MONUMENT, EDITORIAL, NEON
    };

    private DepthPresets() {
    }

    /** write a preset to Settings.System */
    public static void apply(android.content.Context context, Preset preset) {
        android.provider.Settings.System.putInt(context.getContentResolver(),
                PetalConfig.KEY_DEPTH_CLOCK_STYLE, preset.style);
        android.provider.Settings.System.putInt(context.getContentResolver(),
                PetalConfig.KEY_DEPTH_CLOCK_ANCHOR, preset.anchorPct);
        android.provider.Settings.System.putInt(context.getContentResolver(),
                PetalConfig.KEY_DEPTH_CLOCK_X, preset.anchorXPct);
        android.provider.Settings.System.putInt(context.getContentResolver(),
                PetalConfig.KEY_DEPTH_CLOCK_SCALE, preset.scalePct);
        android.provider.Settings.System.putInt(context.getContentResolver(),
                PetalConfig.KEY_DEPTH_CLOCK_STACK, preset.stackVertical ? 1 : 0);
        android.provider.Settings.System.putInt(context.getContentResolver(),
                PetalConfig.KEY_DEPTH_CLOCK_COLOR, preset.ink);
    }
}
