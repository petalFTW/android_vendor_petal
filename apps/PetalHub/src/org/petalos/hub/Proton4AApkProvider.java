/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.petalos.hub;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Read-only, per-install URI for the signed APK built in Hub's cache. */
public final class Proton4AApkProvider extends ContentProvider {
    static final String AUTHORITY = "org.petalos.hub.proton4a.apks";

    static Uri uriFor(File apk) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(apk.getName()).build();
    }

    private File apkFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("[0-9a-f]{16}\\.apk")
                || uri.getPathSegments().size() != 1) {
            throw new FileNotFoundException("Invalid program APK");
        }
        File apk = new File(new File(getContext().getCacheDir(), "program-apks"), name);
        if (!apk.isFile()) throw new FileNotFoundException(name);
        return apk;
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only");
        return ParcelFileDescriptor.open(apkFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        try {
            File apk = apkFor(uri);
            String[] columns = projection == null
                    ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = apk.getName();
                else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = apk.length();
            }
            cursor.addRow(row);
            return cursor;
        } catch (FileNotFoundException exception) {
            return null;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
