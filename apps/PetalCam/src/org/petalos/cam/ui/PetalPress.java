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

package org.petalos.cam.ui;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * Attaches the island's springy liquid press to any view. On touch the view
 * squishes out (wider, shorter) and on release it overshoots back, driven by
 * the same {@link PetalSpring} the rest of PetalCam moves on. The touch
 * listener stays passive so the view keeps its own click/ripple handling.
 */
public final class PetalPress {

    private static final float STIFFNESS = 520f;
    private static final float DAMPING = 0.45f;
    private static final float SCALE_IN = 1.16f;
    private static final float SQUISH = 0.82f;

    private PetalPress() {
    }

    public static void attach(View view) {
        attach(view, SCALE_IN, SQUISH);
    }

    public static void attach(View view, float scaleIn, float squish) {
        if (view == null) {
            return;
        }
        final Driver driver = new Driver(view, scaleIn, squish);
        view.setOnTouchListener((v, event) -> {
            driver.onTouch(event);
            // returning false keeps ripple + click handling on the view itself
            return false;
        });
    }

    private static final class Driver {

        private final View mView;
        private final float mScaleIn;
        private final float mSquish;
        private final PetalSpring mScaleX = new PetalSpring(1f);
        private final PetalSpring mScaleY = new PetalSpring(1f);

        private boolean mTicking;
        private long mLastFrame;

        Driver(View view, float scaleIn, float squish) {
            mView = view;
            mScaleIn = scaleIn;
            mSquish = squish;
        }

        void onTouch(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mScaleX.set(mScaleIn);
                    mScaleY.set(mSquish);
                    start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mScaleX.set(1f);
                    mScaleY.set(1f);
                    start();
                    break;
                default:
                    break;
            }
        }

        private void start() {
            if (mTicking) {
                return;
            }
            mTicking = true;
            mLastFrame = SystemClock.uptimeMillis();
            mView.postOnAnimation(mTicker);
        }

        private final Runnable mTicker = new Runnable() {
            @Override
            public void run() {
                long now = SystemClock.uptimeMillis();
                float dt = Math.min(0.05f, (now - mLastFrame) / 1000f);
                mLastFrame = now;
                mScaleX.step(dt, STIFFNESS, DAMPING);
                mScaleY.step(dt, STIFFNESS, DAMPING);
                mView.setScaleX(mScaleX.value);
                mView.setScaleY(mScaleY.value);
                if (!mScaleX.resting() || !mScaleY.resting()) {
                    mView.postOnAnimation(this);
                } else {
                    mTicking = false;
                    mView.setScaleX(1f);
                    mView.setScaleY(1f);
                }
            }
        };
    }
}
