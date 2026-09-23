package org.petalos.depth;
import java.util.Arrays;
public class MaskAlphaTest {
    public static void main(String[] args) {
        float[] mask = new float[10000];
        rejects(mask);
        Arrays.fill(mask, 1f);
        rejects(mask);
        Arrays.fill(mask, 0f);
        Arrays.fill(mask, 0, 3, 1f);
        rejects(mask);
        Arrays.fill(mask, 0, 3000, 1f);
        mask[3000] = 0.5f;
        byte[] alpha = MaskAlpha.fromConfidence(mask);
        if ((alpha[0] & 255) != 255 || alpha[9999] != 0
                || Math.abs((alpha[3000] & 255) - 128) > 1) throw new AssertionError();
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -0.1f, 1.1f}) {
            mask[5000] = invalid;
            rejects(mask);
        }
        byte[] solid = new byte[81];
        Arrays.fill(solid, (byte) 255);
        if (!Arrays.equals(solid, MaskAlpha.feather(solid, 9, 9, 2))) {
            throw new AssertionError("Solid mask changed");
        }
        byte[] edge = new byte[81];
        for (int y = 0; y < 9; y++) for (int x = 4; x < 9; x++) {
            edge[y * 9 + x] = (byte) 255;
        }
        byte[] ring = MaskAlpha.outline(edge, 9, 9);
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 9; x++) {
                int expected = x >= 2 && x <= 5 ? 255 : 0;
                if ((ring[y * 9 + x] & 255) != expected) {
                    throw new AssertionError("Outline filled the foreground or background");
                }
            }
        }
        for (byte value : MaskAlpha.outline(solid, 9, 9)) {
            if (value != 0) throw new AssertionError("False outline at image border");
        }
        byte[] original = edge.clone();
        byte[] feathered = MaskAlpha.feather(edge, 9, 9, 2);
        if (!Arrays.equals(edge, original)) throw new AssertionError("Raw mask changed");
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 4; x++) {
                if (feathered[y * 9 + x] != 0) throw new AssertionError("Mask expanded");
            }
            if ((feathered[y * 9 + 4] & 255) != 153
                    || (feathered[y * 9 + 5] & 255) != 204
                    || (feathered[y * 9 + 6] & 255) != 255) {
                throw new AssertionError("Incorrect edge fade");
            }
        }
        byte[] soft = {(byte) 80};
        if (!Arrays.equals(soft, MaskAlpha.feather(soft, 1, 1, 5))) {
            throw new AssertionError("Tiny soft mask changed");
        }
        System.out.println("PASS: outline follows edges only; feather preserves interiors, transparency, raw mask and soft edges");
        System.out.println("PASS: empty, full, tiny, valid, soft edge, invalid masks");
    }
    static void rejects(float[] mask) {
        try { MaskAlpha.fromConfidence(mask); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Accepted invalid mask");
    }
}
