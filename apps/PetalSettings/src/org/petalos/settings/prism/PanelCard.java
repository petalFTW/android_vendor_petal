/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.settings.prism;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Locale;

// Plain category card normally; the hidden modes keep their own toys.
final class PanelCard extends FrameLayout {

    private final Panels.Panel panel;
    private final ImageView icon;
    private final TextView title;
    private final TextView sub;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shell = new Path();
    private final Path labelClip = new Path();
    private final RectF rect = new RectF();
    private final RectF labelRect = new RectF();
    private final Typeface heavy;
    private final Typeface mono = Typeface.create("monospace", Typeface.NORMAL);
    private final Drawable petal;
    private boolean cartridge;
    private boolean nxe;
    private final Drawable dashboardIcon;
    private Shader nxeShader;

    PanelCard(Context context, Panels.Panel panel) {
        super(context);
        this.panel = panel;
        heavy = getResources().getFont(R.font.petal_rubik);
        setWillNotDraw(false);
        setClickable(true);
        setFocusable(true);
        setSoundEffectsEnabled(true);
        setHapticFeedbackEnabled(true);
        setClipToPadding(false);
        setClipChildren(false);

        int accent = context.getColor(R.color.ink);

        icon = new ImageView(context);
        icon.setImageResource(panel.icon);
        icon.setImageTintList(ColorStateList.valueOf(accent));
        icon.setPadding(dp(12), dp(12), dp(12), dp(12));
        icon.setBackground(iconChip());
        addView(icon, new LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.START));

