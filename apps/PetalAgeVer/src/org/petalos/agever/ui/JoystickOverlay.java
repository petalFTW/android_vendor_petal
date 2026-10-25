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
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.petalos.agever.R;
import org.petalos.agever.Prefs;
import org.petalos.agever.StageService;
import org.petalos.agever.core.Expression;

/**
 * Floating joystick shown over whatever app is doing the age check. The user
 * can steer the avatar while a verification screen is in the foreground.
 */
public final class JoystickOverlay {

    /** Expression buttons sit under the sticks and are held, not toggled. */
    public interface ExpressionListener {
        void onExpression(Expression expression, boolean on);
    }

    public interface SpeedListener {
        void onSpeedChanged(float speed);
    }

    private final Context mContext;
    private final WindowManager mWindowManager;

    private View mRoot;
    private WindowManager.LayoutParams mParams;
    private JoystickView mMoveStick;
    private JoystickView mTurnStick;
    private JoystickView mDepthStick;
    private ExpressionListener mExpressionListener;
    private SpeedListener mSpeedListener;
    private int mExpressionMask = -1;

    public JoystickOverlay(@NonNull Context context) {
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean isShowing() {
        return mRoot != null;
    }

    /** Must be called on the main thread. */
    public void show(@Nullable JoystickView.Listener move,
            @Nullable JoystickView.Listener turn,
            @Nullable JoystickView.Listener depth,
            @Nullable ExpressionListener expression,
            @Nullable SpeedListener speed,
            float initialSpeed) {
        mExpressionListener = expression;
        mSpeedListener = speed;
        if (mRoot != null) {
            mMoveStick.setListener(move);
            mTurnStick.setListener(turn);
            mDepthStick.setListener(depth);
            return;
        }
        mRoot = LayoutInflater.from(mContext).inflate(R.layout.overlay_joystick, null);
        mMoveStick = mRoot.findViewById(R.id.joystick_move);
        mTurnStick = mRoot.findViewById(R.id.joystick_turn);
        mDepthStick = mRoot.findViewById(R.id.joystick_distance);
        mMoveStick.setListener(move);
        mTurnStick.setListener(turn);
        mDepthStick.setListener(depth);
        bindExpression(R.id.expression_smile, Expression.SMILE);
        bindExpression(R.id.expression_frown, Expression.FROWN);
        bindExpression(R.id.expression_open, Expression.MOUTH_OPEN);
        bindExpression(R.id.expression_brow_up, Expression.BROW_UP);
        bindExpression(R.id.expression_brow_down, Expression.BROW_DOWN);
        bindExpression(R.id.expression_surprise, Expression.SURPRISE);
        bindExpression(R.id.expression_neutral, Expression.NEUTRAL);
        int target = Math.round(48 * mContext.getResources().getDisplayMetrics().density);
        for (int id : new int[]{R.id.expression_smile, R.id.expression_frown, R.id.expression_open,
                R.id.expression_brow_up, R.id.expression_brow_down, R.id.expression_surprise,
                R.id.expression_neutral}) mRoot.findViewById(id).setMinimumHeight(target);
        SeekBar speedSlider = mRoot.findViewById(R.id.expression_speed);
        speedSlider.setProgress(Math.round(initialSpeed * 100f));
        speedSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && mSpeedListener != null) {
                    mSpeedListener.onSpeedChanged(progress / 100f);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) { }

            @Override
            public void onStopTrackingTouch(SeekBar bar) { }
        });

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        mParams.gravity = Gravity.TOP | Gravity.START;
        mParams.setTitle("IDHelper controls");
        mParams.x = Math.max(0, Prefs.get().getPanelX());
        mParams.y = Math.max(0, Prefs.get().getPanelY());
        mRoot.findViewById(R.id.panel_toggle).setOnClickListener(v -> {
            Prefs.get().setPanelCollapsed(!Prefs.get().getPanelCollapsed());
            updateCollapsed();
        });
        mRoot.findViewById(R.id.panel_stop).setOnClickListener(v -> mContext.startService(
                new android.content.Intent(mContext, StageService.class).setAction(StageService.ACTION_STOP)));
        mRoot.findViewById(R.id.panel_drag).setOnLongClickListener(v -> {
            mParams.x = 0; mParams.y = 0; clampPosition();
            Prefs.get().setPanelPosition(mParams.x, mParams.y);
            return true;
        });
        updateCollapsed();

        installDrag();
        try {
            mWindowManager.addView(mRoot, mParams);
            mRoot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (mRoot != null) clampPosition();
            });
        } catch (RuntimeException e) {
            mRoot = null;
            mMoveStick = null;
            mTurnStick = null;
            mDepthStick = null;
            Toast.makeText(mContext, R.string.status_permission_overlay, Toast.LENGTH_LONG).show();
        }
    }

    public void setExpressionMask(int mask) {
        if (mRoot == null) return;
        if (mExpressionMask != mask) {
            clearExpressionSelection();
            if (mExpressionListener != null) mExpressionListener.onExpression(Expression.NEUTRAL, true);
            mExpressionMask = mask;
        }
        boolean available = mask != 0;
        mRoot.findViewById(R.id.expression_controls).setVisibility(available ? View.VISIBLE : View.GONE);
        mRoot.findViewById(R.id.expression_unavailable).setVisibility(available ? View.GONE : View.VISIBLE);
        int[] ids = {R.id.expression_neutral, R.id.expression_smile, R.id.expression_frown,
                R.id.expression_open, R.id.expression_brow_up, R.id.expression_brow_down, R.id.expression_surprise};
        for (Expression expression : Expression.values()) {
            mRoot.findViewById(ids[expression.ordinal()]).setVisibility(
                    (mask & (1 << expression.ordinal())) != 0 ? View.VISIBLE : View.GONE);
        }
    }

    private void updateCollapsed() {
        mRoot.findViewById(R.id.controls_body).setVisibility(
                Prefs.get().getPanelCollapsed() ? View.GONE : View.VISIBLE);
        ((android.widget.Button) mRoot.findViewById(R.id.panel_toggle)).setText(
                Prefs.get().getPanelCollapsed() ? R.string.panel_expand : R.string.panel_collapse);
    }

    private void clampPosition() {
        if (mRoot == null || mParams == null || !mRoot.isAttachedToWindow()) return;
        android.view.WindowMetrics metrics = mWindowManager.getCurrentWindowMetrics();
        android.graphics.Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
        int width = metrics.getBounds().width() - insets.left - insets.right;
        int height = metrics.getBounds().height() - insets.top - insets.bottom;
        int x = Math.max(0, Math.min(mParams.x, Math.max(0, width - mRoot.getWidth())));
        int y = Math.max(0, Math.min(mParams.y, Math.max(0, height - mRoot.getHeight())));
        if (x != mParams.x || y != mParams.y) {
            mParams.x = x; mParams.y = y;
            mWindowManager.updateViewLayout(mRoot, mParams);
        }
    }

    /** Must be called on the main thread. */
    public void hide() {
        if (mRoot == null) {
            return;
        }
        Prefs.get().setPanelPosition(mParams.x, mParams.y);
        if (mExpressionListener != null) mExpressionListener.onExpression(Expression.NEUTRAL, true);
        try {
            mWindowManager.removeView(mRoot);
        } catch (RuntimeException ignored) {
            // already detached
        }
        mRoot = null;
        mMoveStick = null;
        mTurnStick = null;
        mDepthStick = null;
        mExpressionListener = null;
        mSpeedListener = null;
        mParams = null;
        mExpressionMask = -1;
    }

    /** Hold to emote; NEUTRAL just clears everything on the way down. */
    private void bindExpression(int id, final Expression expression) {
        final View view = mRoot.findViewById(id);
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(v -> {
            if (expression == Expression.NEUTRAL) clearExpressionSelection();
            boolean active = expression == Expression.NEUTRAL || !v.isSelected();
            v.setSelected(active && expression != Expression.NEUTRAL);
            if (mExpressionListener != null) mExpressionListener.onExpression(expression, active);
        });
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (expression == Expression.NEUTRAL) clearExpressionSelection();
                    v.setPressed(true);
                    v.setSelected(expression != Expression.NEUTRAL);
                    if (mExpressionListener != null) {
                        mExpressionListener.onExpression(expression, true);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    v.setSelected(false);
                    if (expression != Expression.NEUTRAL && mExpressionListener != null) {
                        mExpressionListener.onExpression(expression, false);
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    private void clearExpressionSelection() {
        if (mRoot == null) return;
        for (int id : new int[]{R.id.expression_smile, R.id.expression_frown, R.id.expression_open,
                R.id.expression_brow_up, R.id.expression_brow_down, R.id.expression_surprise}) {
            mRoot.findViewById(id).setSelected(false);
        }
    }

    private void installDrag() {
        mRoot.findViewById(R.id.panel_drag).setOnTouchListener(new View.OnTouchListener() {
            private int mDownX;
            private int mDownY;
            private float mStartX;
            private float mStartY;
            private final Runnable mReset = () -> {
                if (mRoot != null) mRoot.findViewById(R.id.panel_drag).performLongClick();
            };

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        mDownX = mParams.x;
                        mDownY = mParams.y;
                        mStartX = event.getRawX();
                        mStartY = event.getRawY();
                        v.postDelayed(mReset, android.view.ViewConfiguration.getLongPressTimeout());
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.hypot(event.getRawX() - mStartX, event.getRawY() - mStartY)
                                > android.view.ViewConfiguration.get(mContext).getScaledTouchSlop()) {
                            v.removeCallbacks(mReset);
                        }
                        mParams.x = mDownX + (int) (event.getRawX() - mStartX);
                        mParams.y = mDownY + (int) (event.getRawY() - mStartY);
                        clampPosition();
                        mWindowManager.updateViewLayout(mRoot, mParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.removeCallbacks(mReset);
                        clampPosition();
                        Prefs.get().setPanelPosition(mParams.x, mParams.y);
                        v.performClick();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }
}
