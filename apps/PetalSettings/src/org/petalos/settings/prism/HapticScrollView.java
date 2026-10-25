/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.settings.prism;

import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.widget.ScrollView;

/** One short tick when a finger actually starts moving the scrollable content. */
final class HapticScrollView extends ScrollView {
    private boolean touching;
    private boolean ticked;

    HapticScrollView(Context context) {
        super(context);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touching = true;
            ticked = false;
        }
        try {
            return super.dispatchTouchEvent(event);
        } finally {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touching = false;
            }
        }
    }

    @Override
    protected void onScrollChanged(int left, int top, int oldLeft, int oldTop) {
        super.onScrollChanged(left, top, oldLeft, oldTop);
        if (touching && !ticked && top != oldTop) {
            performHapticFeedback(HapticFeedbackConstants.GESTURE_START);
            ticked = true;
        }
    }
}
