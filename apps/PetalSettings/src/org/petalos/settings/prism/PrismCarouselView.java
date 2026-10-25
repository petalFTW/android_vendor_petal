/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.settings.prism;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.media.AudioManager;
import android.os.Build;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Bundle;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

import java.util.ArrayList;
import java.util.List;

// Category browsing, plus the two hidden decks.
final class PrismCarouselView extends ViewGroup {

    interface Listener {
        void onFocus(Panels.Panel panel);

        void onOpen(Panels.Panel panel);

        void onTap(PanelCard card);
    }

    private final List<PanelCard> cards = new ArrayList<>();
    private final Listener listener;
    private final AudioManager audio;
    private final int touchSlop;
    private final float density;
    private int cardWidth = 220;
    private int cardHeight = 236;
    private float spacing = 176;
    private float dragUnit = 320;
    private ValueAnimator idle;
    private ValueAnimator snap;
    private VelocityTracker tracker;
    private float position;
    private float pendingTarget = Float.NaN;
    private float dragShift;
    private float downX;
    private float downY;
    private float dragStart;
    private boolean dragging;
    private boolean cartridges;
    private boolean nxe;
    private int lastFocus = Integer.MIN_VALUE;
    private long lastDetent;
    private Panels.Panel[] order;

    PrismCarouselView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        audio = context.getSystemService(AudioManager.class);
        density = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(true);
        order = Panels.ALL.toArray(new Panels.Panel[0]);
        for (Panels.Panel panel : order) {
            PanelCard card = new PanelCard(context, panel);
            int index = cards.size();
            card.setOnClickListener(view -> onCardClicked(index));
            addView(card);
            cards.add(card);
        }
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    Panels.Panel current() {
        return order[focusIndex()];
    }

    int focusIndex() {
        int n = order.length;
        return ((Math.round(position) % n) + n) % n;
    }

    void move(int delta) {
        float base = Float.isNaN(pendingTarget) ? Math.round(position) : pendingTarget;
        animateTo(base + delta);
    }

    void focusPanel(String id) {
        stopSnap();
        for (int i = 0; i < order.length; i++) {
            if (order[i].id.equals(id)) {
                position = i;
                pendingTarget = Float.NaN;
                render();
                return;
            }
        }
    }

    void setCartridges(boolean cartridges) {
        this.cartridges = cartridges;
        for (int i = 0; i < cards.size(); i++) cards.get(i).setCartridge(cartridges);
        requestLayout();
        if (cartridges) startIdle();
        else stopIdle();
        render();
        animate().cancel();
        setAlpha(1f);
        if (cartridges && ValueAnimator.areAnimatorsEnabled()) {
            setScaleX(0.955f);
            setScaleY(0.955f);
            animate().scaleX(1f).scaleY(1f)
                    .setDuration(420)
                    .setInterpolator(new OvershootInterpolator(0.38f))
                    .start();
        } else {
            setScaleX(1f);
            setScaleY(1f);
            setAlpha(1f);
        }
    }

    void setNxe(boolean enabled) {
        nxe = enabled;
        for (PanelCard card : cards) card.setNxe(enabled);
        requestLayout();
    }

    boolean cartridges() {
        return cartridges;
    }

