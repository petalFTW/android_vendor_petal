/*
 * Copyright (C) 2026 petalOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.petalos.depth;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.os.UserManager;
import android.util.AtomicFile;
import android.util.Log;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import java.io.File;
import java.io.FileNotFoundException;

// serves cutout and base pngs to systemui
public class DepthContentProvider extends ContentProvider {

    static void cacheLockscreenAsset(Context context, String name) throws IOException {
        File source = new File(context.getFilesDir(), name);
        AtomicFile cached = new AtomicFile(new File(
                context.createDeviceProtectedStorageContext().getFilesDir(), name));
        if (!source.exists()) {
            cached.delete();
            return;
        }
        FileOutputStream output = null;
        try (FileInputStream input = new FileInputStream(source)) {
            output = cached.startWrite();
            input.transferTo(output);
            cached.finishWrite(output);
        } catch (IOException error) {
            cached.failWrite(output);
            throw error;
        }
    }

    private File assetFile(String name) {
        Context context = getContext();
        boolean lockscreenAsset = name.equals("cutout.png") || name.equals("frost.png")
                || name.equals("liquid.png");
        if (context.getSystemService(UserManager.class).isUserUnlocked()) {
            if ("cutout.png".equals(name)) {
                // cutout got binned, rebuild it from the raw draft so the lock
                // screen heals itself instead of waiting for depth studio
                DepthStudioActivity.healCutout(context.getFilesDir());
            }
            if (lockscreenAsset) {
                try {
                    cacheLockscreenAsset(context, name);
                } catch (IOException error) {
                    Log.w("PetalDepth", "Could not cache lockscreen asset", error);
                }
            }
            return new File(context.getFilesDir(), name);
        }
        return lockscreenAsset ? new File(
                context.createDeviceProtectedStorageContext().getFilesDir(), name) : null;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && mode.contains("w")) {
            throw new FileNotFoundException("read-only");
        }
        String name = uri.getLastPathSegment();
        if (TextUtils.isEmpty(name)) {
            throw new FileNotFoundException(uri.toString());
        }
        if (!name.equals("cutout.png") && !name.equals("base.png")
                && !name.equals("base.jpg") && !name.equals("frost.png")
                && !name.equals("liquid.png")) {
            throw new FileNotFoundException(uri.toString());
        }
        File file = assetFile(name);
        if (file == null || !file.exists()) {
            throw new FileNotFoundException(uri.toString());
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if ("cutout.png".equals(name)) return "image/png";
        if ("liquid.png".equals(name)) return "image/png";
        if ("frost.png".equals(name)) return "image/png";
        if ("base.png".equals(name)) return "image/png";
        if ("base.jpg".equals(name)) return "image/jpeg";
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
