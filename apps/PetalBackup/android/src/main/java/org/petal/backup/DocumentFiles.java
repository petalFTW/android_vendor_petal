/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petal.backup;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import org.petal.backup.core.OperationControl;

final class DocumentFiles {
    private DocumentFiles() {}

    record Info(String name, long size, boolean available, int format) {}
    static final class SpaceException extends IOException {
        SpaceException() { super("Not enough temporary storage for this operation"); }
    }
    static final class FormatException extends IOException {
        FormatException(IOException cause) { super("This backup could not be read", cause); }
    }
    interface Checking { void run() throws IOException; }

    static Info info(Context context, Uri uri, boolean archive, CancellationSignal signal) {
        String name = context.getString(R.string.unnamed_file);
        long size = -1;
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null, signal)) {
            if (cursor == null || !cursor.moveToFirst()) return new Info(name, -1, false, 0);
            int title = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            int length = cursor.getColumnIndex(OpenableColumns.SIZE);
            if (title >= 0 && !cursor.isNull(title)) name = cursor.getString(title);
            if (length >= 0 && !cursor.isNull(length)) size = Math.max(-1, cursor.getLong(length));
        } catch (Exception unavailable) { return new Info(name, -1, false, 0); }
        int format = 0;
        if (archive) {
            try (InputStream input = open(context, uri, signal)) {
                byte[] magic = input.readNBytes(8);
                String header = new String(magic, StandardCharsets.US_ASCII);
                format = header.equals("PETALBK1") ? 1 : header.equals("PETALBK2") ? 2 : 0;
            } catch (IOException | RuntimeException unavailable) { return new Info(name, size, false, 0); }
        }
        return new Info(name, size, true, format);
    }

    static InputStream open(Context context, Uri uri, CancellationSignal signal) throws IOException {
        ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "r", signal);
        if (descriptor == null) throw new IOException("Document provider returned no file");
        return new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
    }

    static String name(Context context, Uri uri, int index) {
        String name = null;
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        }
        if (name == null) name = "file-" + index;
        name = name.replaceAll("[\\p{Cntrl}\\\\/:]", "_");
        if (name.isEmpty() || name.equals(".") || name.equals("..")
                || !StandardCharsets.UTF_8.newEncoder().canEncode(name)) {
            name = "file-" + index;
        }
        return name;
    }

    static long copy(InputStream input, OutputStream output, long limit,
            OperationControl control) throws IOException {
        return transfer(input, output, limit, control).bytes;
    }

    static void exportVerified(Context context, Path source, Uri destination,
            CancellationSignal signal, OperationControl control) throws IOException {
        exportVerified(context, source, destination, signal, control, () -> {});
    }

    static void exportVerified(Context context, Path source, Uri destination,
            CancellationSignal signal, OperationControl control, Checking checking) throws IOException {
        long expectedBytes = Files.size(source);
        Transfer expected;
        try (InputStream input = Files.newInputStream(source);
                OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
            if (output == null) throw new IOException("Document provider returned no output stream");
            expected = transfer(input, output, expectedBytes, control);
            output.flush();
        }
        checking.run();
        try (InputStream saved = open(context, destination, signal)) {
            Transfer found = transfer(saved, null, expectedBytes, control);
            if (expected.bytes != expectedBytes || found.bytes != expectedBytes
                    || !MessageDigest.isEqual(expected.hash, found.hash)) {
                throw new IOException("Saved document did not pass read-back verification");
            }
        }
    }

    static Uri createRestoreDirectory(Context context, Uri tree, String name) throws IOException {
        String parentId = DocumentsContract.getTreeDocumentId(tree);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        Set<String> existing = new HashSet<>();
        try (Cursor cursor = context.getContentResolver().query(children,
                new String[] {DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            if (cursor == null) throw new IOException("Could not inspect restore destination");
            while (cursor.moveToNext()) existing.add(cursor.getString(0));
        }
        Uri created = DocumentsContract.createDocument(context.getContentResolver(), parent,
                DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (created == null || existing.contains(DocumentsContract.getDocumentId(created))) {
            throw new IOException("Provider did not create a new restore directory");
        }
        return created;
    }

    static Uri create(Context context, Uri parent, String mime, String name) throws IOException {
        Uri created = DocumentsContract.createDocument(context.getContentResolver(), parent, mime, name);
        if (created == null) throw new IOException("Could not create restored document");
        return created;
    }

    static boolean delete(Context context, Uri uri) {
        try {
            return DocumentsContract.deleteDocument(context.getContentResolver(), uri);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Transfer transfer(InputStream input, OutputStream output, long limit,
            OperationControl control) throws IOException {
        if (limit < 0) throw new SpaceException();
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new AssertionError(exception); }
        byte[] buffer = new byte[64 * 1024];
        long bytes = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            control.check();
            if (count > limit - bytes) throw new SpaceException();
            if (output != null) output.write(buffer, 0, count);
            digest.update(buffer, 0, count);
            bytes += count;
        }
        control.check();
        return new Transfer(bytes, digest.digest());
    }

    private static final class Transfer {
        final long bytes;
        final byte[] hash;
        Transfer(long bytes, byte[] hash) { this.bytes = bytes; this.hash = hash; }
    }
}
