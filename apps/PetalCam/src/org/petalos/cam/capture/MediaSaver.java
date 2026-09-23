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

package org.petalos.cam.capture;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.ImageProxy;
import androidx.exifinterface.media.ExifInterface;

import org.petalos.cam.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persists captures into the shared gallery under DCIM/Camera, applying the
 * front-camera mirror and the optional petalOS watermark before writing, and
 * stamping the device, app, lens and capture info (plus GPS + orientation)
 * into Exif.
 */
public final class MediaSaver {

    private static final String TAG = "PetalCamSaver";
    public static final String ALBUM = "Camera";
    public static final String SOFTWARE = "petalOS Camera";

    /**
     * The ML stages are slow and must never block the shutter/ripple. Captures
     * land instantly; portrait (depth-of-field) then denoise run here, in
     * order, and swap the improved pixels into the file when done.
     */
    private static final ExecutorService PROCESSING_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "PetalCamProcess");
                t.setPriority(Thread.NORM_PRIORITY);
                return t;
            });

    /** Depth-of-field amount applied in PORTRAIT mode (kept subtle). */
    private static final float PORTRAIT_STRENGTH = 0.5f;

    /** Notified on the caller's executor once the ML stages are finished. */
    public interface ProcessCallback {
        void onProcessed(@Nullable Uri uri, boolean ok);
    }

    private MediaSaver() {
    }

    public static final class Result {
        public final Uri uri;
        public final boolean ok;

        Result(Uri uri, boolean ok) {
            this.uri = uri;
            this.ok = ok;
        }
    }

    /**
     * Everything PetalCam knows about the shot that should survive in the file.
     * Written into Exif so any viewer can see which lens/app/device took it.
     */
    public static final class Meta {
        /** "back" or "front". */
        public String lens = "back";
        /** Make of the optics, normally the phone maker. */
        public String lensMake = "";
        /** Human lens string, e.g. "back 24mm f/1.8". */
        public String lensModel = "";
        /** Capture mode, e.g. "PHOTO" / "PRO" / "VIDEO". */
        public String mode = "PHOTO";
        /** Zoom ratio applied at capture time. */
        public float zoom = 1f;
        /** Physical focal length in mm, 0 when unknown. */
        public float focalLength = 0f;
        /** 35mm equivalent focal length in mm, 0 when unknown. */
        public float focalLength35 = 0f;
        /** Lens aperture (f-number), 0 when unknown. */
        public float aperture = 0f;
        public String software = SOFTWARE;

        /** One-line summary stamped as the image description. */
        public String describe() {
            StringBuilder sb = new StringBuilder(software);
            if (lens != null && !lens.isEmpty()) {
                sb.append(" | ").append(lens);
            }
            if (mode != null && !mode.isEmpty()) {
                sb.append(" | ").append(mode);
            }
            sb.append(" | ").append(String.format(Locale.US, "%.1fx", zoom));
            if (focalLength > 0f) {
                sb.append(" | ").append(Math.round(focalLength)).append("mm");
            }
            if (aperture > 0f) {
                sb.append(" f/").append(String.format(Locale.US, "%.1f", aperture));
            }
            return sb.toString();
        }
    }

    /** Save an ImageCapture JPEG result. */
    @NonNull
    public static Result saveImage(@NonNull Context context, @NonNull ImageProxy image,
            boolean mirror, boolean watermark, @Nullable Double lat, @Nullable Double lon,
            int rotationDegrees, boolean portrait, float denoise, @Nullable Meta meta,
            @Nullable java.util.concurrent.Executor uiExecutor, @Nullable ProcessCallback callback) {
        byte[] jpeg = toJpegBytes(image);
        if (jpeg == null) {
            if (callback != null && uiExecutor != null) {
                uiExecutor.execute(() -> callback.onProcessed(null, false));
            }
            return new Result(null, false);
        }
        return saveJpeg(context, jpeg, mirror, watermark, lat, lon, rotationDegrees,
                portrait, denoise, meta, uiExecutor, callback);
    }

    public static byte[] toJpegBytes(ImageProxy image) {
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            buffer.rewind();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (Throwable t) {
            Log.w(TAG, "Could not extract JPEG", t);
            return null;
        } finally {
            image.close();
        }
    }

    @NonNull
    public static Result saveJpeg(@NonNull Context context, @NonNull byte[] jpeg,
            boolean mirror, boolean watermark, @Nullable Double lat, @Nullable Double lon,
            int rotationDegrees, boolean portrait, float denoise, @Nullable Meta meta,
            @Nullable java.util.concurrent.Executor uiExecutor, @Nullable ProcessCallback callback) {
        // Fast path: mirror/watermark only. The denoiser is never run here, so
        // the shutter (and the ripple over it) returns immediately; the cleaned
        // pixels replace the file later on the background thread.
        byte[] payload = transform(context, jpeg, mirror, watermark, rotationDegrees);
        boolean baked = mirror || (watermark && rotationDegrees != 0);
        int exifOrientation = exifFromDegrees(baked ? 0 : rotationDegrees);

        String name = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                .format(new Date()) + ".jpg";

        Uri uri = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_DCIM + File.separator + ALBUM);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return new Result(null, false);
            }

            try (OutputStream os = resolver.openOutputStream(uri)) {
                if (os == null) {
                    return new Result(null, false);
                }
                os.write(payload);
            }

            // EXIF: device/app/lens identity, orientation and optional GPS.
            writeExif(resolver, uri, lat, lon, exifOrientation, meta);

            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);

            final Uri saved = uri;
            if (portrait || denoise > 0f) {
                scheduleProcessing(context, jpeg, mirror, watermark, lat, lon,
                        rotationDegrees, portrait, denoise, meta, saved, uiExecutor, callback);
            } else if (callback != null && uiExecutor != null) {
                uiExecutor.execute(() -> callback.onProcessed(saved, true));
            }
            return new Result(uri, true);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to save capture", t);
            if (uri != null) {
                try {
                    context.getContentResolver().delete(uri, null, null);
                } catch (Throwable ignored) {
                }
            }
            return new Result(null, false);
        }
    }

    /**
     * Applies the front-camera mirror and the watermark. The sensor frame is
     * brought upright first and only then mirrored across the upright image's
     * vertical axis, otherwise a 90/270 sensor frame ends up flipped
     * vertically (the "selfie upside down" bug).
     */
    private static byte[] transform(Context context, byte[] jpeg, boolean mirror,
            boolean watermark, int rotationDegrees) {
        if (!mirror && !watermark) {
            return jpeg;
        }
        Bitmap decoded = null;
        try {
            decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        } catch (Throwable t) {
            Log.w(TAG, "Decode failed", t);
        }
        if (decoded == null) {
            return jpeg;
        }
        Bitmap out = decoded;
        if (mirror || rotationDegrees != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotationDegrees);
            if (mirror) {
                m.postScale(-1f, 1f);
            }
            out = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(),
                    decoded.getHeight(), m, false);
        }
        if (watermark) {
            out = drawWatermark(context, out);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, 96, bos);
        return bos.toByteArray();
    }

    /**
     * Runs the post-capture ML stages off the UI thread, in order: portrait
     * depth-of-field first, then the denoiser, then swaps the finished pixels
     * into the already-saved MediaStore row (preserving its Exif). The caller
     * is notified on {@code uiExecutor} once both stages are done.
     */
    private static void scheduleProcessing(Context context, byte[] sourceJpeg,
            boolean mirror, boolean watermark, @Nullable Double lat, @Nullable Double lon,
            int rotationDegrees, boolean portrait, float denoise, @Nullable Meta meta,
            @NonNull Uri uri, @Nullable java.util.concurrent.Executor uiExecutor,
            @Nullable ProcessCallback callback) {
        final Context app = context.getApplicationContext();
        PROCESSING_EXECUTOR.execute(() -> {
            boolean ok = true;
            // resolve the model lazily on the worker; a missing/broken runtime
            // just drops the portrait stage and keeps the denoise
            final boolean wantPortrait = portrait && PortraitEngine.isAvailable(app);
            // rotate upright whenever any stage needs an upright frame (the
            // depth model certainly does; the fast path may have already baked it)
            final boolean upright = wantPortrait || mirror
                    || (watermark && rotationDegrees != 0);
            try {
                Bitmap decoded = BitmapFactory.decodeByteArray(sourceJpeg, 0, sourceJpeg.length);
                if (decoded == null) {
                    ok = false;
                } else {
                    Bitmap working = decoded;
                    if (upright) {
                        working = rotate(working, rotationDegrees);
                    }
                    // 1) depth of field first...
                    if (wantPortrait) {
                        long t0 = android.os.SystemClock.uptimeMillis();
                        working = PortraitEngine.get(app).process(working, PORTRAIT_STRENGTH);
                        Log.d(TAG, "portrait pass took "
                                + (android.os.SystemClock.uptimeMillis() - t0) + "ms");
                    }
                    // 2) ...then the denoiser
                    if (denoise > 0f) {
                        working = DenoiseEngine.get(app).denoise(working, denoise);
                    }
                    if (mirror) {
                        working = mirrorX(working);
                    }
                    if (watermark) {
                        working = drawWatermark(app, working);
                    }
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    working.compress(Bitmap.CompressFormat.JPEG, 96, bos);
                    ContentResolver resolver = app.getContentResolver();
                    try (OutputStream os = resolver.openOutputStream(uri, "wt")) {
                        if (os == null) {
                            ok = false;
                        } else {
                            os.write(bos.toByteArray());
                            writeExif(resolver, uri, lat, lon,
                                    exifFromDegrees(upright ? 0 : rotationDegrees), meta);
                            Log.d(TAG, "Post-processing replaced " + uri);
                        }
                    }
                }
            } catch (Throwable t) {
                ok = false;
                Log.w(TAG, "Post-processing failed", t);
            }
            final boolean result = ok;
            if (callback != null && uiExecutor != null) {
                uiExecutor.execute(() -> callback.onProcessed(uri, result));
            }
        });
    }

    private static Bitmap rotate(Bitmap src, int degrees) {
        int d = ((degrees % 360) + 360) % 360;
        if (d == 0) {
            return src;
        }
        Matrix m = new Matrix();
        m.postRotate(d);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, false);
    }

    private static Bitmap mirrorX(Bitmap src) {
        Matrix m = new Matrix();
        m.postScale(-1f, 1f);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, false);
    }

    private static android.location.Location buildLocation(double lat, double lon) {
        android.location.Location l = new android.location.Location("petalcam");
        l.setLatitude(lat);
        l.setLongitude(lon);
        return l;
    }

    /** rotation degrees from CameraX into an exif orientation constant */
    private static int exifFromDegrees(int degrees) {
        switch (((degrees % 360) + 360) % 360) {
            case 90:
                return ExifInterface.ORIENTATION_ROTATE_90;
            case 180:
                return ExifInterface.ORIENTATION_ROTATE_180;
            case 270:
                return ExifInterface.ORIENTATION_ROTATE_270;
            default:
                return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /**
     * Stamps identity + capture info into the saved file. Written through a
     * descriptor so it works on the pending MediaStore row. Never fatal: a
     * viewer with no Exif is still better than a lost photo.
     */
    private static void writeExif(ContentResolver resolver, Uri uri,
            @Nullable Double lat, @Nullable Double lon, int exifOrientation,
            @Nullable Meta meta) {
        try (android.os.ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "rw")) {
            if (pfd == null) {
                return;
            }
            ExifInterface exif = new ExifInterface(pfd.getFileDescriptor());
            String now = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                    .format(new Date());
            exif.setAttribute(ExifInterface.TAG_DATETIME, now);
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now);
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, now);
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(exifOrientation));

            String make = Build.MANUFACTURER != null ? Build.MANUFACTURER : "";
            String model = Build.MODEL != null ? Build.MODEL : "";
            exif.setAttribute(ExifInterface.TAG_MAKE, make);
            exif.setAttribute(ExifInterface.TAG_MODEL, model);
            exif.setAttribute(ExifInterface.TAG_SOFTWARE,
                    meta != null && meta.software != null ? meta.software : SOFTWARE);

            if (meta != null) {
                if (meta.lensMake != null && !meta.lensMake.isEmpty()) {
                    exif.setAttribute(ExifInterface.TAG_LENS_MAKE, meta.lensMake);
                }
                if (meta.lensModel != null && !meta.lensModel.isEmpty()) {
                    exif.setAttribute(ExifInterface.TAG_LENS_MODEL, meta.lensModel);
                }
                exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, meta.describe());
                if (meta.focalLength > 0f) {
                    exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH,
                            rational(meta.focalLength, 100));
                }
                if (meta.focalLength35 > 0f) {
                    exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                            String.valueOf(Math.round(meta.focalLength35)));
                }
                if (meta.aperture > 0f) {
                    // TAG_APERTURE is not exposed by every androidx release;
                    // f-number is the modern tag anyway.
                    exif.setAttribute(ExifInterface.TAG_F_NUMBER,
                            rational(meta.aperture, 10));
                }
            }

            if (lat != null && lon != null) {
                exif.setGpsInfo(buildLocation(lat, lon));
            }
            exif.saveAttributes();
        } catch (Throwable t) {
            Log.w(TAG, "Could not write Exif metadata", t);
        }
    }

    /** Formats a float as an Exif rational without floating point drift. */
    private static String rational(float value, int scale) {
        int den = scale;
        int num = Math.round(value * den);
        while (den > 1 && num % 10 == 0) {
            num /= 10;
            den /= 10;
        }
        return num + "/" + den;
    }

    /** Subtle bottom-left petalOS mark. */
    private static Bitmap drawWatermark(Context context, Bitmap src) {
        Bitmap mutable = src.copy(Bitmap.Config.ARGB_8888, true);
        if (mutable == null) {
            return src;
        }
        Canvas canvas = new Canvas(mutable);
        float density = context.getResources().getDisplayMetrics().density;
        float pad = 24f * density;
        float iconSize = 26f * density;
        float textSize = 20f * density;

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setAlpha(210);
        text.setTextSize(textSize);
        text.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        text.setShadowLayer(6f * density, 0f, 2f * density, 0x99000000);

        String label = "petalOS";
        float textW = text.measureText(label);
        float total = iconSize + 10f * density + textW;
        float left = pad;
        float bottom = mutable.getHeight() - pad;
        float top = bottom - iconSize;

        Drawable logo = context.getDrawable(R.drawable.ic_cam_petal);
        if (logo != null) {
            logo.setBounds((int) left, (int) top, (int) (left + iconSize), (int) bottom);
            logo.setAlpha(220);
            logo.draw(canvas);
        }

        Paint.FontMetrics fm = text.getFontMetrics();
        float baseline = bottom - (iconSize - (fm.descent - fm.ascent)) / 2f - fm.ascent;
        canvas.drawText(label, left + iconSize + 10f * density, baseline, text);
        return mutable;
    }

    /** Copy a captured video file into DCIM/Camera and return its Uri. */
    @Nullable
    public static Uri importVideo(Context context, File source) {
        try {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, source.getName());
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_DCIM + File.separator + ALBUM);
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return null;
            }
            try (InputStream in = new java.io.FileInputStream(source);
                 OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    return null;
                }
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return uri;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to import video", t);
            return null;
        }
    }

    /** Video output values for a MediaStore recording. */
    public static ContentValues videoValues(String name) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_DCIM + File.separator + ALBUM);
        values.put(MediaStore.Video.Media.DESCRIPTION, SOFTWARE);
        values.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis());
        return values;
    }
}
