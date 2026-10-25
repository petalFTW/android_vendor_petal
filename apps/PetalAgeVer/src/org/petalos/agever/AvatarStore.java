/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.agever;

import android.content.Context;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.concurrent.CancellationException;

public final class AvatarStore {
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    private AvatarStore() {}

    public static byte[] read(Context context, String ref) throws IOException {
        try (InputStream input = ref.startsWith("/")
                ? new FileInputStream(ownedFile(context, ref)) : context.getAssets().open(ref)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            copy(input, bytes);
            byte[] result = bytes.toByteArray();
            validate(result);
            return result;
        }
    }

    public static File importFile(Context context, Uri uri) throws IOException {
        File directory = new File(context.getFilesDir(), "avatars");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Storage unavailable");
        File pending = new File(directory, UUID.randomUUID() + ".pending");
        File destination = new File(directory, pending.getName().replace(".pending", ".glb"));
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                    FileOutputStream output = new FileOutputStream(pending)) {
                if (input == null) throw new IOException("File unavailable");
                copy(input, output);
                output.getFD().sync();
            }
            read(context, pending.getAbsolutePath());
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (!pending.renameTo(destination)) throw new IOException("Could not finish import");
            return destination;
        } finally {
            pending.delete();
        }
    }

    public static File ownedFile(Context context, String ref) throws IOException {
        File directory = new File(context.getFilesDir(), "avatars").getCanonicalFile();
        File file = new File(ref).getCanonicalFile();
        if (!directory.equals(file.getParentFile())) throw new IOException("Not an imported avatar");
        return file;
    }

    public static File thumbnail(Context context, String ref) {
        String key;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(
                    ref.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            key = android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP
                    | android.util.Base64.URL_SAFE);
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
        return new File(new File(context.getCacheDir(), "avatar-thumbnails"), key + ".png");
    }

    private static void copy(InputStream input, java.io.OutputStream output) throws IOException {
        byte[] buffer = new byte[65536];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (count > MAX_BYTES - total) throw new IOException("Avatar exceeds 64 MB");
            output.write(buffer, 0, count);
            total += count;
        }
    }

    public static void validate(byte[] bytes) throws IOException {
        if (bytes.length < 20 || bytes.length > MAX_BYTES) throw new IOException("Invalid GLB size");
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (header.getInt() != 0x46546c67 || header.getInt() != 2 || header.getInt() != bytes.length) {
            throw new IOException("Choose a GLB version 2 file");
        }
        int offset = 12;
        boolean first = true;
        while (offset < bytes.length) {
            if (bytes.length - offset < 8) throw new IOException("Truncated GLB chunk");
            int length = header.getInt(offset);
            int type = header.getInt(offset + 4);
            if (length <= 0 || length % 4 != 0 || length > bytes.length - offset - 8
                    || (first && type != 0x4e4f534a)) throw new IOException("Invalid GLB chunk");
            if (first) validateJson(bytes, offset + 8, length);
            offset += 8 + length;
            first = false;
        }
    }

    private static void validateJson(byte[] bytes, int offset, int length) throws IOException {
        if (length > 4 * 1024 * 1024) throw new IOException("Avatar metadata exceeds 4 MB");
        String text = new String(bytes, offset, length, java.nio.charset.StandardCharsets.UTF_8);
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') {
                if (++depth > 32) throw new IOException("Avatar metadata is too deeply nested");
            } else if (c == '}' || c == ']') {
                if (--depth < 0) throw new IOException("Invalid avatar metadata");
            }
        }
        if (depth != 0 || quoted) throw new IOException("Invalid avatar metadata");
        try {
            org.json.JSONObject document = new org.json.JSONObject(text);
            if (!"2.0".equals(document.getJSONObject("asset").getString("version"))) {
                throw new IOException("Choose a glTF 2.0 avatar");
            }
            for (String kind : new String[]{"buffers", "images"}) {
                org.json.JSONArray entries = document.optJSONArray(kind);
                if (entries == null) continue;
                for (int i = 0; i < entries.length(); i++) {
                    String uri = entries.getJSONObject(i).optString("uri", "");
                    if (!uri.isEmpty() && !uri.startsWith("data:")) {
                        throw new IOException("Use a self-contained GLB with embedded resources");
                    }
                }
            }
        } catch (org.json.JSONException e) { throw new IOException("Invalid avatar metadata", e); }
    }
}
