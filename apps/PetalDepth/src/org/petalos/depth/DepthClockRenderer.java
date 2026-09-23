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

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;

// must match SystemUI's clock metrics exactly
public final class DepthClockRenderer {

    public static final int STYLE_OFF = 0;
    public static final int STYLE_SOLID = 1;
    public static final int STYLE_GLASS = 2;
    public static final int STYLE_FADE = 3;
    public static final int STYLE_MIX = 4;
    public static final int STYLE_POSTER = 5;
    public static final int STYLE_NEON = 6;
    public static final int STYLE_GOLD = 7;
    public static final int STYLE_VOGUE = 8;
    public static final int STYLE_MONO = 9;

    private final org.petalos.config.DepthClockMaterials mMaterials =
            new org.petalos.config.DepthClockMaterials();
    private android.graphics.Bitmap mBackdrop;
    private android.graphics.Bitmap mLiquidBackdrop;
    private int mWidth, mHeight;

    public void setBackdrop(android.graphics.Bitmap bitmap) { mBackdrop = bitmap; }
    public void setLiquidBackdrop(android.graphics.Bitmap bitmap) { mLiquidBackdrop = bitmap; }

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDepth = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDate = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mProbe = new Paint(Paint.ANTI_ALIAS_FLAG);

    public DepthClockRenderer() {
        mStroke.setStyle(Paint.Style.STROKE);
        mGlow.setStyle(Paint.Style.STROKE);
    }

    // same layout math as the lockscreen
    public void draw(Canvas canvas, int viewW, int viewH, int style, boolean vertical,
            float anchorXFrac, float anchorFrac, float scale, int ink, boolean showDate,
            String hourDisp, String minute, String date) {
        if (viewW <= 0 || viewH <= 0 || style == STYLE_OFF) {
            return;
        }
        mWidth = viewW;
        mHeight = viewH;
        applyTypeface(style);
        mFill.setColor(ink);
        mStroke.setColor(ink);
        mDate.setColor(Color.argb(210, Color.red(ink), Color.green(ink), Color.blue(ink)));

        mProbe.setTypeface(mFill.getTypeface());
        mProbe.setTextSize(100f);
        boolean square = org.petalos.config.DepthClockMaterials.isSquare(style);
        if (square) {
            // squares are always stacked
            vertical = true;
        }
        float unitWidth = mProbe.measureText(vertical ? "00" : "00:00");
        float targetWidth = viewW
                * (square ? 0.92f : (vertical ? 0.62f : 0.78f)) * scale;
        float textSize = 100f * targetWidth / Math.max(1f, unitWidth);
        float heightCap = viewH * 0.5f * scale / (square ? 2.1f : (vertical ? 2.3f : 1.4f));
        float ts = Math.min(textSize, heightCap);

        mFill.setTextSize(ts);
        mStroke.setTextSize(ts);
        mStroke.setStrokeWidth(ts * 0.035f);
        mDate.setTextSize(ts * 0.12f);

        float digitSpacing = ts * 0.12f;

        android.graphics.RectF hh = textBounds(hourDisp, style);
        android.graphics.RectF mm = textBounds(minute, style);
        float centerX = viewW * anchorXFrac;
        boolean verticalStack = vertical || square;
        if (verticalStack) {
            float lineStep = ts * 1.25f;
            float baseline = viewH * anchorFrac - (hh.top + lineStep + mm.bottom) / 2f;
            drawStyled(canvas, hourDisp, centerX - hh.centerX(), baseline, style);
            drawStyled(canvas, minute, centerX - mm.centerX(), baseline + lineStep, style);
            if (showDate) {
                canvas.drawText(date, centerX - mDate.measureText(date) / 2f,
                        baseline + hh.top - mDate.getTextSize() * 0.5f
                                - mDate.getFontMetrics().bottom, mDate);
            }
        } else {
            float minuteX = mFill.measureText(hourDisp) + digitSpacing;
            float left = Math.min(hh.left, minuteX + mm.left);
            float right = Math.max(hh.right, minuteX + mm.right);
            float startX = centerX - (left + right) / 2f;
            float baseline = viewH * anchorFrac
                    - (Math.min(hh.top, mm.top) + Math.max(hh.bottom, mm.bottom)) / 2f;
            drawStyled(canvas, hourDisp, startX, baseline, style);
            drawStyled(canvas, minute, startX + minuteX, baseline, style);
            if (showDate) {
                canvas.drawText(date, centerX - mDate.measureText(date) / 2f,
                        baseline + mDate.getTextSize() * 1.9f, mDate);
            }
        }
    }