    PanelCard card(String id) {
        for (PanelCard card : cards) if (card.panel().id.equals(id)) return card;
        return null;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int scene = Math.min(width, dp(440));
        boolean expressive = cartridges || nxe;
        cardWidth = Math.round(scene * (nxe ? .64f : cartridges ? .66f : .76f));
        int available = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
                ? dp(300) : MeasureSpec.getSize(heightSpec);
        // Cartridges keep their silhouette; normal settings don't need a giant square.
        cardHeight = Math.min(Math.round(cardWidth * (nxe ? .82f : cartridges ? 1.04f : .78f)),
                available - dp(18));
        cardHeight = Math.max(dp(150), cardHeight);
        spacing = scene * (expressive ? .46f : .62f);
        dragUnit = spacing;
        for (PanelCard card : cards) {
            card.measure(MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(cardHeight, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(width, resolveSize(cardHeight + dp(24), heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (PanelCard card : cards) {
            int left = (getWidth() - card.getMeasuredWidth()) / 2;
            int top = Math.max(dp(8), (getHeight() - card.getMeasuredHeight()) / 2);
            card.layout(left, top, left + card.getMeasuredWidth(),
                    top + card.getMeasuredHeight());
            card.setCameraDistance(density * 9000f);
        }
        render();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!nxe) return;
        PanelCard card = cards.get(focusIndex());
        float floor = card.getBottom();
        int layer = canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), 35);
        canvas.clipRect(0, floor + dp(6), getWidth(), getHeight());
        canvas.translate(0, floor + dp(6) + floor * .22f);
        canvas.scale(1f, -.22f);
        // Mirror the same faces and view matrices as the visible deck, including
        // perspective, scale and the current fractional drag position.
        for (int distance = cards.size(); distance >= 0; distance--) {
            for (int i = 0; i < cards.size(); i++) {
                PanelCard reflected = cards.get(i);
                if (reflected.getAlpha() == 0f
                        || Math.round(Math.abs(wrap(i - position))) != distance) continue;
                int saved = canvas.save();
                canvas.translate(reflected.getLeft(), reflected.getTop());
                canvas.concat(reflected.getMatrix());
                reflected.drawDashboardTile(canvas);
                canvas.restoreToCount(saved);
            }
        }
        canvas.restoreToCount(layer);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startIdle();
        render();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopIdle();
        stopSnap();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) startIdle();
        else stopIdle();
    }

    private void startIdle() {
        stopIdle();
        if (!cartridges || !isShown() || !ValueAnimator.areAnimatorsEnabled()) {
            render();
            return;
        }
        idle = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        idle.setDuration(7800);
        idle.setRepeatCount(ValueAnimator.INFINITE);
        idle.setInterpolator(new LinearInterpolator());
        idle.addUpdateListener(a -> render());
        idle.start();
    }

    private void stopIdle() {
        if (idle != null) idle.cancel();
        idle = null;
    }

    private void render() {
        // Child RenderNode transforms alone do not re-record this ViewGroup's
        // custom reflection drawing. Keep it in lockstep with every deck frame.
        if (nxe) invalidate();
        int n = cards.size();
        float time = idle == null ? 0f : (float) idle.getAnimatedValue();
        int focus = focusIndex();
        for (int i = 0; i < n; i++) {
            PanelCard card = cards.get(i);
            float offset = wrap(i - position);
            float abs = Math.abs(offset);
            float floatY = cartridges
                    ? dp(11) * (0.5f + 0.5f * (float) Math.sin(time * 2f + i * 0.85f))
                    : 0f;

            // A sliver of the next category keeps the swipe discoverable.
            float travel = nxe ? Math.signum(offset) * getWidth()
                    * (Math.min(abs, 1f) * 0.40f + Math.max(0f, abs - 1f) * 0.14f)
                    - getWidth() * 0.12f : offset * spacing;
            card.setTranslationX(travel + dragShift * Math.min(abs, 1.5f) * 0.45f);
            card.setTranslationY(-floatY);
            card.setRotationY(nxe ? -Math.signum(offset) * Math.min(abs, 1f) * 24f : 0f);
            card.setTranslationZ(dp(3) * (n - Math.min(abs, n)));
            card.setRotation(cartridges
                    ? clamp((float) Math.sin(time + i) * 1.5f, -2f, 2f) : 0f);
            float scale = nxe ? (float) Math.pow(0.77f, abs)
                    : 1f - Math.min(abs, 2f) * (cartridges ? .14f : .06f);
            card.setScaleX(scale);
            card.setScaleY(scale);
            card.setSelected(i == focus);
            card.setCarouselFocus(Math.max(0f, 1f - abs));
            card.setAlpha(abs > (nxe ? 4.1f : 1.8f) ? 0f : 1f);
            if (!nxe && !cartridges && abs <= 1.8f) {
                card.setAlpha(1f - Math.min(abs, 1f) * .35f);
            }
            boolean far = abs > 1.02f;
            card.setFocusable(!far);
            card.setClickable(!far);
            card.setImportantForAccessibility(far
                    ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    : IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
        if (focus != lastFocus) {
            long now = android.os.SystemClock.uptimeMillis();
            if (lastFocus != Integer.MIN_VALUE && (dragging || snap != null)
                    && now - lastDetent >= 80) {
                performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                if (cartridges || nxe) playDetentClick();
                lastDetent = now;
            }
            lastFocus = focus;
            for (int distance = n; distance >= 0; distance--) {
                for (int i = 0; i < n; i++) {
                    if (Math.round(Math.abs(wrap(i - position))) == distance) {
                        cards.get(i).bringToFront();
                    }
                }
            }
            listener.onFocus(order[focus]);
        }
    }

    private void playDetentClick() {
        // The volume overload permits a tiny wheel tick, but unlike View's
        // click helper it needs an explicit check of the user's touch-sound setting.
        if (!isSoundEffectsEnabled() || audio == null
                || audio.getRingerMode() != AudioManager.RINGER_MODE_NORMAL
                || Settings.System.getInt(getContext().getContentResolver(),
                        Settings.System.SOUND_EFFECTS_ENABLED, 0) == 0) return;
        audio.playSoundEffect(AudioManager.FX_KEY_CLICK, 0.18f);
    }

    private int mod(int value, int n) {
        return ((value % n) + n) % n;
    }

    private float wrap(float value) {
        int n = cards.size();
        float half = n / 2f;
        while (value > half) value -= n;
        while (value < -half) value += n;
        return value;
    }

    private void animateTo(float target) {
        animateTo(target, 0f);
    }

    private void animateTo(float target, float velocity) {
        stopSnap();
        pendingTarget = target;
        if (!ValueAnimator.areAnimatorsEnabled()) {
            position = Math.round(target);
            pendingTarget = position;
            dragShift = 0f;
            render();
            return;
        }
        final float shiftAtStart = dragShift;
        if (!cartridges && !nxe) {
            final float start = position;
            snap = ValueAnimator.ofFloat(0f, 1f);
            snap.setDuration(240);
            snap.setInterpolator(new android.view.animation.PathInterpolator(.2f, 0f, .2f, 1f));
            snap.addUpdateListener(a -> {
                float progress = (float) a.getAnimatedValue();
                position = start + (target - start) * progress;
                dragShift = shiftAtStart * (1f - progress);
                render();
            });
        } else {
            final float displacement = position - target;
            final float initialVelocity = clamp(-velocity / dragUnit, -12f, 12f);
            // The eggs keep their springy release.
            final float damping = 10.5f;
            final float frequency = (float) Math.sqrt(230f - damping * damping);
            final float sine = (initialVelocity + damping * displacement) / frequency;
            snap = ValueAnimator.ofFloat(0f, 1.1f);
            snap.setDuration(1100);
            snap.setInterpolator(new LinearInterpolator());
            snap.addUpdateListener(a -> {
                float seconds = (float) a.getAnimatedValue();
                float decay = (float) Math.exp(-damping * seconds);
                position = target + decay * (displacement * (float) Math.cos(frequency * seconds)
                        + sine * (float) Math.sin(frequency * seconds));
                dragShift = shiftAtStart * decay;
                render();
            });
        }
        snap.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                snap = null;
                int n = cards.size();
                position = ((Math.round(position) % n) + n) % n;
                pendingTarget = position;
                dragShift = 0f;
                render();
            }
        });
        snap.start();
    }

