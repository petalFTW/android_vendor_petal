package org.petalos.depth;

import java.nio.FloatBuffer;

final class SamMask {
    static int select(FloatBuffer masks, FloatBuffer quality, int count,
            int width, int height, OutlinePrompt prompt) {
        int best = -1;
        float bestScore = -Float.MAX_VALUE;
        int stride = width * height;
        for (int candidate = 0; candidate < count; candidate++) {
            int selected = 0, intersection = 0, union = 0, samples = 0;
            for (int y = 0; y < height; y += 4) {
                for (int x = 0; x < width; x += 4) {
                    boolean foreground = masks.get(candidate * stride + y * width + x) > 0;
                    boolean inside = prompt.contains((x + 0.5f) / width, (y + 0.5f) / height);
                    if (foreground) selected++;
                    if (foreground && inside) intersection++;
                    if (foreground || inside) union++;
                    samples++;
                }
            }
            if (selected < Math.max(1, samples / 2000) || selected > samples * 0.985f) continue;
            float score = quality.get(candidate);
            if (!Float.isFinite(score)) continue;
            if (prompt.outline.length > 0) score += 0.7f * intersection / Math.max(1, union);
            for (int i = 0; i < prompt.labels.length; i++) {
                if (prompt.labels[i] != 0 && prompt.labels[i] != 1) continue;
                int x = Math.min(width - 1, Math.round(prompt.points[i * 2] * (width - 1)));
                int y = Math.min(height - 1, Math.round(prompt.points[i * 2 + 1] * (height - 1)));
                boolean foreground = masks.get(candidate * stride + y * width + x) > 0;
                if (foreground != (prompt.labels[i] == 1)) score -= 2;
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best < 0) throw new IllegalArgumentException("No clear subject. Redraw the outline.");
        return best;
    }
}
