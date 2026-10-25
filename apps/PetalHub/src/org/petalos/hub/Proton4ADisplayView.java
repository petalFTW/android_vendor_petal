/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.petalos.hub;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import org.petalos.proton4a.ProtonRunner;
import org.petalos.proton4a.XwdFramebuffer;

import java.io.File;
import java.io.IOException;

/** Renders the Xvfb framebuffer mirrored to disk by the runtime. */
public class Proton4ADisplayView extends View {
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect destination = new Rect();
    private volatile File source;
    private Bitmap bitmap;
    private Thread worker;
    private volatile boolean running;
    private Runnable onFrameAvailable;
    private long lastMove;
    private boolean pointerDown;

    public Proton4ADisplayView(Context context) {
        super(context);
    }

    public Proton4ADisplayView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setSource(File framebuffer) {
        source = framebuffer;
        if (framebuffer != null && worker == null) {
            startWorker();
        }
    }

    public void setOnFrameAvailable(Runnable listener) {
        onFrameAvailable = listener;
    }

    private void startWorker() {
        running = true;
        worker = new Thread(() -> {
            while (running) {
                try {
                    File file = source;
                    if (file != null && file.isFile()) {
                        XwdFramebuffer.Frame frame = XwdFramebuffer.read(file);
                        final Bitmap decoded = XwdFramebuffer.toBitmap(frame);
                        post(() -> {
                            if (!running) {
                                decoded.recycle();
                                return;
                            }
                            Bitmap previous = bitmap;
                            bitmap = decoded;
                            if (previous != null) previous.recycle();
                            invalidate();
                            if (onFrameAvailable != null) {
                                Runnable listener = onFrameAvailable;
                                onFrameAvailable = null;
                                listener.run();
                            }
                        });
                    }
                    Thread.sleep(50);
                } catch (IOException exception) {
                    sleep();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "Proton4A-display");
        worker.setDaemon(true);
        worker.start();
    }

    private void sleep() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Bitmap frame = bitmap;
        if (frame == null) return;
        float scale = Math.min((float) getWidth() / frame.getWidth(),
                (float) getHeight() / frame.getHeight());
        int width = (int) (frame.getWidth() * scale);
        int height = (int) (frame.getHeight() * scale);
        int left = (getWidth() - width) / 2;
        int top = (getHeight() - height) / 2;
        destination.set(left, top, left + width, top + height);
        canvas.drawBitmap(frame, null, destination, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        Bitmap frame = bitmap;
        if (frame == null || destination.isEmpty()) {
            return false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (!destination.contains((int) event.getX(), (int) event.getY())) return false;
            pointerDown = true;
        } else if (!pointerDown) {
            return false;
        }
        int x = Math.max(0, Math.min(frame.getWidth() - 1,
                (int) ((event.getX() - destination.left) * frame.getWidth()
                        / destination.width())));
        int y = Math.max(0, Math.min(frame.getHeight() - 1,
                (int) ((event.getY() - destination.top) * frame.getHeight()
                        / destination.height())));
        String action;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: action = "down"; break;
            case MotionEvent.ACTION_MOVE:
                if (event.getEventTime() - lastMove < 50) return true;
                lastMove = event.getEventTime();
                action = "move";
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pointerDown = false;
                action = "up";
                break;
            default: return true;
        }
        ProtonRunner.input(getContext(), action, Integer.toString(x), Integer.toString(y));
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        if (bitmap != null) {
            bitmap.recycle();
            bitmap = null;
        }
        super.onDetachedFromWindow();
    }
}