        title = new TextView(context);
        title.setText(panel.title);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.settings_title_size));
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setLetterSpacing(-0.01f);
        title.setTextColor(context.getColor(R.color.ink));
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        addView(title, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START));

        sub = new TextView(context);
        sub.setText(panel.sub);
        sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.settings_summary_size));
        sub.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        sub.setTextColor(context.getColor(R.color.muted));
        sub.setLineSpacing(dp(1), 1f);
        sub.setMaxLines(2);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        addView(sub, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START));

        setPadding(dp(22), dp(22), dp(22), dp(22));
        setContentDescription(panel.title + ", " + panel.sub);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        title.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        sub.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(18));
        setForeground(new RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), null, mask));
        petal = context.getDrawable(R.drawable.ic_petal);
        dashboardIcon = context.getDrawable(panel.icon).mutate();
    }

    Panels.Panel panel() {
        return panel;
    }

    void setCartridge(boolean cartridge) {
        this.cartridge = cartridge;
        updateContentVisibility();
        updateRipple();
        invalidate();
    }

    void setNxe(boolean enabled) {
        nxe = enabled;
        updateContentVisibility();
        updateRipple();
        invalidate();
    }

    private void updateRipple() {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(nxe ? 3 : cartridge ? 27 : 18));
        setForeground(new RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), null, mask));
    }

    private void updateContentVisibility() {
        icon.setVisibility(cartridge || nxe ? GONE : VISIBLE);
        title.setVisibility(cartridge || nxe ? INVISIBLE : VISIBLE);
        sub.setVisibility(cartridge || nxe ? INVISIBLE : VISIBLE);
    }

    boolean isCartridge() {
        return cartridge;
    }

    void setCarouselFocus(float focus) {
        float eased = focus * focus * (3f - 2f * focus);
        // Keep side titles readable; the summary belongs to the selected card.
        sub.setAlpha(eased);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        nxeShader = new LinearGradient(0, 0, 0, h,
                new int[] {0xFFD9FF39, 0xFFA4D914, 0xFF183B08},
                new float[] {0f, 0.48f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int pad = getPaddingLeft();
        icon.layout(pad, pad, pad + icon.getMeasuredWidth(), pad + icon.getMeasuredHeight());

        int subTop = getHeight() - getPaddingBottom() - sub.getMeasuredHeight() - dp(2);
        title.layout(pad, subTop - title.getMeasuredHeight() - dp(7),
                pad + title.getMeasuredWidth(), subTop - dp(7));
        sub.layout(pad, subTop, pad + sub.getMeasuredWidth(), subTop + sub.getMeasuredHeight());
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (nxe) {
            drawDashboardTile(canvas);
        } else if (!cartridge) {
            drawTile(canvas);
            super.dispatchDraw(canvas);
        } else {
            drawCartridge(canvas);
        }
    }

    void drawDashboardTile(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        rect.set(dp(1), dp(1), w - dp(1), h - dp(1));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setShader(nxeShader);
        canvas.drawRoundRect(rect, dp(3), dp(3), paint);
        paint.setShader(null);
        // The fine ring texture and oversized category emblem echo the NXE tiles.
        canvas.save();
        canvas.clipRect(rect);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.7f));
        paint.setColor(0x18FFFFFF);
        for (int i = 0; i < 18; i++) {
            float x = w * ((i * 37 % 101) / 100f);
            float y = h * ((i * 23 % 97) / 100f);
            canvas.drawCircle(x, y, dp(4 + i % 5 * 3), paint);
        }
        canvas.restore();
        int size = Math.round(h * 0.48f);
        int x = Math.round((w - size) / 2f);
        int y = Math.round(h * 0.14f);
        dashboardIcon.setBounds(x + dp(2), y + dp(4), x + size + dp(2), y + size + dp(4));
        dashboardIcon.setTint(0x550C2600);
        dashboardIcon.draw(canvas);
        dashboardIcon.setBounds(x, y, x + size, y + size);
        dashboardIcon.setTint(0xFFF4FFD8);
        dashboardIcon.draw(canvas);
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(heavy);
        paint.setTextSize(dp(18));
        paint.setColor(Color.WHITE);
        String label = android.text.TextUtils.ellipsize(panel.title,
                new android.text.TextPaint(paint), w - dp(28),
                android.text.TextUtils.TruncateAt.END).toString();
        canvas.drawText(label, dp(14), h - dp(20), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.7f));
        paint.setColor(0x88EDFF9B);
        canvas.drawRoundRect(rect, dp(3), dp(3), paint);
    }

    private void drawTile(Canvas canvas) {
        float r = dp(18);
        rect.set(dp(0.5f), dp(0.5f), getWidth() - dp(0.5f), getHeight() - dp(0.5f));

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(getContext().getColor(R.color.card));
        canvas.drawRoundRect(rect, r, r, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(getResources().getDisplayMetrics().density * 0.85f);
        paint.setColor(getContext().getColor(R.color.line));
        canvas.drawRoundRect(rect, r, r, paint);
    }

    private void drawCartridge(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float r = dp(14);
        float tabW = Math.max(dp(12), w * 0.055f);
        float tabH = h * 0.135f;
        float bodyW = w - tabW;
        buildShell(w, h, r, tabW, tabH);

        // Soft cast shadow beneath the molded shell.
        canvas.save();
        canvas.translate(0, dp(5));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x3A000000);
        canvas.drawPath(shell, paint);
        canvas.restore();
        paint.setAlpha(255);

        // Light plastic shell with a cool bevel.
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, w, h,
                new int[] {0xFFFDFCFF, 0xFFE8E3ED, 0xFFD4CEDA},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(shell, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, dp(0.9f)));
        paint.setColor(0x55000000);
        canvas.drawPath(shell, paint);

        // The top-right insertion tab is the 3DS card's signature. The darker
        // inner edge keeps the step readable when another cartridge sits behind.
        paint.setStrokeWidth(Math.max(1f, dp(0.8f)));
        paint.setColor(0x33000000);
        canvas.drawLine(bodyW, tabH + dp(3), bodyW, h - r, paint);
        canvas.drawLine(bodyW + dp(3), tabH, w - dp(8), tabH, paint);
        paint.setColor(0x55FFFFFF);
        paint.setStrokeWidth(Math.max(1f, dp(0.7f)));
        canvas.drawLine(r, dp(0.7f), w - r, dp(0.7f), paint);

        // Small moulded grip slots along the bottom edge.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x22000000);
        canvas.drawRect(dp(10), h - dp(7), dp(12), h - dp(2), paint);
        canvas.drawRect(bodyW - dp(12), h - dp(7), bodyW - dp(10), h - dp(2), paint);

        // Recessed paper label with the same generous plastic border as the
        // real card: about 8% on the sides and 13-15% on top and bottom.
        float side = Math.max(dp(18), bodyW * 0.085f);
        float labelTop = tabH + dp(4);
        float labelBottom = h - h * 0.12f;
        labelRect.set(side, labelTop, bodyW - side, labelBottom);
        float labelRadius = dp(5);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, labelRect.top, 0, labelRect.bottom,
                new int[] {0xFFFFFCF4, 0xFFF3EBDB, 0xFFE9DFCC}, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(labelRect, labelRadius, labelRadius, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, dp(0.7f)));
        paint.setColor(0x332C2418);
        canvas.drawRoundRect(labelRect, labelRadius, labelRadius, paint);

        float bandH = Math.max(dp(23), labelRect.height() * 0.14f);
        float stripH = Math.max(dp(21), labelRect.height() * 0.12f);
        float bandBottom = labelRect.top + bandH;
        float codeTop = labelRect.bottom - stripH;
        labelClip.reset();
        labelClip.addRoundRect(labelRect, labelRadius, labelRadius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(labelClip);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xE6FFFFFF);
        canvas.drawRect(labelRect.left, labelRect.top, labelRect.right, bandBottom, paint);
        canvas.drawRect(labelRect.left, codeTop, labelRect.right, labelRect.bottom, paint);
        canvas.restore();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x1F2C2418);
        canvas.drawRect(labelRect.left + dp(1), bandBottom - Math.max(1f, dp(0.7f)),
                labelRect.right - dp(1), bandBottom, paint);
        canvas.drawRect(labelRect.left + dp(1), codeTop,
                labelRect.right - dp(1), codeTop + Math.max(1f, dp(0.7f)), paint);

        // Brand band, in the spirit of the 3DS card's top strip.
        if (petal != null) {
            int mark = (int) dp(11);
            int markLeft = (int) (labelRect.left + dp(8));
            int markTop = (int) (labelRect.top + dp(6));
            petal.setBounds(markLeft, markTop, markLeft + mark, markTop + mark);
            petal.setAlpha(220);
            petal.draw(canvas);
            petal.setAlpha(255);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(heavy);
        paint.setTextSize(dp(10));
        paint.setColor(0xFF5A427A);
        canvas.drawText("petalOS", labelRect.left + dp(23), labelRect.top + dp(15), paint);

        paint.setTypeface(mono);
        paint.setTextSize(dp(5.2f));
        paint.setLetterSpacing(0.0f);
        paint.setColor(0xAA746886);
        String kind = "SETTINGS CARD";
        canvas.drawText(kind, labelRect.right - dp(8) - paint.measureText(kind),
                labelRect.top + dp(14), paint);

        // The category colour is only a thin rule here, not a painted shell.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(panel.cartColor, 0xCC));
        canvas.drawRect(labelRect.left + dp(8), bandBottom + dp(3),
                labelRect.left + dp(44), bandBottom + dp(4.6f), paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(heavy);
        paint.setTextSize(dp(17));
        paint.setColor(getContext().getColor(R.color.label_ink));
        canvas.drawText(panel.title, labelRect.left + dp(9), bandBottom + dp(28), paint);

        // Big, quiet petal watermark behind the label content.
        if (petal != null) {
            int size = (int) Math.min(dp(64), labelRect.width() * 0.38f);
            int cx = (int) ((labelRect.left + labelRect.right) / 2f);
            int cy = (int) (labelRect.top + (labelRect.bottom - labelRect.top) * 0.64f);
            petal.setBounds(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
            petal.setAlpha(34);
            petal.draw(canvas);
            petal.setAlpha(255);
        }

        float codeBaseline = labelRect.bottom - dp(8);
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(mono);
        paint.setTextSize(dp(5.5f));
        paint.setColor(getContext().getColor(R.color.label_dim));
        canvas.drawText("PBL-PDT-" + String.format(Locale.US, "%03d",
                Panels.ALL.indexOf(panel) + 1), labelRect.left + dp(9), codeBaseline, paint);

        drawSeal(canvas, labelRect.right - dp(25), codeTop - dp(16), dp(11));

        // A tiny code strip, like the manufacturing marks on a real card.
        paint.setColor(0x664A4237);
        for (int i = 0; i < 6; i++) {
            float x = labelRect.right - dp(69) + i * dp(3.2f);
            float bar = dp(3) + (i * 7 % 9) * dp(0.7f);
            canvas.drawRect(x, codeBaseline - bar, x + dp(1.4f), codeBaseline, paint);
        }
    }

    private void drawSeal(Canvas canvas, float cx, float cy, float radius) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, radius,
                new int[] {0xFFFFF3CE, 0xFFE5C78E, 0xFFC39C57},
                new float[] {0f, 0.68f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, dp(0.7f)));
        paint.setColor(0xFF9E7A3E);
        canvas.drawCircle(cx, cy, radius, paint);
        if (petal != null) {
            int size = (int) (radius * 1.25f);
            petal.setBounds((int) cx - size / 2, (int) cy - size / 2,
                    (int) cx + size / 2, (int) cy + size / 2);
            petal.setAlpha(190);
            petal.draw(canvas);
            petal.setAlpha(255);
        }
    }

    private void buildShell(float w, float h, float r, float tabW, float tabH) {
        float bodyW = w - tabW;
        float tabR = dp(4);
        shell.reset();
        shell.moveTo(r, 0);
        shell.lineTo(w - r, 0);
        shell.quadTo(w, 0, w, r);
        shell.lineTo(w, tabH - tabR);
        shell.quadTo(w, tabH, w - tabR, tabH);
        shell.lineTo(bodyW + tabR, tabH);
        shell.quadTo(bodyW, tabH, bodyW, tabH + tabR);
        shell.lineTo(bodyW, h - r);
        shell.quadTo(bodyW, h, bodyW - r, h);
        shell.lineTo(r, h);
        shell.quadTo(0, h, 0, h - r);
        shell.lineTo(0, r);
        shell.quadTo(0, 0, r, 0);
        shell.close();
    }

    private GradientDrawable iconChip() {
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(0x0FFFFFFF);
        chip.setCornerRadius(dp(12));
        return chip;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
