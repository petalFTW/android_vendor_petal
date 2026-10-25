/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.petalos.proton4a;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal reader for the ustar/GNU tar streams that carry the runtime payload.
 * Only the entry types present in that payload are supported: directories,
 * regular files, symlinks and hardlinks, plus GNU long-name records.
 */
final class TarArchive {
    private static final int BLOCK = 512;

    private TarArchive() {}

    interface Listener {
        /** @return false to skip the entry. */
        boolean onEntry(TarEntry entry) throws IOException;
    }

    static final class TarEntry {
        String name;
        boolean directory;
        boolean symlink;
        boolean hardlink;
        String linkName;
        int mode;
        long size;
        InputStream content;
    }

    static void read(InputStream input, Listener listener) throws IOException {
        byte[] header = new byte[BLOCK];
        String longName = null;
        String longLink = null;
        while (true) {
            readFully(input, header);
            if (isZero(header)) {
                readFully(input, header);
                if (isZero(header)) return;
            }
            String name = longName != null ? longName : name(header);
            int type = header[156] & 0xff;
            if (type == 'L' || type == 'K') {
                long size = size(header);
                String value = new String(readBytes(input, (int) size), StandardCharsets.UTF_8)
                        .replace("\0", "").trim();
                if (type == 'L') longName = value;
                else longLink = value;
                skipPadding(input, size);
                continue;
            }
            longName = null;

            TarEntry entry = new TarEntry();
            entry.name = name;
            entry.mode = (int) octal(header, 100, 8);
            entry.size = size(header);
            entry.directory = type == '5' || entry.name.endsWith("/");
            entry.symlink = type == '2';
            entry.hardlink = type == '1';
            entry.linkName = type == '1' || type == '2'
                    ? (longLink != null ? longLink : linkName(header)) : null;
            longLink = null;

            InputStream content = entry.directory ? null : new BoundedStream(input, entry.size);
            entry.content = content;
            if (entry.directory) {
                listener.onEntry(entry);
            } else {
                if (listener.onEntry(entry)) {
                    drain(content);
                } else {
                    skipFully(input, entry.size);
                }
                skipPadding(input, entry.size);
            }
        }
    }

    private static void drain(InputStream input) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        while (input.read(buffer) != -1) {
            // discard
        }
    }

    private static byte[] readBytes(InputStream input, int count) throws IOException {
        byte[] data = new byte[count];
        readFully(input, data);
        return data;
    }

    private static void skipPadding(InputStream input, long size) throws IOException {
        long remainder = size % BLOCK;
        if (remainder != 0) skipFully(input, BLOCK - remainder);
    }

    private static void skipFully(InputStream input, long count) throws IOException {
        long skipped = 0;
        while (skipped < count) {
            long step = input.skip(count - skipped);
            if (step <= 0) {
                if (input.read() == -1) throw new EOFException();
                step = 1;
            }
            skipped += step;
        }
    }

    private static void readFully(InputStream input, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int count = input.read(target, offset, target.length - offset);
            if (count == -1) throw new EOFException("truncated tar archive");
            offset += count;
        }
    }

    private static boolean isZero(byte[] block) {
        for (byte value : block) {
            if (value != 0) return false;
        }
        return true;
    }

    private static String field(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) end++;
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static String name(byte[] header) {
        String prefix = field(header, 345, 155);
        String base = field(header, 0, 100);
        return prefix.isEmpty() ? base : prefix + "/" + base;
    }

    private static String linkName(byte[] header) {
        return field(header, 157, 100);
    }

    private static long size(byte[] header) {
        if ((header[124] & 0x80) != 0) {
            long value = header[124] & 0x7f;
            for (int index = 125; index < 136; index++) {
                value = (value << 8) | (header[index] & 0xff);
            }
            return value;
        }
        return octal(header, 124, 12);
    }

    private static long octal(byte[] header, int offset, int length) {
        long value = 0;
        boolean seen = false;
        for (int index = offset; index < offset + length; index++) {
            int digit = header[index] & 0xff;
            if (digit == 0 || digit == ' ') {
                if (seen) break;
                continue;
            }
            if (digit < '0' || digit > '7') break;
            value = (value << 3) | (digit - '0');
            seen = true;
        }
        return value;
    }

    /** Caps reads at the declared entry size so callers cannot cross entries. */
    private static final class BoundedStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        BoundedStream(InputStream delegate, long size) {
            this.delegate = delegate;
            this.remaining = size;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = delegate.read();
            if (value != -1) remaining--;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int capped = (int) Math.min(length, remaining);
            int count = delegate.read(buffer, offset, capped);
            if (count != -1) remaining -= count;
            return count;
        }

        @Override
        public long skip(long count) throws IOException {
            long capped = Math.min(count, remaining);
            long skipped = delegate.skip(capped);
            if (skipped > 0) remaining -= skipped;
            return skipped;
        }
    }

    static void copy(InputStream input, File target) throws IOException {
        try (FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
    }
}
