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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SegmenterHelper implements AutoCloseable {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private OrtSession encoder;
    private OrtSession decoder;
    private OrtSession.Result encoded;
    private Bitmap encodedBase;
    private int inputWidth;
    private int inputHeight;

    public void ensureInitialized(Context context) throws Exception {
        if (encoder != null && decoder != null) return;
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(4);
            options.setInterOpNumThreads(1);
            options.setCPUArenaAllocator(false);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            if (encoder == null) encoder = environment.createSession(
                    modelFile(context, "mobile_sam_encoder.onnx").getAbsolutePath(), options);
            if (decoder == null) decoder = environment.createSession(
                    modelFile(context, "mobile_sam_decoder.onnx").getAbsolutePath(), options);
        }
    }

    private File modelFile(Context context, String asset) throws Exception {
        File directory = new File(context.getNoBackupFilesDir(), "mobilesam-0d3b4033");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new java.io.IOException("Cannot create model directory");
        }
        File target = new File(directory, asset);
        if (target.isFile()) return target;
        File staging = new File(directory, asset + ".tmp");
        try (InputStream input = context.getAssets().open(asset);
                FileOutputStream output = new FileOutputStream(staging)) {
            byte[] bytes = new byte[65536];
            int count;
            while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
            output.getFD().sync();
        }
        if (!staging.renameTo(target)) throw new java.io.IOException("Cannot save model");
        return target;
    }

    private void encode(Bitmap base) throws Exception {
        if (encoded != null && encodedBase == base) return;
        if (encoded != null) {
            encoded.close();
            encoded = null;
            encodedBase = null;
        }
        float scale = 1024f / Math.max(base.getWidth(), base.getHeight());
        inputWidth = Math.max(1, Math.round(base.getWidth() * scale));
        inputHeight = Math.max(1, Math.round(base.getHeight() * scale));
        Bitmap resized = Bitmap.createScaledBitmap(base, inputWidth, inputHeight, true);
        int[] pixels = new int[inputWidth * inputHeight];
        resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight);
        if (resized != base) resized.recycle();
        float[] rgb = new float[pixels.length * 3];
        for (int i = 0; i < pixels.length; i++) {
            rgb[i * 3] = Color.red(pixels[i]);
            rgb[i * 3 + 1] = Color.green(pixels[i]);
            rgb[i * 3 + 2] = Color.blue(pixels[i]);
        }
        long started = SystemClock.elapsedRealtime();
        try (OnnxTensor input = tensor(rgb, inputHeight, inputWidth, 3)) {
            encoded = encoder.run(java.util.Collections.singletonMap("input_image", input));
        }
        encodedBase = base;
        Log.d("DepthSegmenter", "MobileSAM encoder ms="
                + (SystemClock.elapsedRealtime() - started));
    }

    private OnnxTensor tensor(float[] data, long... shape) throws Exception {
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), shape);
    }

    public Bitmap cutout(Bitmap base, float x, float y) throws Exception {
        return cutout(base, OutlinePrompt.tap(x, y));
    }

    Bitmap cutout(Bitmap base, OutlinePrompt prompt) throws Exception {
        encode(base);
        long started = SystemClock.elapsedRealtime();
        float[] coordinates = prompt.points.clone();
        for (int i = 0; i < coordinates.length; i += 2) {
            coordinates[i] *= inputWidth;
            coordinates[i + 1] *= inputHeight;
        }
        try (OnnxTensor points = tensor(coordinates, 1, prompt.labels.length, 2);
                OnnxTensor labels = tensor(prompt.labels, 1, prompt.labels.length);
                OnnxTensor mask = tensor(new float[256 * 256], 1, 1, 256, 256);
                OnnxTensor hasMask = tensor(new float[]{0}, 1);
                OnnxTensor size = tensor(new float[]{inputHeight, inputWidth}, 2)) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("image_embeddings", (OnnxTensor) encoded.get(0));
            inputs.put("point_coords", points);
            inputs.put("point_labels", labels);
            inputs.put("mask_input", mask);
            inputs.put("has_mask_input", hasMask);
            inputs.put("orig_im_size", size);
            try (OrtSession.Result result = decoder.run(inputs)) {
                OnnxTensor masks = (OnnxTensor) result.get("masks").get();
                long[] shape = ((TensorInfo) masks.getInfo()).getShape();
                int count = (int) shape[1], height = (int) shape[2], width = (int) shape[3];
                FloatBuffer values = masks.getFloatBuffer();
                FloatBuffer quality = ((OnnxTensor) result.get("iou_predictions").get())
                        .getFloatBuffer();
                int selected = SamMask.select(values, quality, count, width, height, prompt);
                float[] confidence = new float[width * height];
                for (int i = 0; i < confidence.length; i++) {
                    float logit = values.get(selected * confidence.length + i);
                    confidence[i] = (float) (1 / (1 + Math.exp(-logit)));
                }
                byte[] alpha = MaskAlpha.dilated(MaskAlpha.fromConfidence(confidence),
                        width, height);
                Bitmap cutout = applyAlpha(base, alpha, width, height);
                Log.d("DepthSegmenter", "MobileSAM decoder ms="
                        + (SystemClock.elapsedRealtime() - started) + " candidate=" + selected
                        + " quality=" + quality.get(selected) + " prompts=" + prompt.labels.length);
                return cutout;
            }
        }
    }

    private Bitmap applyAlpha(Bitmap base, byte[] alpha, int width, int height) {
        int[] maskPixels = new int[alpha.length];
        for (int i = 0; i < alpha.length; i++) maskPixels[i] = (alpha[i] & 255) << 24;
        Bitmap mask = Bitmap.createBitmap(maskPixels, width, height, Bitmap.Config.ARGB_8888);
        Bitmap scaled = Bitmap.createScaledBitmap(mask, base.getWidth(), base.getHeight(), true);
        int[] pixels = new int[base.getWidth() * base.getHeight()];
        int[] opacity = new int[pixels.length];
        base.getPixels(pixels, 0, base.getWidth(), 0, 0, base.getWidth(), base.getHeight());
        scaled.getPixels(opacity, 0, base.getWidth(), 0, 0, base.getWidth(), base.getHeight());
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (pixels[i] & 0x00ffffff) | (opacity[i] & 0xff000000);
        }
        Bitmap result = Bitmap.createBitmap(pixels, base.getWidth(), base.getHeight(),
                Bitmap.Config.ARGB_8888);
        result.setHasAlpha(true);
        if (scaled != mask) scaled.recycle();
        mask.recycle();
        return result;
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    @Override
    public void close() {
        executor.execute(() -> {
            try {
                if (encoded != null) encoded.close();
                if (encoder != null) encoder.close();
                if (decoder != null) decoder.close();
            } catch (Exception e) {
                Log.w("DepthSegmenter", "Model cleanup failed", e);
            } finally {
                encoded = null;
                encodedBase = null;
                encoder = null;
                decoder = null;
            }
        });
        executor.shutdown();
    }
}
