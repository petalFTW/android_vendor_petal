/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.hub.garden;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import java.time.LocalDate;
import java.util.Locale;
import java.util.function.IntConsumer;
import org.petalos.hub.R;

public final class PocketGardenView extends ViewGroup {
    private PocketGarden garden;
    private LocalDate day;
    private final IntConsumer onTend;
    private final PlotView[] plots = new PlotView[PocketGarden.PLOTS];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PetalBloomArt blooms = new PetalBloomArt();
    private final String[] flowerNames;
    private final int frame;
    private final int rim;
    private final int accent;
    private final int secondary;
    private final int soil;
    private final int stem;
    private final int glass;
    private final Typeface typeface;
    private float sceneScale = 1f;
    private float sceneLeft;
    private boolean active;

    public PocketGardenView(Context context, PocketGarden garden, IntConsumer onTend) {
        super(context);
        this.garden = garden;
        this.onTend = onTend;
        day = LocalDate.now();
        flowerNames = getResources().getStringArray(R.array.garden_flowers);
        frame = context.getColor(R.color.petal_glass_tint);
        rim = context.getColor(R.color.petal_glass_rim_top);
        accent = context.getColor(R.color.petal_accent_text);
        secondary = context.getColor(R.color.petal_settings_secondary);
        soil = context.getColor(R.color.garden_soil);
        stem = context.getColor(R.color.garden_stem);
        glass = context.getColor(R.color.petal_glass_tint_strong);
        typeface = getResources().getFont(R.font.petal_rubik);
        setWillNotDraw(false);
        setClipChildren(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        for (int i = 0; i < plots.length; i++) {
            plots[i] = new PlotView(context, i);
            addView(plots[i]);
        }
        showGarden(garden, day, false);
    }

    public void showGarden(PocketGarden garden, LocalDate day, boolean pending) {
        this.garden = garden;
        this.day = day;
        for (PlotView plot : plots) {
            plot.setEnabled(!pending && garden.canTend(day));
            plot.refreshDescription();
        }
        invalidate();
    }

    public void setActive(boolean active) {
        this.active = active;
        if (!active) for (PlotView plot : plots) plot.stopPulse();
    }

    public void pulse(int plot) {
        if (active) plots[plot].playPulse();
    }

    @Override protected void onDetachedFromWindow() {
        setActive(false);
        super.onDetachedFromWindow();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = resolveSize(dp(360), widthSpec);
        int sceneWidth = Math.min(width, dp(460));
        int height = resolveSize(Math.round(sceneWidth * 552f / 360f), heightSpec);
        setMeasuredDimension(width, height);
        sceneScale = Math.min(sceneWidth / 360f, height / 552f);
        sceneLeft = (width - sceneScale * 360) / 2f;
        for (PlotView plot : plots) {
            plot.measure(MeasureSpec.makeMeasureSpec(Math.round(108 * sceneScale), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(132 * sceneScale), MeasureSpec.EXACTLY));
        }
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        for (int i = 0; i < plots.length; i++) {
            int x = Math.round(sceneLeft + (18 + i % 3 * 108) * sceneScale);
            int y = Math.round((12 + i / 3 * 132) * sceneScale);
            plots[i].layout(x, y, x + plots[i].getMeasuredWidth(), y + plots[i].getMeasuredHeight());
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int save = canvas.save();
        canvas.translate(sceneLeft, 0);
        canvas.scale(sceneScale, sceneScale);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(frame);
        canvas.drawRoundRect(2, 2, 358, 550, 28, 28, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(rim);
        canvas.drawRoundRect(2, 2, 358, 550, 28, 28, paint);
        canvas.restoreToCount(save);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class PlotView extends View {
        private final int index;
        private final String number;
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path pot = new Path();
        private final Shader potFill;
        private float pulse;
        private ValueAnimator pulseAnimator;

        PlotView(Context context, int index) {
            super(context);
            this.index = index;
            number = String.format(Locale.getDefault(), "%02d", index + 1);
            ink.setTypeface(typeface);
            ink.setTextAlign(Paint.Align.CENTER);
            pot.moveTo(27, 83);
            pot.lineTo(81, 83);
            pot.lineTo(75, 105);
            pot.quadTo(74, 110, 68, 110);
            pot.lineTo(40, 110);
            pot.quadTo(34, 110, 33, 105);
            pot.close();
            potFill = new LinearGradient(27, 82, 75, 110,
                    new int[] {glass, frame}, null, Shader.TileMode.CLAMP);
            setFocusable(true);
            setClickable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setOnClickListener(view -> {
                if (active && isEnabled()) onTend.accept(index);
            });
        }

        private String flowerName(PocketGarden.Plant plant) {
            return plant.golden ? getContext().getString(R.string.garden_golden_name, flowerNames[plant.variety])
                    : flowerNames[plant.variety];
        }

        void refreshDescription() {
            PocketGarden.Plant plant = garden.get(index);
            String description;
            if (plant == null) description = getContext().getString(R.string.garden_empty_plot, index + 1);
            else if (plant.water == PocketGarden.BLOOM) description = getContext().getString(
                    R.string.garden_bloom_plot, index + 1, flowerName(plant));
            else description = getContext().getString(R.string.garden_growing_plot, index + 1,
                    getContext().getString(plant.water == 0 ? R.string.garden_seed
                            : plant.water == 1 ? R.string.garden_sprout : R.string.garden_bud));
            setContentDescription(description);
            setStateDescription(getContext().getString(garden.canTend(day)
                    ? R.string.garden_daily_ready : R.string.garden_daily_resting));
            invalidate();
        }

        void stopPulse() {
            if (pulseAnimator != null) pulseAnimator.cancel();
            pulseAnimator = null;
            pulse = 0f;
            invalidate();
        }

        void playPulse() {
            stopPulse();
            if (!ValueAnimator.areAnimatorsEnabled()) return;
            pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
            pulseAnimator.setDuration(650);
            pulseAnimator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
            pulseAnimator.addUpdateListener(animation -> {
                pulse = (float) Math.sin((float) animation.getAnimatedValue() * Math.PI);
                invalidate();
            });
            pulseAnimator.start();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int save = canvas.save();
            canvas.scale(getWidth() / 108f, getHeight() / 132f);
            boolean tended = day.equals(garden.lastTendedDate()) && garden.lastTendedPlot() == index;
            ink.setShader(null);
            ink.setColor(accent);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(isFocused() ? 1.5f : 0.8f);
            ink.setAlpha(isFocused() ? 255 : tended ? 100 : 0);
            canvas.drawRoundRect(4, 5, 104, 128, 20, 20, ink);
            ink.setAlpha(255);
            ink.setStyle(Paint.Style.FILL);
            ink.setColor(soil);
            ink.setAlpha(20);
            canvas.drawOval(29, 107, 79, 116, ink);
            ink.setAlpha(255);
            ink.setShader(potFill);
            canvas.drawPath(pot, ink);
            ink.setShader(null);
            ink.setColor(rim);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(0.8f);
            canvas.drawPath(pot, ink);
            ink.setStyle(Paint.Style.FILL);
            ink.setColor(glass);
            canvas.drawRoundRect(23, 77, 85, 85, 4, 4, ink);
            ink.setColor(soil);
            canvas.drawOval(28, 78, 80, 83, ink);
            PocketGarden.Plant plant = garden.get(index);
            if (plant == null) {
                ink.setColor(secondary);
                ink.setAlpha(garden.canTend(day) ? 150 : 65);
                ink.setStrokeWidth(1.2f);
                canvas.drawLine(51, 78, 57, 78, ink);
                canvas.drawLine(54, 75, 54, 81, ink);
                ink.setAlpha(255);
            } else if (plant.water == 0) {
                ink.setColor(plant.golden ? 0xFFD8B66E : 0xFFAF98D4);
                canvas.drawOval(50, 73 - pulse * 2, 58, 79 - pulse * 2, ink);
            } else {
                int growthSave = canvas.save();
                canvas.rotate(pulse * -3, 54, 81);
                float bottom = plant.water == 1 ? 73 : plant.water == 2 ? 64 : 58;
                ink.setColor(stem);
                ink.setStrokeWidth(1.6f);
                ink.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawLine(54, bottom - 1, 54, 80, ink);
                if (plant.water > 1) {
                    ink.setStyle(Paint.Style.STROKE);
                    canvas.drawArc(45, 61, 55, 76, 0, 80, false, ink);
                    ink.setStyle(Paint.Style.FILL);
                }
                float flowerWidth = plant.water == 1 ? 35 : plant.water == 2 ? 44 : 66;
                float flowerHeight = plant.water == 1 ? 24 : plant.water == 2 ? 34 : 50;
                blooms.draw(canvas, plant.variety, plant.golden, 54, bottom,
                        flowerWidth * (1 + pulse * 0.03f), flowerHeight, plant.water == 1);
                canvas.restoreToCount(growthSave);
            }
            ink.setShader(null);
            ink.setColor(secondary);
            ink.setTextSize(8);
            canvas.drawText(number, 54, 101, ink);
            int stage = plant == null ? -1 : plant.water;
            for (int i = 0; i <= PocketGarden.BLOOM; i++) {
                ink.setColor(i <= stage ? accent : rim);
                ink.setAlpha(i <= stage ? 185 : 130);
                canvas.drawRoundRect(37 + i * 9, 119, 43 + i * 9, 121, 1, 1, ink);
            }
            ink.setAlpha(255);
            canvas.restoreToCount(save);
        }
    }
}
