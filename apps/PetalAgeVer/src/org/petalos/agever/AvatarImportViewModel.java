/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.agever;

import android.content.Context;
import android.net.Uri;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import android.os.Handler;
import android.os.Looper;

public final class AvatarImportViewModel extends ViewModel {
    public static final class Result {
        public final boolean busy;
        public final File file;
        public final String error;
        public final String label;
        Result(boolean busy, File file, String error) {
            this(busy, file, error, "");
        }
        Result(boolean busy, File file, String error, String label) {
            this.busy = busy; this.file = file; this.error = error;
            this.label = label;
        }
    }
    public final MutableLiveData<Result> result = new MutableLiveData<>(new Result(false, null, null));
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private Future<?> mImport;
    private volatile int mGeneration;

    public void start(Context context, Uri uri) {
        cancel();
        Context app = context.getApplicationContext();
        final int generation = ++mGeneration;
        result.setValue(new Result(true, null, null));
        mImport = mWorker.submit(() -> {
            try {
                String name = app.getString(R.string.imported_avatar);
                try (android.database.Cursor cursor = app.getContentResolver().query(uri,
                        new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) {
                        name = cursor.getString(0).replaceAll("[\\p{Cntrl}]", "").trim();
                        if (name.length() > 64) name = name.substring(0, 64);
                    }
                } catch (RuntimeException ignored) { }
                final String label = name;
                File file = AvatarStore.importFile(app, uri);
                mMain.post(() -> {
                    if (generation != mGeneration) { file.delete(); return; }
                    result.setValue(new Result(false, file, null, label));
                });
            } catch (Exception | OutOfMemoryError e) {
                mMain.post(() -> {
                    if (generation == mGeneration) result.setValue(new Result(false, null,
                            e instanceof OutOfMemoryError ? "Not enough memory for this avatar" : e.getMessage()));
                });
            }
        });
    }
    public void consume() { result.setValue(new Result(false, null, null)); }
    public void cancel() {
        ++mGeneration;
        if (mImport != null) mImport.cancel(true);
        result.setValue(new Result(false, null, null));
    }
    @Override protected void onCleared() {
        ++mGeneration;
        if (mImport != null) mImport.cancel(true);
        mWorker.shutdownNow();
    }
}
