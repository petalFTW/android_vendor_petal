// ported from petalOS SystemUI LiquidGlassDrawable.kt + RefractionShader.kt (Apache 2.0, petalOS 2026)
package org.petal.catback.ui;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.annotation.TargetApi;
import android.os.Build;

// island only: blur + rim refraction, slab, rim, sheen, neon comet. no grain, car panels are too coarse for it. blur-only on 31-32, flat tint below
final class IslandGlass {
    // tiny capsule barely bends, big cards cap at 18dp
    // thicker and harder than the phone (.20 / 18dp / 24dp): car glass reads from arm's length on a coarse panel
    private static final float BAND_FRACTION = 0.32f, BAND_MAX_DP = 30f, STRENGTH_DP = 40f, BLUR_BEHIND_DP = 24f;
    // rom island at its maximums: pop-out and chroma sliders at 100%, cards pull 1.2x (IslandView/RefractionShader)
    private static final float POPOUT = 1f, CHROMA = 1f, CHROMA_MAX_DP = 5f, EXPANDED_MULTIPLIER = 1.2f,
            CAPSULE_H = 38f, CARD_H = 150f; // Island's capsule/card heights
    private static final float NEON_CHAIN = 0.24f, NEON_FADE = 0.12f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            rim = new Paint(Paint.ANTI_ALIAS_FLAG), sheen = new Paint(Paint.ANTI_ALIAS_FLAG),
            neonCore = new Paint(Paint.ANTI_ALIAS_FLAG), neonGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path(), rimPath = new Path(), pixelPath = new Path(), segment = new Path(), wrapped = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final Matrix sheenMatrix = new Matrix();
    private final RectF rimRect = new RectF(), clip = new RectF();
    private final Object slab, lens; // RuntimeShader on 33+
    // frost = blurred backdrop; lens = the sharp backdrop bent + rgb-split at the rim, see-through inside it
    private final RenderNode backdrop = new RenderNode("island-backdrop"), lensLayer = new RenderNode("island-lens");
    private LinearGradient sheenShader;
    private RenderEffect blurEffect; private float blurFor = Float.NaN;
    private float rimTop = Float.NaN, rimBottom = Float.NaN;
    // blurred+bent backdrop only rebuilt when the scene or island box moves
    private int cachedVersion = -1;
    private float cachedLeft = Float.NaN, cachedTop = Float.NaN, cachedW = Float.NaN, cachedH = Float.NaN, cachedRadius = Float.NaN, cachedSy = Float.NaN;
    // drawn part of the frame in pixels; the backdrop capture stops here instead of pulling in empty space
    private float areaLeft = -1e6f, areaTop = -1e6f, areaRight = 1e6f, areaBottom = 1e6f;
    private float sheenWidth = -1, lastSy = 2;

