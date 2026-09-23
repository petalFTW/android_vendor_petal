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

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * Realtime QR / barcode decoder. Runs ZXing directly on the camera's Y plane
 * (the luminance buffer already costs nothing to read), so it stays on-device
 * and needs no Play Services.
 */
public class QrAnalyzer implements ImageAnalysis.Analyzer {

    public interface Listener {
        void onQrDetected(@NonNull String text);
    }

    // don't hammer the decoder; ~4 scans a second is plenty for a held phone
    private static final long MIN_INTERVAL_MS = 250L;
    // don't re-announce the same code for a while after showing it
    private static final long DEDUPE_MS = 5000L;
    // a code has to show up on this many frames before we believe it
    private static final int CONFIRM_HITS = 2;

    private final Listener mListener;
    private final MultiFormatReader mReader = new MultiFormatReader();

    private long mLastScan;
    private String mLastText;
    private long mLastTextAt;
    private String mPendingText;
    private int mPendingHits;

    public QrAnalyzer(@NonNull Listener listener) {
        mListener = listener;
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        // no TRY_HARDER, it hallucinates codes out of pure noise. dropped the
        // checksum-less 1d formats (code39/93/itf) that were spitting random numbers
        hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.of(
                BarcodeFormat.QR_CODE,
                BarcodeFormat.DATA_MATRIX,
                BarcodeFormat.AZTEC,
                BarcodeFormat.PDF_417,
                BarcodeFormat.CODE_128,
                BarcodeFormat.EAN_13,
                BarcodeFormat.EAN_8,
                BarcodeFormat.UPC_A,
                BarcodeFormat.UPC_E));
        mReader.setHints(hints);
    }

    /** Forget the dedupe window so the same code can pop again immediately. */
    public void reset() {
        mLastText = null;
        mLastTextAt = 0L;
        mPendingText = null;
        mPendingHits = 0;
    }

    @Override
    public void analyze(@NonNull ImageProxy image) {
        try {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - mLastScan < MIN_INTERVAL_MS) {
                return;
            }
            mLastScan = now;

            final int width = image.getWidth();
            final int height = image.getHeight();
            if (width < 16 || height < 16) {
                return;
            }

            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            final int rowStride = plane.getRowStride();
            final int pixelStride = plane.getPixelStride();

            byte[] luminance = new byte[width * height];
            if (pixelStride == 1 && rowStride == width) {
                buffer.rewind();
                buffer.get(luminance);
            } else {
                buffer.rewind();
                for (int y = 0; y < height; y++) {
                    int row = y * rowStride;
                    int out = y * width;
                    for (int x = 0; x < width; x++) {
                        luminance[out + x] = buffer.get(row + x * pixelStride);
                    }
                }
            }

            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    luminance, width, height, 0, 0, width, height, false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = mReader.decodeWithState(bitmap);
            String text = result != null ? result.getText() : null;
            if (text == null || text.isEmpty()) {
                // nothing this frame, throw the candidate away
                mPendingText = null;
                mPendingHits = 0;
                return;
            }
            if (text.equals(mLastText) && now - mLastTextAt < DEDUPE_MS) {
                return;
            }
            // lone noise frame should never be enough to pop a code
            if (text.equals(mPendingText)) {
                mPendingHits++;
            } else {
                mPendingText = text;
                mPendingHits = 1;
            }
            if (mPendingHits < CONFIRM_HITS) {
                return;
            }
            mLastText = text;
            mLastTextAt = now;
            mPendingText = null;
            mPendingHits = 0;
            mListener.onQrDetected(text);
        } catch (Throwable ignored) {
            // a bad frame is never a crash
        } finally {
            mReader.reset();
            image.close();
        }
    }
}
