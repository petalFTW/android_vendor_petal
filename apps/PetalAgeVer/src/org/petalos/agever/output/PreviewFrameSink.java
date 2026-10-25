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

package org.petalos.agever.output;

import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// SurfaceView output only, this doesn't publish a camera
public final class PreviewFrameSink implements FrameSink, SurfaceHolder.Callback {

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private SurfaceView mView;
    private Callback mCallback;
    private int mGeneration;

    public void attach(@Nullable SurfaceView view) {
        if (mView == view) {
            return;
        }
        ++mGeneration;
        if (mView != null) {
            mView.getHolder().removeCallback(this);
        }
        mView = view;
        if (mView == null) {
            notifyLost();
            return;
        }
        SurfaceHolder holder = mView.getHolder();
        holder.addCallback(this);
        holder.setFormat(PixelFormat.OPAQUE);
        Surface surface = holder.getSurface();
        if (surface != null && surface.isValid()) {
            notifyReady(surface);
        }
    }

    @Override
    public void start(@NonNull Callback callback) {
        ++mGeneration;
        mCallback = callback;
        // The surface may already be live if the view was attached first.
        SurfaceView view = mView;
        if (view != null) {
            Surface surface = view.getHolder().getSurface();
            if (surface != null && surface.isValid()) {
                notifyReady(surface);
            }
        }
    }

    @Override
    public void stop() {
        mCallback = null;
        attach(null);
    }

    @Override
    public boolean isActive() {
        SurfaceView view = mView;
        return view != null && view.getHolder().getSurface() != null
                && view.getHolder().getSurface().isValid();
    }

    @NonNull
    @Override
    public String name() {
        return "preview";
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        Surface surface = holder.getSurface();
        if (surface != null && surface.isValid()) {
            notifyReady(surface);
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        Surface surface = holder.getSurface();
        if (surface != null && surface.isValid()) {
            Callback cb = mCallback;
            int generation = mGeneration;
            mMainHandler.post(() -> {
                if (cb != null && cb == mCallback && generation == mGeneration) {
                    cb.onSurfaceReady(surface, width, height);
                }
            });
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        notifyLost();
    }

    private void notifyReady(Surface surface) {
        SurfaceView view = mView;
        int w = view == null ? 0 : view.getWidth();
        int h = view == null ? 0 : view.getHeight();
        Callback cb = mCallback;
        int generation = mGeneration;
        mMainHandler.post(() -> {
            if (cb != null && cb == mCallback && generation == mGeneration) {
                cb.onSurfaceReady(surface, w, h);
            }
        });
    }

    private void notifyLost() {
        Callback cb = mCallback;
        int generation = mGeneration;
        mMainHandler.post(() -> {
            if (cb != null && cb == mCallback && generation == mGeneration) {
                cb.onSurfaceLost();
            }
        });
    }
}
