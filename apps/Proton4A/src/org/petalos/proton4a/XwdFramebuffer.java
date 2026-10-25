/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.petalos.proton4a;

import android.graphics.Bitmap;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reads the XWD framebuffer Xvfb mirrors its screen to (via {@code -fbdir}).
 * The header is big-endian; the pixel payload follows the header's byte order.
 */
public final class XwdFramebuffer {
    private static final int HEADER_BYTES = 100;

    private XwdFramebuffer() {}

    public static final class Frame {
        public final int width;
        public final int height;
        public final int[] argb;
        public final long fileLength;

        Frame(int width, int height, int[] argb, long fileLength) {
            this.width = width;
            this.height = height;
            this.argb = argb;
            this.fileLength = fileLength;
        }
    }

    public static Frame read(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.length() < HEADER_BYTES) throw new IOException("XWD header truncated");
            byte[] header = new byte[HEADER_BYTES];
            input.readFully(header);
            ByteBuffer fields = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
            int headerSize = fields.getInt(0);
            int width = fields.getInt(16);
            int height = fields.getInt(20);
            int byteOrder = fields.getInt(28); // 0 = LSBFirst, 1 = MSBFirst
            int bitsPerPixel = fields.getInt(44);
            int bytesPerLine = fields.getInt(48);
            int colors = fields.getInt(76);

            if (headerSize < HEADER_BYTES || width <= 0 || height <= 0
                    || bitsPerPixel != 32 || width > 8192 || height > 8192
                    || bytesPerLine < (long) width * 4 || bytesPerLine > 8192 * 4
                    || colors < 0 || colors > 65536 || (byteOrder != 0 && byteOrder != 1)) {
                throw new IOException("Unsupported XWD format");
            }
            // XWDColor is 12 bytes; the palette sits between the window name
            // (included in headerSize) and the pixel payload.
            long pixelsOffset = headerSize + 12L * colors;
            if (input.length() < pixelsOffset + (long) bytesPerLine * height) {
                throw new IOException("XWD pixels truncated");
            }
            input.seek(pixelsOffset);
            byte[] row = new byte[bytesPerLine];
            int[] argb = new int[width * height];
            for (int y = 0; y < height; y++) {
                input.readFully(row);
                ByteBuffer pixels = ByteBuffer.wrap(row).order(
                        byteOrder == 0 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                for (int x = 0; x < width; x++) {
                    argb[y * width + x] = 0xFF000000 | (pixels.getInt() & 0x00FFFFFF);
                }
            }
            return new Frame(width, height, argb, input.length());
        }
    }

    public static Bitmap toBitmap(Frame frame) {
        return Bitmap.createBitmap(frame.argb, frame.width, frame.height,
                Bitmap.Config.ARGB_8888);
    }
}
