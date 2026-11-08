package org.petal.catback.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

// petalOS volume hud on the car: SystemUI's PetalVolumeOverlayView + PetalLiquid + PetalUtils.jointPath, same maths,
// layout units instead of dp. a dark blob that oozes out of the screen's right edge and stays joined to it by
// meniscus fillets, bounces open (reach leads, height lags), splats back into the bezel on dismiss
final class VolumeBlob {
    // rom is 34/160/14/34 dp x1.35; scaled to the car canvas (384-480 tall) keeping the same proportions
    static final float CW = 44, CH = 184, RAD = CW * 14f / 34f, JOINT = CW;
    private static final int BODY = 0xFF0C0C0E, FILL_WHITE = 0xFFFFFFFF, FILL_MUTED = 0xFF8E8E93, FILL_MAX = 0xFFFF453A,
            GLYPH_DARK = 0xFF161618, GLYPH_LIGHT = 0xEBFFFFFF;
    private static final long SHAKE_MS = 440, EXIT_MS = 500;

    // PetalSpring
    private static final class Spring {
        float value, target, velocity;
        void snap(float v) { value = target = v; velocity = 0; }
        void step(float dt, float k, float zeta) {
            int steps = Math.max(1, (int) Math.ceil(dt / .004f));
            float h = dt / steps, c = 2 * zeta * (float) Math.sqrt(k);
            for (int i = 0; i < steps; i++) { velocity += (-k * (value - target) - c * velocity) * h; value += velocity * h; }
        }
        boolean resting() { return Math.abs(velocity) < .0015f && Math.abs(value - target) < .0015f; }
    }
    private final Spring open = new Spring(), contour = new Spring(), fill = new Spring();
    private boolean showing, hasShown, draining;
    private long exitAt = -1, shakeAt = -100000;
    private float exitFrom = 1, exitT;
    // PetalLiquid outputs
    private float reach, spread, bulge, joint, opacity;

    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG), fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG),
            glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path(), speaker = new Path(), waves = new Path(), mute = new Path();
    // where the capsule sits at rest, for touches
    final RectF bounds = new RectF();
    float trackTop, trackBottom;

    VolumeBlob() {
        speaker.moveTo(4, 9.5f); speaker.lineTo(4, 14.5f); speaker.lineTo(7.2f, 14.5f); speaker.lineTo(12, 18.5f);
        speaker.lineTo(12, 5.5f); speaker.lineTo(7.2f, 9.5f); speaker.close();
        // "M15.5 9.2a4 4 0 0 1 0 5.6M17.9 6.8a7.4 7.4 0 0 1 0 10.4"
        // r4 arc through (15.5,9.2)-(15.5,14.8) has its centre at (12.643,12); r7.4 through (17.9,6.8)-(17.9,17.2) at (12.635,12)
        waves.addArc(new RectF(8.643f, 8, 16.643f, 16), -44.4f, 88.8f);
        waves.addArc(new RectF(5.235f, 4.6f, 20.035f, 19.4f), -44.65f, 89.3f);
        mute.moveTo(16, 9); mute.lineTo(21, 15); mute.moveTo(21, 9); mute.lineTo(16, 15);
    }

    void show() {
        showing = true;
        if (draining) {
            // came back mid splat, pick the goo up from where it is
            open.snap(reach); contour.snap(Math.min(spread, 1));
            exitAt = -1;
        }
        draining = false;
        open.target = 1; contour.target = 1;
    }
    void dismiss(long nowMs) {
        if (!showing && draining) return;
        showing = false;
        if (!draining) { exitAt = nowMs; exitFrom = clamp(open.value, 0, 1.15f); }
        draining = true;
        open.target = 0; contour.target = 0;
    }
    void shake(long nowMs) { shakeAt = nowMs; }
    void level(float level, boolean muted) {
        fill.target = muted ? 0 : clamp(level, 0, 1);
        if (!hasShown) { hasShown = true; fill.snap(fill.target); }
    }
    boolean showing() { return showing; }
    // open enough to grab
    boolean touchable() { return showing && reach > .5f; }
    boolean visible() { return showing || (draining && exitT < 1) || open.value > .002f; }
    boolean animating(long nowMs) {
        return (draining && exitT < 1) || !open.resting() || !contour.resting() || !fill.resting() || nowMs - shakeAt < SHAKE_MS;
    }

    void step(float dt, long nowMs, boolean still) {
        dt = Math.min(dt, .05f);
        if (still) {
            open.snap(showing ? 1 : 0); contour.snap(showing ? 1 : 0); fill.snap(fill.target);
            if (!showing) { draining = false; exitT = 1; }
            return;
        }
        if (draining) {
            exitT = clamp((nowMs - exitAt) / (float) EXIT_MS, 0, 1);
            if (exitT >= 1) { open.snap(0); contour.snap(0); }
        } else {
            // opening is bouncy, reach leads and the height lags behind it
            open.step(dt, 420, .46f);
            contour.step(dt, 240, .65f);
        }
        fill.step(dt, 900, .88f);
    }

    // PetalVolumeOverlayView.onDraw, right edge
    void draw(Canvas c, float edgeX, float cy, boolean muted0, float level0, long nowMs, boolean still) {
        float w = CW, h = CH;
        float pad = Math.max(5, w * .11f), nub = w - 2 * pad;
        bounds.set(edgeX - w, cy - h / 2, edgeX, cy + h / 2);
        trackBottom = cy + h / 2 - pad; trackTop = trackBottom - (h - 2 * pad);
        if (!visible()) return;
        if (draining) solveExit(exitT, w, 1.28f); else solve(w, h);
        float o = reach, sx = o, sy = spread;
        float rad = Math.min(RAD, w / 2), jointDepth = JOINT * joint, xTail = (JOINT * .5f + 10) * joint;
        long shakeMs = nowMs - shakeAt;
        float shake = still || shakeMs < 0 || shakeMs >= SHAKE_MS ? 0
                : (float) (Math.sin(shakeMs / 1000.0 * 60.0) * 5.5 * (1.0 - shakeMs / (double) SHAKE_MS));
        int saved = c.save();
        c.translate(0, shake);

        // body and joint, one path with shadow
        jointPath(cy, w * sx, h * sy, Math.min(rad, w * sx / 2), jointDepth, xTail, edgeX, bulge);
        body.setColor(BODY); body.setAlpha(Math.round(255 * opacity));
        body.setShadowLayer(24, 0, 10, 0x6B000000);
        c.drawPath(path, body);
        body.clearShadowLayer();

        // slide fill and glyph in, no squashing
        int clip = c.save();
        c.clipPath(path);
        c.translate(w * (1 - o), 0);
        float trackH = h - 2 * pad, level = clamp(fill.value, 0, 1), fillH = nub + level * (trackH - nub);
        boolean muted = muted0 || level0 <= 1e-6f, atMax = !muted && level0 >= 1 - 1e-6f;
        int fillColor = muted ? FILL_MUTED : atMax ? FILL_MAX : FILL_WHITE;
        int glowColor = muted ? 0x338E8E93 : atMax ? 0x55FF453A : 0x2EFFFFFF;
        float capsuleLeft = edgeX - w, bottom = cy + h / 2 - pad;
        fillPaint.setColor(fillColor); fillPaint.setAlpha(Math.round(255 * opacity));
        fillPaint.setShadowLayer(20, 0, 0, glowColor);
        c.drawRoundRect(capsuleLeft + pad, bottom - fillH, capsuleLeft + w - pad, bottom, nub / 2, nub / 2, fillPaint);
        fillPaint.clearShadowLayer();
        float iconW = w * .42f;
        boolean covered = fillH > pad + iconW * 1.35f;
        drawGlyph(c, capsuleLeft + w / 2, bottom - nub / 2, iconW, muted ? FILL_MAX : covered ? GLYPH_DARK : GLYPH_LIGHT, muted);
        c.restoreToCount(clip);
        c.restoreToCount(saved);
    }

    private void drawGlyph(Canvas c, float cx, float cy, float size, int color, boolean muted) {
        float k = size / 24;
        int saved = c.save();
        c.translate(cx - 12 * k, cy - 12 * k); c.scale(k, k);
        glyph.setColor(color); glyph.setAlpha(Math.round(255 * opacity));
        glyph.setStyle(Paint.Style.FILL);
        c.drawPath(speaker, glyph);
        glyph.setStyle(Paint.Style.STROKE); glyph.setStrokeWidth(1.9f);
        glyph.setStrokeCap(Paint.Cap.ROUND); glyph.setStrokeJoin(Paint.Join.ROUND);
        c.drawPath(muted ? mute : waves, glyph);
        c.restoreToCount(saved);
    }

    // PetalUtils.jointPath, right edge: runs past the edge (xTail), bows into the capsule's top and bottom
    // with quads (the meniscus), front edge can belly out or cave in
    private void jointPath(float cy, float w, float h, float r0, float d, float xTail, float e, float bulge) {
        float r = Math.max(0, Math.min(r0, Math.min(w * .7f, h / 2))), yT = cy - h / 2, yB = cy + h / 2;
        float l = e - w, lr = e - (w - r);
        path.rewind();
        path.moveTo(e + xTail, yT - d);
        path.lineTo(e, yT - d);
        path.quadTo(e, yT, lr, yT);
        path.quadTo(l, yT, l, yT + r);
        if (bulge != 0) path.quadTo(l - bulge, cy, l, yB - r); else path.lineTo(l, yB - r);
        path.quadTo(l, yB, lr, yB);
        path.quadTo(e, yB, e, yB + d);
        path.lineTo(e + xTail, yB + d);
        path.close();
    }

    // PetalLiquid.solve
    private void solve(float w, float h) {
        float r = clamp(open.value, 0, 1.15f), c = clamp(contour.value, 0, 1.1f);
        reach = r;
        // starts life as a round blob about as tall as it is wide
        float bead = h > 0 ? clamp(r * w * 1.25f / h, 0, 1) : 1;
        float s = bead + (1 - bead) * Math.min(c, 1) + Math.max(0, c - 1);
        // overshoot goes wide -> goes thin, undershoot fattens. thats the jelly
        s *= 1 - .72f * (r - 1) * smoothstep(.7f, 1, c);
        spread = Math.max(0, s);
        // speed bends the front, fast out = fat nose
        float cap = .4f * w * Math.max(r, .2f);
        bulge = clamp(open.velocity * .1f * w, -cap, cap);
        joint = c;
        opacity = clamp(r * 2.4f, 0, 1);
    }
    // PetalLiquid.solveExit: swell, slam into the bezel, smear along it, gone
    private void solveExit(float t, float w, float maxSpread) {
        float wind = (float) Math.sin(Math.PI * clamp(t / .12f, 0, 1)) * .09f;
        float a = clamp((t - .1f) / .3f, 0, 1), a3 = a * a * a;
        float sp = clamp((t - .38f) / .36f, 0, 1), ab = clamp((t - .72f) / .28f, 0, 1);
        float r = 1 + wind * (1 - a) - .86f * a3;
        r -= .06f * easeOut(sp);
        r *= (1 - ab) * (1 - ab) * exitFrom;
        reach = Math.max(0, r);
        float ripple = .08f * (float) Math.sin(sp * Math.PI * 3.0) * (1 - sp);
        spread = 1 + .1f * a3 + (maxSpread - 1.1f) * backOut(sp) + ripple + .06f * ab;
        joint = (1 + (.45f - 1) * sp) * (1 - ab);
        bulge = -.3f * w * reach * a * (1 - sp);
        opacity = 1 - smoothstep(.8f, 1, t);
    }
    private static float easeOut(float t) { float u = 1 - t; return 1 - u * u * u; }
    private static float backOut(float t) { float c = 1.9f, u = t - 1; return 1 + (c + 1) * u * u * u + c * u * u; }
    private static float smoothstep(float e0, float e1, float x) { float t = clamp((x - e0) / (e1 - e0), 0, 1); return t * t * (3 - 2 * t); }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