    private android.graphics.RectF textBounds(String text, int style) {
        android.graphics.Rect bounds = new android.graphics.Rect();
        mFill.getTextBounds(text, 0, text.length(), bounds);
        android.graphics.RectF result = new android.graphics.RectF(bounds);
        if (style == STYLE_VOGUE) {
            float extra = mFill.getTextSize() * 0.18f * Math.max(0, text.length() - 1);
            result.left -= extra / 2f;
            result.right += extra / 2f;
        }
        return result;
    }

    private void drawStyled(Canvas canvas, String text, float x, float y, int style) {
        if (mMaterials.draw(canvas, text, x, y, style, mFill,
                style == org.petalos.config.DepthClockMaterials.LIQUID
                        ? mLiquidBackdrop : mBackdrop, mWidth, mHeight)) return;
        switch (style) {
            case STYLE_GLASS:
                softShadow(canvas, text, x, y);
                canvas.drawText(text, x, y, mStroke);
                break;
            case STYLE_FADE: {
                mFill.setShader(new LinearGradient(
                        0f, y - mFill.getTextSize(), 0f, y,
                        Color.argb(255, Color.red(mFill.getColor()),
                                Color.green(mFill.getColor()), Color.blue(mFill.getColor())),
                        Color.argb(90, Color.red(mFill.getColor()),
                                Color.green(mFill.getColor()), Color.blue(mFill.getColor())),
                        Shader.TileMode.CLAMP));
                canvas.drawText(text, x, y, mFill);
                mFill.setShader(null);
                break;
            }
            default:
                drawStyled2(canvas, text, x, y, style);
                break;
        }
    }

    /** part 2 of the ugly switch */
    private void drawStyled2(Canvas canvas, String text, float x, float y, int style) {
        switch (style) {
            case STYLE_MIX: {
                float cx = x;
                boolean solid = true;
                for (int i = 0; i < text.length(); i++) {
                    String s = String.valueOf(text.charAt(i));
                    float cw = mFill.measureText(s);
                    if (text.charAt(i) == ' ') {
                        cx += cw;
                        continue;
                    }
                    canvas.drawText(s, cx, y, solid ? mFill : mStroke);
                    cx += cw;
                    solid = !solid;
                }
                break;
            }
            default:
                drawStyled3(canvas, text, x, y, style);
                break;
        }
    }

    /** still more style cases */
    private void drawStyled3(Canvas canvas, String text, float x, float y, int style) {
        switch (style) {
            case STYLE_POSTER: {
                mFill.setShader(new LinearGradient(
                        0f, y - mFill.getTextSize(), 0f, y + mFill.getTextSize() * 0.1f,
                        new int[] {mFill.getColor(), mFill.getColor(),
                                shade(mFill.getColor(), 0.45f)},
                        new float[] {0f, 0.58f, 0.58f},
                        Shader.TileMode.CLAMP));
                softShadow(canvas, text, x, y);
                canvas.drawText(text, x, y, mFill);
                mFill.setShader(null);
                break;
            }
            case STYLE_NEON: {
                mGlow.setTypeface(mFill.getTypeface());
                mGlow.setTextSize(mFill.getTextSize());
                mGlow.setColor(mFill.getColor());
                mGlow.setStrokeWidth(mFill.getTextSize() * 0.02f);
                mGlow.setShadowLayer(mFill.getTextSize() * 0.28f, 0f, 0f,
                        withAlpha(mFill.getColor(), 180));
                canvas.drawText(text, x, y, mGlow);
                canvas.drawText(text, x, y, mGlow);
                mGlow.setShadowLayer(mFill.getTextSize() * 0.08f, 0f, 0f, Color.WHITE);
                canvas.drawText(text, x, y, mGlow);
                mGlow.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
                break;
            }
            default:
                drawStyled4(canvas, text, x, y, style);
                break;
        }
    }

