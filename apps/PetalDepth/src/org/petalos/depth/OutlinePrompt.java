package org.petalos.depth;

import java.util.ArrayList;
import java.util.List;

final class OutlinePrompt {
    final float[] outline;
    final float[] points;
    final float[] labels;

    private OutlinePrompt(float[] outline, float[] points, float[] labels) {
        this.outline = outline;
        this.points = points;
        this.labels = labels;
    }

    static OutlinePrompt tap(float x, float y) {
        return new OutlinePrompt(new float[0], new float[]{clamp(x), clamp(y), 0, 0},
                new float[]{1, -1});
    }

    static OutlinePrompt fromLoop(float[] loop) {
        if (loop.length < 6 || loop.length % 2 != 0) {
            throw new IllegalArgumentException("Draw around the subject");
        }
        loop = loop.clone();
        float left = 1, top = 1, right = 0, bottom = 0;
        for (int i = 0; i < loop.length; i += 2) {
            loop[i] = clamp(loop[i]);
            loop[i + 1] = clamp(loop[i + 1]);
            left = Math.min(left, loop[i]);
            top = Math.min(top, loop[i + 1]);
            right = Math.max(right, loop[i]);
            bottom = Math.max(bottom, loop[i + 1]);
        }
        float area = 0;
        for (int i = 0, j = loop.length - 2; i < loop.length; j = i, i += 2) {
            area += loop[j] * loop[i + 1] - loop[i] * loop[j + 1];
        }
        if (Math.abs(area) < 0.008f || right - left < 0.03f || bottom - top < 0.03f) {
            throw new IllegalArgumentException("Draw a larger loop");
        }
        float seedX = 0, seedY = 0, best = -1;
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < 48; x++) {
                float px = left + (right - left) * (x + 0.5f) / 48;
                float py = top + (bottom - top) * (y + 0.5f) / 48;
                if (!contains(loop, px, py)) continue;
                float distance = distanceToEdge(loop, px, py);
                if (distance > best) {
                    best = distance;
                    seedX = px;
                    seedY = py;
                }
            }
        }
        if (best < 0) throw new IllegalArgumentException("Draw a closed loop");
        List<Float> points = new ArrayList<>();
        List<Float> labels = new ArrayList<>();
        add(points, labels, left, top, 2);
        add(points, labels, right, bottom, 3);
        add(points, labels, seedX, seedY, 1);
        float[][] outside = {{left - 0.05f, (top + bottom) / 2},
                {right + 0.05f, (top + bottom) / 2},
                {(left + right) / 2, top - 0.05f}, {(left + right) / 2, bottom + 0.05f}};
        for (float[] point : outside) {
            if (point[0] >= 0 && point[0] <= 1 && point[1] >= 0 && point[1] <= 1) {
                add(points, labels, point[0], point[1], 0);
            }
        }
        return new OutlinePrompt(loop, array(points), array(labels));
    }

    OutlinePrompt corrected(float x, float y, boolean keep) {
        if (labels.length >= 32) throw new IllegalArgumentException("Redraw the outline to start again");
        float[] nextPoints = java.util.Arrays.copyOf(points, points.length + 2);
        float[] nextLabels = java.util.Arrays.copyOf(labels, labels.length + 1);
        nextPoints[points.length] = clamp(x);
        nextPoints[points.length + 1] = clamp(y);
        nextLabels[labels.length] = keep ? 1 : 0;
        if (keep) {
            for (int i = 0; i < labels.length; i++) {
                if (labels[i] == 2) {
                    nextPoints[i * 2] = Math.min(nextPoints[i * 2], clamp(x - 0.03f));
                    nextPoints[i * 2 + 1] = Math.min(nextPoints[i * 2 + 1], clamp(y - 0.03f));
                } else if (labels[i] == 3) {
                    nextPoints[i * 2] = Math.max(nextPoints[i * 2], clamp(x + 0.03f));
                    nextPoints[i * 2 + 1] = Math.max(nextPoints[i * 2 + 1], clamp(y + 0.03f));
                }
            }
        }
        return new OutlinePrompt(outline, nextPoints, nextLabels);
    }

    boolean contains(float x, float y) {
        return outline.length == 0 || contains(outline, x, y);
    }

    private static boolean contains(float[] loop, float x, float y) {
        boolean inside = false;
        for (int i = 0, j = loop.length - 2; i < loop.length; j = i, i += 2) {
            if ((loop[i + 1] > y) != (loop[j + 1] > y)
                    && x < (loop[j] - loop[i]) * (y - loop[i + 1])
                    / (loop[j + 1] - loop[i + 1]) + loop[i]) inside = !inside;
        }
        return inside;
    }

    private static float distanceToEdge(float[] loop, float x, float y) {
        float best = Float.MAX_VALUE;
        for (int i = 0, j = loop.length - 2; i < loop.length; j = i, i += 2) {
            float dx = loop[i] - loop[j], dy = loop[i + 1] - loop[j + 1];
            float length = dx * dx + dy * dy;
            float t = length == 0 ? 0 : Math.max(0, Math.min(1,
                    ((x - loop[j]) * dx + (y - loop[j + 1]) * dy) / length));
            float ex = x - loop[j] - t * dx, ey = y - loop[j + 1] - t * dy;
            best = Math.min(best, ex * ex + ey * ey);
        }
        return best;
    }

    private static float clamp(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Invalid outline");
        return Math.max(0, Math.min(1, value));
    }

    private static void add(List<Float> points, List<Float> labels, float x, float y, float label) {
        points.add(x);
        points.add(y);
        labels.add(label);
    }

    private static float[] array(List<Float> values) {
        float[] result = new float[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i);
        return result;
    }
}
