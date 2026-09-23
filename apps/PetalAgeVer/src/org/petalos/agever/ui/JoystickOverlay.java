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

package org.petalos.agever.ui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.petalos.agever.R;

/**
 * Floating joystick shown over whatever app is doing the age check. The user
 * can steer the avatar while a verification screen is in the foreground.
 */
public final class JoystickOverlay {

    private final Context mContext;
    private final WindowManager mWindowManager;

    private View mRoot;
    private WindowManager.LayoutParams mParams;
    private JoystickView mMoveStick;
    private JoystickView mTurnStick;

    public JoystickOverlay(@NonNull Context context) {
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean isShowing() {
        return mRoot != null;
    }

    /** Must be called on the main thread. */
    public void show(@Nullable JoystickView.Listener move,
            @Nullable JoystickView.Listener turn) {
        if (mRoot != null) {
            mMoveStick.setListener(move);
            mTurnStick.setListener(turn);
            return;
        }
        mRoot = LayoutInflater.from(mContext).inflate(R.layout.overlay_joystick, null);
        mMoveStick = mRoot.findViewById(R.id.joystick_move);
        mTurnStick = mRoot.findViewById(R.id.joystick_turn);
        mMoveStick.setListener(move);
        mTurnStick.setListener(turn);

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        mParams.gravity = Gravity.TOP | Gravity.START;
        mParams.x = 32;
        mParams.y = 320;

        installDrag();
        try {
            mWindowManager.addView(mRoot, mParams);
        } catch (RuntimeException e) {
            mRoot = null;
            mMoveStick = null;
            mTurnStick = null;
            Toast.makeText(mContext, R.string.status_permission_overlay, Toast.LENGTH_LONG).show();
        }
    }

    /** Must be called on the main thread. */
    public void hide() {
        if (mRoot == null) {
            return;
        }
        try {
            mWindowManager.removeView(mRoot);
        } catch (RuntimeException ignored) {
            // already detached
        }
        mRoot = null;
        mMoveStick = null;
        mTurnStick = null;
        mParams = null;
    }

    private void installDrag() {
        mRoot.setOnTouchListener(new View.OnTouchListener() {
            private int mDownX;
            private int mDownY;
            private float mStartX;
            private float mStartY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        mDownX = mParams.x;
                        mDownY = mParams.y;
                        mStartX = event.getRawX();
                        mStartY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        mParams.x = mDownX + (int) (event.getRawX() - mStartX);
                        mParams.y = mDownY + (int) (event.getRawY() - mStartY);
                        mWindowManager.updateViewLayout(mRoot, mParams);
                        return true;
                    default:
                        return false;
                }
            }
        });
    }
}
