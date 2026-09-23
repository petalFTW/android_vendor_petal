package org.petalos.depth;

final class MaskAlpha {
    static byte[] fromConfidence(float[] confidence) {
        int selected = 0;
        for (float value : confidence) {
            if (!Float.isFinite(value) || value < 0f || value > 1f) {
                throw new IllegalArgumentException("Invalid confidence mask");
            }
            if (value >= 0.5f) selected++;
        }
        if (selected < Math.max(16, confidence.length / 2000)
                || selected > confidence.length * 0.985f) {
            throw new IllegalArgumentException("No clear subject");
        }
        byte[] alpha = new byte[confidence.length];
        for (int i = 0; i < confidence.length; i++) {
            float t = Math.max(0f, Math.min(1f, (confidence[i] - 0.28f) / 0.44f));
            alpha[i] = (byte) Math.round(t * t * (3f - 2f * t) * 255f);
        }
        return alpha;
    }

    // fade the edge inward, keep bg colour out
    static byte[] feather(byte[] alpha, int width, int height, int radius) {
        if (width <= 0 || height <= 0 || alpha.length != width * height || radius < 1) {
            throw new IllegalArgumentException("Invalid mask dimensions or radius");
        }
        byte[] horizontal = new byte[alpha.length];
        byte[] result = new byte[alpha.length];
        int count = radius * 2 + 1;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            int sum = 0;
            for (int x = -radius; x <= radius; x++) {
                sum += alpha[row + Math.max(0, Math.min(width - 1, x))] & 255;
            }
            for (int x = 0; x < width; x++) {
                horizontal[row + x] = (byte) ((sum + count / 2) / count);
                sum -= alpha[row + Math.max(0, x - radius)] & 255;
                sum += alpha[row + Math.min(width - 1, x + radius + 1)] & 255;
            }
        }
        for (int x = 0; x < width; x++) {
            int sum = 0;
            for (int y = -radius; y <= radius; y++) {
                sum += horizontal[Math.max(0, Math.min(height - 1, y)) * width + x] & 255;
            }
            for (int y = 0; y < height; y++) {
                int index = y * width + x;
                result[index] = (byte) Math.min(alpha[index] & 255,
                        (sum + count / 2) / count);
                sum -= horizontal[Math.max(0, y - radius) * width + x] & 255;
                sum += horizontal[Math.min(height - 1, y + radius + 1) * width + x] & 255;
            }
        }
        return result;
    }

    static byte[] outline(byte[] alpha, int width, int height) {
        byte[] ring = new byte[alpha.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int low = 255, high = 0;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        if (dx * dx + dy * dy > 5) continue;
                        int index = Math.max(0, Math.min(height - 1, y + dy)) * width
                                + Math.max(0, Math.min(width - 1, x + dx));
                        int value = alpha[index] & 255;
                        low = Math.min(low, value);
                        high = Math.max(high, value);
                    }
                }
                ring[y * width + x] = (byte) (high - low);
            }
        }
        return ring;
    }

    // dilate one px so fg covers the soft edge
    static byte[] dilated(byte[] alpha, int width, int height) {
        byte[] out = new byte[alpha.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int best = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= height) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= width) continue;
                        int value = alpha[yy * width + xx] & 255;
                        if (value > best) best = value;
                    }
                }
                out[y * width + x] = (byte) best;
            }
        }
        return out;
    }
}
