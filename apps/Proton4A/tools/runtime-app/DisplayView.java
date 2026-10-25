package org.petalos.proton4a.runtime;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.io.IOException;

/** Displays the app-owned Xvfb framebuffer and forwards pointer events. */
final class DisplayView extends View {
    interface PointerListener { void input(String action, int x, int y); }

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect bounds = new Rect();
    private final File framebuffer;
    private final PointerListener pointer;
    private final Runnable onContent;
    private volatile boolean running = true;
    private boolean contentShown;
    private Bitmap bitmap;
    private boolean pointerDown;
    private long lastMove;

    DisplayView(Context context, File framebuffer, PointerListener pointer,
            Runnable onContent) {
        super(context);
        this.framebuffer = framebuffer;
        this.pointer = pointer;
        this.onContent = onContent;
        Thread reader = new Thread(() -> {
            while (running) {
                try {
                    XwdFramebuffer.Frame frame = XwdFramebuffer.read(framebuffer);
                    boolean hasContent = false;
                    int colored = 0;
                    for (int i = 0; i < frame.argb.length; i += 8) {
                        if ((frame.argb[i] & 0xFFFFFF) != 0 && ++colored >= 100) {
                            hasContent = true;
                            break;
                        }
                    }
                    final boolean ready = hasContent;
                    Bitmap next = XwdFramebuffer.toBitmap(frame);
                    post(() -> {
                        if (!running) { next.recycle(); return; }
                        Bitmap old = bitmap;
                        bitmap = next;
                        invalidate();
                        if (ready && !contentShown) {
                            contentShown = true;
                            onContent.run();
                        }
                        if (old != null) old.recycle();
                    });
                } catch (IOException ignored) {
                    // Xvfb may still be creating the framebuffer.
                }
                try { Thread.sleep(120); }
                catch (InterruptedException ignored) { return; }
            }
        }, "Proton4A-frames");
        reader.setDaemon(true);
        reader.start();
    }

    @Override protected void onDraw(Canvas canvas) {
        Bitmap frame = bitmap;
        if (frame == null) return;
        float scale = Math.min((float) getWidth() / frame.getWidth(),
                (float) getHeight() / frame.getHeight());
        int width = Math.round(frame.getWidth() * scale);
        int height = Math.round(frame.getHeight() * scale);
        bounds.set((getWidth() - width) / 2, (getHeight() - height) / 2,
                (getWidth() + width) / 2, (getHeight() + height) / 2);
        canvas.drawBitmap(frame, null, bounds, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        Bitmap frame = bitmap;
        if (frame == null || bounds.isEmpty()) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!bounds.contains((int) event.getX(), (int) event.getY())) return false;
            pointerDown = true;
        } else if (!pointerDown) return false;
        if (action == MotionEvent.ACTION_MOVE) {
            if (event.getEventTime() - lastMove < 50) return true;
            lastMove = event.getEventTime();
        }
        int x = Math.max(0, Math.min(frame.getWidth() - 1,
                (int) ((event.getX() - bounds.left) * frame.getWidth() / bounds.width())));
        int y = Math.max(0, Math.min(frame.getHeight() - 1,
                (int) ((event.getY() - bounds.top) * frame.getHeight() / bounds.height())));
        if (action == MotionEvent.ACTION_DOWN) pointer.input("down", x, y);
        else if (action == MotionEvent.ACTION_MOVE) pointer.input("move", x, y);
        else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            pointerDown = false;
            pointer.input("up", x, y);
        }
        return true;
    }

    void stop() {
        running = false;
        post(() -> {
            if (bitmap != null) { bitmap.recycle(); bitmap = null; }
        });
    }
}
