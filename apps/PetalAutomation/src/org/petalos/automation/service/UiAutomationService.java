/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.automation.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.petalos.automation.engine.AutomationEngine;
import org.petalos.automation.model.Types;

/** borrows the user's accessibility toggle for taps/swipes/typing + app tracking. */
public final class UiAutomationService extends AccessibilityService {
    private static final String TAG = "PetalAutomation";
    private static UiAutomationService sInstance;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private String mForeground;
    private long mLastVolumeDown;
    private long mLastVolumeUp;

    public static UiAutomationService get() {
        return sInstance;
    }

    @Override
    public void onServiceConnected() {
        sInstance = this;
        Log.i(TAG, "accessibility up");
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getPackageName() == null) {
            return;
        }
        final String name = event.getPackageName().toString();
        if (name.equals(mForeground)) {
            return;
        }
        final String previous = mForeground;
        mForeground = name;

        final AutomationEngine.Event open = new AutomationEngine.Event();
        open.triggerType = Types.T_APP_OPEN;
        open.app = name;
        AutomationEngine.handle(this, open);

        if (previous != null) {
            final AutomationEngine.Event close = new AutomationEngine.Event();
            close.triggerType = Types.T_APP_CLOSE;
            close.app = previous;
            AutomationEngine.handle(this, close);
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        final long now = System.currentTimeMillis();
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (now - mLastVolumeDown < 2000) {
                fireButton("volume_down_double");
            }
            mLastVolumeDown = now;
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
            if (now - mLastVolumeUp < 2000) {
                fireButton("volume_up_double");
            }
            mLastVolumeUp = now;
        }
        // never eat the key, volume is still volume
        return false;
    }

    private void fireButton(String key) {
        final AutomationEngine.Event event = new AutomationEngine.Event();
        event.triggerType = Types.T_BUTTON;
        event.text = key;
        AutomationEngine.handle(this, event);
    }

    public static void tap(int x, int y) {
        final Path path = new Path();
        path.moveTo(x, y);
        dispatchStroke(path, 0, 50);
    }

    public static void swipe(int x, int y, int x2, int y2, int ms) {
        final Path path = new Path();
        path.moveTo(x, y);
        path.lineTo(x2, y2);
        dispatchStroke(path, 0, Math.max(1, ms));
    }

    private static void dispatchStroke(Path path, long start, long duration) {
        final UiAutomationService service = sInstance;
        if (service == null) {
            return;
        }
        final GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, start, duration))
                .build();
        MAIN.post(() -> service.dispatchGesture(gesture, null, null));
    }

    public static void typeText(String text) {
        final UiAutomationService service = sInstance;
        if (service == null || text == null) {
            return;
        }
        MAIN.post(() -> {
            final AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) {
                return;
            }
            AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus == null) {
                return;
            }
            final Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        });
    }
}