    private void stopSnap() {
        if (snap != null) {
            // cancel also ends it; old callbacks mustn't round the new drag.
            snap.removeAllListeners();
            snap.removeAllUpdateListeners();
            snap.cancel();
            snap = null;
        }
    }

    private void onCardClicked(int index) {
        performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
        int diff = Math.round(wrap(index - position));
        if (diff != 0) {
            animateTo(position + diff);
            return;
        }
        listener.onTap(cards.get(index));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (cards.isEmpty()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                stopSnap();
                pendingTarget = Float.NaN;
                dragShift = 0f;
                downX = event.getX();
                downY = event.getY();
                dragStart = position;
                dragging = false;
                if (tracker == null) {
                    tracker = VelocityTracker.obtain();
                    tracker.addMovement(event);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (tracker != null) tracker.addMovement(event);
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (!dragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                    startDragging();
                    android.view.ViewParent parent = getParent();
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) {
                    float pull = -dx / dragUnit;
                    // Magnetic wells around whole cards. A slow drag feels a
                    // stronger pull because the finger isn't throwing the deck.
                    float raw = dragStart + pull;
                    float dragSpeed = 0f;
                    if (tracker != null) {
                        tracker.computeCurrentVelocity(1000);
                        dragSpeed = Math.abs(tracker.getXVelocity()) / dragUnit;
                    }
                    float strength = clamp(1.7f - dragSpeed * 0.30f, 0.7f, 1.7f);
                    position = raw - 0.065f * strength * (float) Math.sin(raw * Math.PI * 2);
                    dragShift = clamp(-dx * 0.07f, -dp(26), dp(26));
                    render();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    float velocity = 0f;
                    if (tracker != null) {
                        tracker.addMovement(event);
                        tracker.computeCurrentVelocity(1000,
                                ViewConfiguration.get(getContext()).getScaledMaximumFlingVelocity());
                        velocity = tracker.getXVelocity();
                    }
                    stopTracker();
                    float speed = clamp(-velocity / dragUnit, -12f, 12f);
                    int start = Math.round(dragStart);
                    int target;
                    if (Math.abs(speed) < 1.2f) {
                        // Slow release: the card closest to the middle right now
                        // wins, so a gentle nudge either stays put or completes
                        // the step instead of being rounded from the start point.
                        target = Math.round(clamp(position, start - 3f, start + 3f));
                    } else {
                        float projected = position + speed * 0.20f;
                        target = Math.round(clamp(projected, start - 3f, start + 3f));
                    }
                    animateTo(target, velocity);
                    dragging = false;
                } else {
                    stopTracker();
                    // child clicks normally handle taps; this covers gaps
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                stopTracker();
                if (dragging) animateTo(Math.round(position), 0f);
                dragging = false;
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void startDragging() {
        if (dragging) return;
        dragging = true;
        performHapticFeedback(android.view.HapticFeedbackConstants.GESTURE_START);
        lastDetent = android.os.SystemClock.uptimeMillis();
    }

    private void stopTracker() {
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            stopSnap();
            pendingTarget = Float.NaN;
            downX = event.getX();
            downY = event.getY();
            dragStart = position;
            dragging = false;
            stopTracker();
            tracker = VelocityTracker.obtain();
            tracker.addMovement(event);
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float dx = Math.abs(event.getX() - downX);
            float dy = Math.abs(event.getY() - downY);
            if (dx > touchSlop && dx > dy * 1.2f) {
                stopSnap();
                startDragging();
                if (tracker != null) tracker.addMovement(event);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }
        if ((event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                && !dragging) {
            stopTracker();
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            move(keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setScrollable(true);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            move(action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1);
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(float value) {
        return Math.round(value * density);
    }
}