    /** end of the style switch */
    private void drawStyled4(Canvas canvas, String text, float x, float y, int style) {
        switch (style) {
            case STYLE_GOLD: {
                mGlow.setTypeface(mStroke.getTypeface());
                mGlow.setTextSize(mStroke.getTextSize());
                mGlow.setColor(mStroke.getColor());
                mGlow.setStrokeWidth(mStroke.getStrokeWidth());
                mGlow.setShadowLayer(mStroke.getTextSize() * 0.14f, 0f, 0f,
                        withAlpha(mStroke.getColor(), 150));
                canvas.drawText(text, x, y, mGlow);
                mGlow.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
                canvas.drawText(text, x, y, mStroke);
                break;
            }
            case STYLE_VOGUE: {
                float spacing = mFill.getTextSize() * 0.18f;
                float plain = mFill.measureText(text);
                float total = text.length() > 1 ? plain + spacing * (text.length() - 1) : plain;
                float cx = x + (plain - total) / 2f;
                int saved = mFill.getColor();
                mFill.setColor(Color.argb(70, 0, 0, 0));
                float sx = cx;
                for (int i = 0; i < text.length(); i++) {
                    String s = String.valueOf(text.charAt(i));
                    canvas.drawText(s, sx + mFill.getTextSize() * 0.02f,
                            y + mFill.getTextSize() * 0.03f, mFill);
                    sx += mFill.measureText(s) + spacing;
                }
                mFill.setColor(saved);
                for (int i = 0; i < text.length(); i++) {
                    String s = String.valueOf(text.charAt(i));
                    canvas.drawText(s, cx, y, mFill);
                    cx += mFill.measureText(s) + spacing;
                }
                break;
            }
            case STYLE_MONO: {
                float depth = mFill.getTextSize() * 0.09f;
                int steps = 14;
                mDepth.setTypeface(mFill.getTypeface());
                mDepth.setTextSize(mFill.getTextSize());
                mDepth.setColor(shade(mFill.getColor(), 0.22f));
                for (int i = steps; i >= 1; i--) {
                    float t = i / (float) steps;
                    canvas.drawText(text, x + depth * t, y + depth * t, mDepth);
                }
                canvas.drawText(text, x, y, mFill);
                break;
            }
            default:
                softShadow(canvas, text, x, y);
                canvas.drawText(text, x, y, mFill);
                break;
        }
    }

    private void softShadow(Canvas canvas, String text, float x, float y) {
        int saved = mFill.getColor();
        mFill.setColor(Color.argb(70, 0, 0, 0));
        canvas.drawText(text, x + mFill.getTextSize() * 0.02f, y + mFill.getTextSize() * 0.03f,
                mFill);
        mFill.setColor(saved);
    }

    private void applyTypeface(int style) {
        Typeface face;
        switch (style) {
            case STYLE_POSTER:
            case STYLE_MONO:
                face = org.petalos.config.DepthClockMaterials.weighted(900, false);
                break;
            case STYLE_VOGUE:
                face = org.petalos.config.DepthClockMaterials.weighted(400, true);
                break;
            case STYLE_GOLD:
                face = org.petalos.config.DepthClockMaterials.weighted(700, true);
                break;
            case STYLE_NEON:
                face = org.petalos.config.DepthClockMaterials.weighted(300, false);
                break;
            default:
                face = org.petalos.config.DepthClockMaterials.weighted(700, false);
                break;
        }
        if (style >= org.petalos.config.DepthClockMaterials.HEAVY) {
            face = org.petalos.config.DepthClockMaterials.typeface(style);
        }
        mFill.setTypeface(face);
        mStroke.setTypeface(face);
        mDate.setTypeface(org.petalos.config.DepthClockMaterials.weighted(500, false));
    }

    private static int shade(int color, float factor) {
        return Color.argb(Color.alpha(color),
                (int) (Color.red(color) * factor) & 0xFF,
                (int) (Color.green(color) * factor) & 0xFF,
                (int) (Color.blue(color) * factor) & 0xFF);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
