package org.petal.catback.ui;

import android.graphics.Canvas;
import android.graphics.RenderNode;

// 2x supersampling: the car ui renders into a gpu layer at twice the output size, then lands at exactly half.
// at exactly .5 every output pixel samples dead centre between four layer texels, so plain bilinear is a clean
// 2x2 box filter: glass rims, the lens split, icons and text all get averaged edges for one extra blit
public final class Supersampler {
    private final RenderNode root = new RenderNode("catback-ssaa");

    public Supersampler() { root.setUseCompositingLayer(true, null); }

    public void draw(CarUi ui, Canvas out, int left, int top, int w, int h, float layoutWidth, float layoutHeight,
                     boolean compact, int factor) {
        if (factor <= 1 || !out.isHardwareAccelerated()) {
            ui.draw(out, left, top, w, h, layoutWidth, layoutHeight, compact, 1);
            return;
        }
        int cw = out.getWidth() * factor, ch = out.getHeight() * factor;
        root.setPosition(0, 0, cw, ch);
        Canvas big = root.beginRecording(cw, ch);
        try { ui.draw(big, left * factor, top * factor, w * factor, h * factor, layoutWidth, layoutHeight, compact, factor); }
        finally { root.endRecording(); }
        int saved = out.save();
        out.scale(1f / factor, 1f / factor);
        out.drawRenderNode(root);
        out.restoreToCount(saved);
    }
}