    IslandGlass() {
        rim.setStyle(Paint.Style.STROKE); rim.setStrokeWidth(1f);
        neonCore.setStyle(Paint.Style.STROKE); neonGlow.setStyle(Paint.Style.STROKE);
        neonGlow.setMaskFilter(new BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL));
        sheen.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        Object s = null, l = null;
        if (Build.VERSION.SDK_INT >= 33) {
            // separately, so a lens that won't compile on some gpu still leaves the slab (and vice versa)
            try { s = new RuntimeShader(SLAB_AGSL); } catch (RuntimeException unsupported) { s = null; }
            try { l = new RuntimeShader(LENS_AGSL); } catch (RuntimeException unsupported) { l = null; }
        }
        slab = s; lens = l;
    }

    static float band(float w, float h) { return Math.min(Math.min(w, h) * BAND_FRACTION, BAND_MAX_DP); }
    // like IslandView: more lens the further a capsule has grown toward a card
    private static float popout(float heightUnits) {
        float t = Math.max(0f, Math.min(1f, (heightUnits - CAPSULE_H) / (CARD_H - CAPSULE_H)));
        return POPOUT * (1f + t * (EXPANDED_MULTIPLIER - 1f));
    }

    void area(float left, float top, float right, float bottom) { areaLeft = left; areaTop = top; areaRight = right; areaBottom = bottom; }

    // canvas in layout units (sx/sy/ox/oy from pixels), scene = stuff behind in pixel space or null, rect in layout units
    void draw(Canvas canvas, RenderNode scene, int sceneVersion, float ox, float oy, float sx, float sy,
              RectF rect, float radius, float sheenProgress, float neonProgress, int neonColor) {
        draw(canvas, scene, sceneVersion, ox, oy, sx, sy, rect, radius, sheenProgress, neonProgress, neonColor, 0);
    }

    // 0 = island tint, else argb (panels go lighter)
    void draw(Canvas canvas, RenderNode scene, int sceneVersion, float ox, float oy, float sx, float sy,
              RectF rect, float radius, float sheenProgress, float neonProgress, int neonColor, int tintOverride) {
        if (rect.width() < 1 || rect.height() < 1) return;
        lastSy = sy;
        path.reset(); path.addRoundRect(rect, radius, radius, Path.Direction.CW);
        boolean hardware = canvas.isHardwareAccelerated() && scene != null;
        boolean blurred = hardware && Build.VERSION.SDK_INT >= 31;

        if (blurred) backdropLens(canvas, scene, sceneVersion, ox, oy, sx, sy, rect, radius);

        // same as IslandView.themeBackground()
        int tint = tintOverride != 0 ? tintOverride : blurred ? 0xA8101012 : 0xF2101012;
        if (slab != null && blurred && Build.VERSION.SDK_INT >= 33) { fill.setShader(slab(rect, radius, tint)); fill.setColor(Color.WHITE); }
        else { fill.setShader(null); fill.setColor(tint); }
        canvas.drawPath(path, fill);

        rimRect.set(rect); rimRect.inset(.5f, .5f);
        rimPath.reset(); rimPath.addRoundRect(rimRect, radius, radius, Path.Direction.CW);
        if (rect.top != rimTop || rect.bottom != rimBottom) {
            rimTop = rect.top; rimBottom = rect.bottom;
            rim.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, 0x2EFFFFFF, 0x0AFFFFFF, Shader.TileMode.CLAMP));
        }
        canvas.drawPath(rimPath, rim);

        if (neonProgress >= 0f && neonProgress <= 1f) neon(canvas, neonProgress, neonColor);
        if (sheenProgress >= 0f && sheenProgress <= 1f) sheen(canvas, rect, sheenProgress);
    }

    @TargetApi(33)
    private Shader slab(RectF rect, float radius, int tint) {
        RuntimeShader shader = (RuntimeShader) slab;
        shader.setFloatUniform("center", rect.centerX(), rect.centerY());
        shader.setFloatUniform("halfSize", rect.width() * .5f, rect.height() * .5f);
        shader.setFloatUniform("radius", Math.min(radius, Math.min(rect.width(), rect.height()) * .5f));
        shader.setFloatUniform("bandWidth", band(rect.width(), rect.height()));
        shader.setFloatUniform("gain", popout(rect.height()));
        shader.setFloatUniform("surface", luminance(tint));
        // the phone's tight glint is a 1-2px sparkle at car density that the encoder chews up, so it widens there
        shader.setFloatUniform("shininess", Math.max(8f, Math.min(18f, 18f * lastSy / 2f)));
        shader.setColorUniform("tint", tint);
        return shader;
    }

    // frost whatever's behind, then lay the rom's rim lens over it: sharp backdrop pulled out from the edge with
    // a red/blue split. the capture reaches past the island by the pull + split + blur spread so every sample
    // is a real pixel (stopping at the rim smeared the edge into streaks)
    @TargetApi(31)
    private void backdropLens(Canvas canvas, RenderNode scene, int sceneVersion, float ox, float oy, float sx, float sy, RectF rect, float radius) {
        float left = ox + rect.left * sx, top = oy + rect.top * sy, w = rect.width() * sx, h = rect.height() * sy;
        float reach = (STRENGTH_DP * POPOUT * EXPANDED_MULTIPLIER + CHROMA_MAX_DP * CHROMA + BLUR_BEHIND_DP) * sy;
        // capture box snapped to whole pixels so the layers land 1:1, no second resample
        int cl = (int) Math.floor(Math.max(areaLeft, left - reach)), ct = (int) Math.floor(Math.max(areaTop, top - reach));
        int cr = (int) Math.ceil(Math.min(areaRight, left + w + reach)), cb = (int) Math.ceil(Math.min(areaBottom, top + h + reach));
        int cw = Math.max(1, cr - cl), ch = Math.max(1, cb - ct);
        boolean lensOn = lens != null && Build.VERSION.SDK_INT >= 33;
        boolean same = sceneVersion == cachedVersion && left == cachedLeft && top == cachedTop && w == cachedW && h == cachedH
                && radius == cachedRadius && sy == cachedSy;
        if (!same) {
            capture(backdrop, scene, cl, ct, cw, ch);
            if (blurEffect == null || blurFor != sy) {
                float blur = BLUR_BEHIND_DP * sy * .5f; blurFor = sy;
                blurEffect = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP);
            }
            backdrop.setRenderEffect(blurEffect);
            if (lensOn) {
                capture(lensLayer, scene, cl, ct, cw, ch);
                lensLayer.setRenderEffect(lens(left - cl, top - ct, w, h, cw, ch, radius * sy, sy, popout(rect.height())));
            }
            cachedVersion = sceneVersion; cachedLeft = left; cachedTop = top; cachedW = w; cachedH = h; cachedRadius = radius; cachedSy = sy;
        }
        int saved = canvas.save();
        // 1 unit = 1 frame pixel from here, (rect.left, rect.top) sits on the island's top-left pixel
        canvas.scale(1f / sx, 1f / sy, rect.left, rect.top);
        clip.set(rect.left, rect.top, rect.left + w, rect.top + h);
        pixelPath.reset();
        pixelPath.addRoundRect(clip, radius * sy, radius * sy, Path.Direction.CW);
        canvas.clipPath(pixelPath);
        canvas.translate(rect.left + (cl - left), rect.top + (ct - top));
        canvas.drawRenderNode(backdrop);
        if (lensOn) canvas.drawRenderNode(lensLayer);
        canvas.restoreToCount(saved);
    }
    private static void capture(RenderNode node, RenderNode scene, int left, int top, int w, int h) {
        node.setPosition(0, 0, w, h);
        Canvas record = node.beginRecording(w, h);
        try { record.translate(-left, -top); record.drawRenderNode(scene); }
        finally { node.endRecording(); }
    }

    @TargetApi(33)
    private RenderEffect lens(float originX, float originY, float w, float h, int cw, int ch, float radiusPx, float sy, float popout) {
        RuntimeShader shader = (RuntimeShader) lens;
        float halfExtent = Math.min(w, h) * .5f;
        shader.setFloatUniform("origin", originX, originY);
        shader.setFloatUniform("halfSize", w * .5f, h * .5f);
        shader.setFloatUniform("limit", cw, ch);
        shader.setFloatUniform("radius", Math.min(radiusPx, halfExtent));
        // RenderEngine caps the pull at half the short side so a capsule doesn't fold inside out
        shader.setFloatUniform("strength", Math.min(STRENGTH_DP * sy * popout, halfExtent));
        shader.setFloatUniform("bandWidth", Math.max(.001f, Math.min(Math.min(w, h) * BAND_FRACTION, Math.min(BAND_MAX_DP * sy, halfExtent))));
        shader.setFloatUniform("chromatic", CHROMA_MAX_DP * sy * CHROMA);
        return RenderEffect.createRuntimeShaderEffect(shader, "originalInput");
    }

    private void neon(Canvas canvas, float progress, int color) {
        measure.setPath(rimPath, false);
        float perimeter = measure.getLength();
        if (perimeter <= 0) return;
        float chain = perimeter * NEON_CHAIN, head = progress * (perimeter + chain), tail = Math.max(0, head - chain);
        if (tail >= perimeter) return;
        segment.reset();
        float over = head - perimeter;
        if (over <= 0) measure.getSegment(tail, head, segment, true);
        else {
            measure.getSegment(tail, perimeter, segment, true);
            wrapped.reset(); measure.getSegment(0, over, wrapped, true); segment.addPath(wrapped);
        }
        float alpha = clamp(progress / NEON_FADE) * clamp((1 - progress) / NEON_FADE);
        neonGlow.setColor(withAlpha(color, (int) (alpha * 0x30))); neonGlow.setStrokeWidth(5f);
        canvas.drawPath(segment, neonGlow);
        neonCore.setColor(withAlpha(color, (int) (alpha * 0xFF))); neonCore.setStrokeWidth(1.6f);
        canvas.drawPath(segment, neonCore);
    }

    private void sheen(Canvas canvas, RectF rect, float progress) {
        float w = rect.width(), h = rect.height(), band = w * .4f, x = rect.left - band + progress * (w + band);
        float fade = clamp((1 - progress) / .25f);
        if (fade <= 0) return;
        if (sheenWidth != band) {
            sheenWidth = band;
            sheenShader = new LinearGradient(0, 0, band, 0, new int[]{0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
            sheen.setShader(sheenShader);
        }
        sheenMatrix.setTranslate(x, 0); sheenShader.setLocalMatrix(sheenMatrix);
        sheen.setAlpha((int) (255 * fade));
        int saved = canvas.save();
        canvas.clipPath(path);
        canvas.rotate(-22f, rect.centerX(), rect.centerY());
        canvas.drawRect(x, rect.top - h, x + band, rect.bottom + h, sheen);
        canvas.restoreToCount(saved);
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }
    private static int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24); }
    private static float luminance(int c) {
        return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
    }
    // SLAB_AGSL straight copy
    private static final String SLAB_AGSL =
            "uniform float2 center;\nuniform float2 halfSize;\nuniform float radius;\nuniform float bandWidth;\n"
            + "uniform float gain;\nuniform float surface;\nuniform float shininess;\nlayout(color) uniform half4 tint;\n"
            + "half4 main(float2 xy) {\n"
            + "    float2 p = xy - center;\n"
            + "    float2 inner = halfSize - max(radius, bandWidth);\n"
            + "    if (abs(p.x) <= inner.x && abs(p.y) <= inner.y) return half4(tint.rgb * tint.a, tint.a);\n"
            + "    float2 q = abs(p) - halfSize + radius;\n"
            + "    float2 outside = max(q, 0.0);\n"
            + "    float outsideLength = length(outside);\n"
            + "    float d = outsideLength + min(max(q.x, q.y), 0.0) - radius;\n"
            + "    float u = clamp(1.0 + d / bandWidth, 0.0, 1.0);\n"
            + "    float shoulder = smoothstep(0.0, 1.0, u);\n"
            + "    if (shoulder == 0.0) return half4(tint.rgb * tint.a, tint.a);\n"
            + "    float2 edgeNormal = outsideLength > 0.001 ? outside / outsideLength\n"
            + "            : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));\n"
            + "    edgeNormal *= sign(p);\n"
            + "    float3 n = float3(edgeNormal * u, sqrt(max(1.0 - u * u, 0.0)));\n"
            + "    float3 lightDir = normalize(float3(-0.45, -0.60, 0.80));\n"
            + "    float3 halfway = normalize(lightDir + float3(0.0, 0.0, 1.0));\n"
            + "    float reflection = pow(max(dot(n, halfway), 0.0), shininess);\n"
            + "    float facing = dot(edgeNormal, normalize(float2(-0.45, -0.60)));\n"
            + "    float fresnel = u * u * u * u;\n"
            + "    float darkMode = 1.0 - surface;\n"
            + "    float highlight = gain * shoulder * (mix(0.12, 0.20, darkMode) * reflection\n"
            + "            + mix(0.06, 0.10, darkMode) * fresnel * max(facing, 0.0));\n"
            + "    float shade = gain * shoulder * mix(0.045, 0.13, darkMode) * max(-facing, 0.0);\n"
            + "    float clearK = mix(0.18, 0.55, darkMode);\n"
            + "    half a = tint.a * half(1.0 - clearK * shoulder * min(gain, 1.0));\n"
            + "    half4 col = half4(tint.rgb * a, a);\n"
            + "    half darkAlpha = half(clamp(shade, 0.0, 0.22));\n"
            + "    col = half4(col.rgb * (1.0 - darkAlpha), darkAlpha + col.a * (1.0 - darkAlpha));\n"
            + "    half lightAlpha = half(clamp(highlight, 0.0, 0.32));\n"
            + "    return half4(half3(lightAlpha) + col.rgb * (1.0 - lightAlpha),\n"
            + "                 lightAlpha + col.a * (1.0 - lightAlpha));\n"
            + "}\n";

    // RenderEngine's island refraction (BlurFilter.cpp) over the sharp backdrop. it outputs the lens premultiplied
    // by the rim, so drawn over the frost layer it lands exactly on the rom's mix(frost, lens, rim). differences:
    // the island sits at origin inside a bigger capture (limit), and normals come off a box rounded by at least
    // the band so the pull turns smoothly instead of flipping along a hard diagonal from each corner
    private static final String LENS_AGSL =
            "uniform shader originalInput;\nuniform float2 origin;\nuniform float2 halfSize;\nuniform float2 limit;\n"
            + "uniform float radius;\nuniform float strength;\nuniform float bandWidth;\nuniform float chromatic;\n"
            + "half4 lensAt(float2 xy) { return originalInput.eval(clamp(xy, float2(0.5), limit - 0.5)); }\n"
            + "half4 main(float2 xy) {\n"
            + "    float2 p = xy - origin - halfSize;\n"
            + "    float2 inner = halfSize - max(radius, bandWidth);\n"
            + "    if (abs(p.x) <= inner.x && abs(p.y) <= inner.y) return half4(0.0);\n"
            + "    float2 q = abs(p) - halfSize + radius;\n"
            + "    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;\n"
            + "    float u = clamp(1.0 + d / bandWidth, 0.0, 1.0);\n"
            + "    float rim = smoothstep(0.0, 1.0, u);\n"
            + "    if (rim <= 0.001) return half4(0.0);\n"
            + "    float soft = max(radius, bandWidth);\n"
            + "    float2 outside = max(abs(p) - halfSize + soft, 0.0);\n"
            + "    float outsideLength = length(outside);\n"
            + "    float2 normal = outsideLength > 0.001 ? outside / outsideLength : float2(0.0);\n"
            + "    normal *= sign(p);\n"
            + "    float bend = 0.6 * u / sqrt(max(1.0 - 0.64 * u * u, 0.36));\n"
            + "    float displacement = bend * strength;\n"
            + "    float split = chromatic * rim;\n"
            + "    half4 lens = lensAt(xy + normal * displacement);\n"
            + "    if (chromatic > 0.0) {\n"
            + "        lens.r = lensAt(xy + normal * (displacement + split)).r;\n"
            + "        lens.b = lensAt(xy + normal * max(displacement - split, 0.0)).b;\n"
            + "    }\n"
            + "    return lens * half(rim);\n"
            + "}\n";
}
