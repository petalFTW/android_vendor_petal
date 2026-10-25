/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.settings.prism;

import android.app.Activity;
import android.app.AlertDialog;
import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.BatteryManager;
import android.transition.ChangeBounds;
import android.transition.ChangeTransform;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.provider.Settings;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Set;

import org.petalos.config.DepthNotificationPlacement;
import org.petalos.config.PetalConfig;

public final class PrismSettingsActivity extends Activity {

    private static final String PREFS = "prism";
    private static final String KEY_EGG = "cartridges";
    private static final String KEY_NXE = "nxe_dashboard";
    private static final String KEY_DEPTH = "petal_depth_enabled";
    private static final String KEY_NOTIFICATION_START = PetalConfig.KEY_DEPTH_NOTIFICATION_START;
    private static final String KEY_NOTIFICATION_LAST = "notification_start_last";

    private PrismCarouselView carousel;
    private LinearLayout peekHost;
    private IndexRailView rail;
    private FrameLayout overlay;
    private FrameLayout root;
    private SharedPreferences prefs;
    private AudioManager audio;
    private int buildTaps;
    private int softwareTaps;
    private boolean egg;
    private boolean nxe;
    private LinearLayout peekCard;
    private String peekPanelId;
    private ValueAnimator peekTint;
    private NxeBackdrop dashboardBackdrop;
    private WallpaperBackdrop wallpaperBackdrop;
    private String petalBuild;
    private List<AppResult> appIndex;
    private boolean loadingApps;
    private String appQuery;
    private LinearLayout appResults;
    private Panels.Panel detailPanel;
    private boolean searching;
    private boolean showingAllSettings;
    private boolean detailFromAllSettings;
    private EditText searchInput;
    private ScrollView homeScroll;
    private AlertDialog writeDialog;
    private ScrollView detailScroll;
    private LinearLayout searchBar;
    private TextView searchLabel;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        egg = prefs.getBoolean(KEY_EGG, false);
        nxe = prefs.getBoolean(KEY_NXE, false);
        if (nxe) egg = false;
        petalBuild = readPetalBuild();
        audio = getSystemService(AudioManager.class);
        getWindow().setDecorFitsSystemWindows(false);

        root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.paper));
        wallpaperBackdrop = new WallpaperBackdrop(this);
        wallpaperBackdrop.setAlpha(0.12f);
        root.addView(wallpaperBackdrop, new FrameLayout.LayoutParams(-1, -1));
        dashboardBackdrop = new NxeBackdrop(this);
        dashboardBackdrop.setVisibility(nxe ? View.VISIBLE : View.GONE);
        root.addView(dashboardBackdrop, new FrameLayout.LayoutParams(-1, -1));
        buildContent();
        setContentView(root);
        updateBackdropVisibility();
        if (state != null) {
            carousel.focusPanel(state.getString("panel", "connect"));
            if (state.getBoolean("searching")) {
                openSearch();
                searchInput.setText(state.getString("query", ""));
            } else if (state.getBoolean("allSettings")) {
                openAllSettings();
            } else if (state.containsKey("detail")) {
                openDetail(Panels.byId(state.getString("detail")));
                detailFromAllSettings = state.getBoolean("detailFromAllSettings", false);
            }
        }
        root.requestApplyInsets();
    }

    private void buildContent() {
        ScrollView scroll = new HapticScrollView(this);
        homeScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(dp(20), dp(16), dp(20), dp(10));
        scroll.addView(column);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            scroll.setPadding(bars.left, bars.top + dp(4), bars.right, bars.bottom + dp(8));
            return insets;
        });

        // Keep the search bar easy to find, without a bloody billboard above it.
        LinearLayout search = new LinearLayout(this);
        searchBar = search;
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setBackground(rounded(getColor(R.color.card), getColor(R.color.line), normalRadius(28, 14)));
        search.setPadding(dp(12), 0, dp(12), 0);
        search.setClickable(true);
        search.setFocusable(true);
        search.setMinimumHeight(dp(48));
        feedback(search);
        ImageView lens = new ImageView(this);
        lens.setImageResource(R.drawable.ic_search);
        lens.setImageTintList(ColorStateList.valueOf(getColor(R.color.muted)));
        search.addView(lens, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView full = text(getString(R.string.search_settings), 16, getColor(R.color.muted), false);
        searchLabel = full;
        full.setPadding(dp(8), 0, 0, 0);
        search.addView(full, new LinearLayout.LayoutParams(0, -2, 1f));
        bindTap(search, v -> openSearch());
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, -2);
        searchParams.bottomMargin = dp(14);
        column.addView(search, searchParams);

        carousel = new PrismCarouselView(this, new PrismCarouselView.Listener() {
            @Override public void onFocus(Panels.Panel panel) {
                buildPeek(panel);
                updateDots();
            }

            @Override public void onOpen(Panels.Panel panel) {
                openDetail(panel);
            }

            @Override public void onTap(PanelCard card) {
                openDetail(card.panel());
            }
        });
        LinearLayout.LayoutParams carouselParams = new LinearLayout.LayoutParams(-1, carouselStageHeight());
        carouselParams.setMarginStart(-dp(20));
        carouselParams.setMarginEnd(-dp(20));
        column.addView(carousel, carouselParams);

        rail = new IndexRailView(this);
        rail.setExpressive(egg || nxe);
        rail.setCount(Panels.ALL.size());
        LinearLayout.LayoutParams railParams = new LinearLayout.LayoutParams(-1, dp(26));
        railParams.topMargin = 0;
        railParams.bottomMargin = dp(12);
        column.addView(rail, railParams);

        peekHost = new LinearLayout(this);
        peekHost.setOrientation(LinearLayout.VERTICAL);
        column.addView(peekHost);

        if (egg) carousel.setCartridges(true);
        if (nxe) carousel.setNxe(true);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::onBack);
    }

    private void toggleCartridges() {
        egg = !egg;
        nxe = false;
        prefs.edit().putBoolean(KEY_EGG, egg).putBoolean(KEY_NXE, false).apply();
        dashboardBackdrop.setVisibility(View.GONE);
        carousel.setNxe(false);
        carousel.setCartridges(egg);
        updateMainAppearance();
        updateBackdropVisibility();
        carousel.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        Toast.makeText(this, egg ? R.string.cartridge_enabled : R.string.cartridge_disabled,
                Toast.LENGTH_SHORT).show();
    }

    private void toggleDashboard() {
        nxe = !nxe;
        egg = false;
        prefs.edit().putBoolean(KEY_NXE, nxe).putBoolean(KEY_EGG, false).apply();
        carousel.setCartridges(false);
        carousel.setNxe(nxe);
        updateMainAppearance();
        dashboardBackdrop.setVisibility(nxe ? View.VISIBLE : View.GONE);
        updateBackdropVisibility();
        carousel.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        Toast.makeText(this, nxe ? R.string.dashboard_enabled : R.string.cartridge_disabled,
                Toast.LENGTH_SHORT).show();
    }

    // The eggs still get the whole stage to themselves.
    private void updateBackdropVisibility() {
        if (wallpaperBackdrop == null) return;
        wallpaperBackdrop.setVisibility(egg || nxe ? View.GONE : View.VISIBLE);
    }

    private void updateDots() {
        if (rail == null) return;
        Panels.Panel panel = carousel.current();
        rail.setActive(carousel.focusIndex(), egg || nxe ? panel.cartColor : getColor(R.color.accent));
    }

    private float normalRadius(float expressive, float restrained) {
        return egg || nxe ? expressive : restrained;
    }

    private int carouselStageHeight() {
        int screenDp = Math.round(getResources().getDisplayMetrics().heightPixels
                / getResources().getDisplayMetrics().density);
        return egg || nxe ? dp(Math.max(240, Math.min(350, Math.round(screenDp * .40f))))
                : dp(Math.max(210, Math.min(280, Math.round(screenDp * .32f)))
                + Math.max(0f, getResources().getConfiguration().fontScale - 1f) * 80);
    }

    private void updateMainAppearance() {
        carousel.getLayoutParams().height = carouselStageHeight();
        carousel.requestLayout();
        rail.setExpressive(egg || nxe);
        updateDots();
        searchBar.setBackground(rounded(getColor(R.color.card), getColor(R.color.line), normalRadius(28, 14)));
        searchLabel.setTypeface(uiTypeface(false));
        if (peekCard != null) {
            peekCard.setBackground(rounded(getColor(R.color.card), getColor(R.color.line), normalRadius(28, 16)));
            buildPeek(carousel.current());
        }
    }

    // Compact controls below the selected category.

    private void buildPeek(Panels.Panel panel) {
        if (peekHost == null) return;
        boolean changed = peekPanelId != null && !peekPanelId.equals(panel.id);
        if (changed) {
            buildTaps = 0;
            softwareTaps = 0;
        }
        if (peekCard == null) {
            peekCard = new LinearLayout(this);
            peekCard.setOrientation(LinearLayout.VERTICAL);
            peekCard.setBackground(rounded(getColor(R.color.card), getColor(R.color.line), normalRadius(28, 16)));
            peekCard.setPadding(dp(18), dp(8), dp(18), dp(8));
            peekHost.addView(peekCard);
        } else if (changed && ValueAnimator.areAnimatorsEnabled() && peekCard.isLaidOut()) {
            // Reuse the shell instead of making the controls flash.
            TransitionManager.endTransitions(peekCard);
            TransitionSet morph = new TransitionSet();
            morph.addTransition(new ChangeBounds());
            morph.addTransition(new ChangeTransform());
            morph.setDuration(egg || nxe ? 480 : 200);
            morph.setInterpolator(new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f));
            TransitionManager.beginDelayedTransition(peekCard, morph);
        }
        LinearLayout card = peekCard;
        card.removeAllViews();
        peekPanelId = panel.id;
        animatePeekTint(panel, changed);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(44));
        TextView heading = text(panel.title, 18, getColor(R.color.ink), false);
        heading.setAccessibilityHeading(true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));
        header.setClickable(true);
        header.setFocusable(true);
        header.setContentDescription(getString(R.string.section_settings, panel.title));
        bindTap(header, v -> openDetail(panel));
        feedback(header);
        card.addView(header);

        switch (panel.id) {
            case "connect" -> {
                card.addView(actionRow(R.drawable.ic_wifi, "Wi-Fi", "Networks and internet",
                        Settings.ACTION_WIFI_SETTINGS, true));
                card.addView(actionRow(R.drawable.ic_bluetooth, "Bluetooth", "Devices and pairing",
                        Settings.ACTION_BLUETOOTH_SETTINGS, true));
                card.addView(actionRow(R.drawable.ic_wifi, "Mobile data", "Data usage and limits",
                        Settings.ACTION_DATA_USAGE_SETTINGS, true));
            }
            case "display" -> {
                card.addView(brightnessSlider(true));
                card.addView(depthSwitch(true));
                card.addView(actionRow(R.drawable.ic_display, "Text size & display",
                        "Full display settings", Settings.ACTION_DISPLAY_SETTINGS, true));
            }
            case "notifications" -> {
                card.addView(actionRow(R.drawable.ic_bell, "Notification settings",
                        "Alerts, lock screen, history", "android.settings.NOTIFICATION_SETTINGS", true));
                card.addView(notificationStartControl(false));
                card.addView(actionRow(R.drawable.ic_bell, "Notification history",
                        "Recently dismissed", "android.settings.NOTIFICATION_HISTORY", true));
            }
            case "sound" -> {
                card.addView(volumeSlider(true));
                card.addView(actionRow(R.drawable.ic_sound, "Do Not Disturb",
                        "Quiet time", "android.settings.ZEN_MODE_SETTINGS", true));
                card.addView(actionRow(R.drawable.ic_sound, "Sound & vibration",
                        "Ringtones, haptics", Settings.ACTION_SOUND_SETTINGS, true));
            }
            case "privacy" -> {
                card.addView(actionRow(R.drawable.ic_shield, "Privacy controls",
                        "Camera, microphone, location", "android.settings.PRIVACY_SETTINGS", true));
                card.addView(actionRow(R.drawable.ic_info, "Location access",
                        "Recent requests", Settings.ACTION_LOCATION_SOURCE_SETTINGS, true));
                card.addView(actionRow(R.drawable.ic_shield, "Security",
                        "Screen lock and accounts", Settings.ACTION_SECURITY_SETTINGS, true));
            }
            case "battery" -> {
                BatteryManager battery = getSystemService(BatteryManager.class);
                int level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                card.addView(infoRow("Battery level", level >= 0 && level <= 100
                        ? level + "%" : "Unavailable", true));
                card.addView(actionRow(R.drawable.ic_battery, "Battery saver",
                        "Stretch the last few percent", Settings.ACTION_BATTERY_SAVER_SETTINGS, true));
                card.addView(actionRow(R.drawable.ic_battery, "Battery usage",
                        "App and system usage", Intent.ACTION_POWER_USAGE_SUMMARY, true));
            }
            case "apps" -> {
                card.addView(actionRow(R.drawable.ic_apps, "All apps",
                        "Manage installed apps", Settings.ACTION_APPLICATION_SETTINGS, true));
                card.addView(actionRow(R.drawable.ic_apps, "Storage",
                        "Free space and usage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, true));
                card.addView(actionRow(R.drawable.ic_apps, "Default apps",
                        "Browsers, phone, home", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, true));
            }
            case "about" -> {
                card.addView(infoRow(getString(R.string.android_version), Build.VERSION.RELEASE, true));
                card.addView(buildNumberRow());
                card.addView(actionRow(R.drawable.ic_info, "Device info",
                        Build.MANUFACTURER + " " + Build.MODEL,
                        Settings.ACTION_DEVICE_INFO_SETTINGS, true));
            }
            default -> {
                int shown = 0;
                for (Panels.Action action : panel.actions) {
                    if (shown++ == 3) break;
                    card.addView(actionRow(action.icon, action.label, action.help,
                            action.action, true));
                }
            }
            case "hub" -> {
                card.addView(runRow(R.drawable.ic_petal, "Open petalOS Hub",
                        "Themes, island, launcher, updates", this::openHub));
                card.addView(runRow(R.drawable.ic_info, "petalOS version",
                        "Version and credits", this::openHubVersion));
                LinearLayout software = infoRow(getString(R.string.petal_version), petalBuild, true);
                software.setClickable(true);
                software.setFocusable(true);
                feedback(software);
                software.setOnClickListener(v -> {
                    if (++softwareTaps == 9) {
                        softwareTaps = 0;
                        toggleDashboard();
                    } else {
                        software.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                });
                card.addView(software);
            }
        }

        for (int i = 1; i < card.getChildCount(); i++) {
            View row = card.getChildAt(i);
            row.setLayoutParams(new LinearLayout.LayoutParams(-1, -2, 1f));
        }
        peekHost.post(this::fitPeekToViewport);
        nameMorphTargets(card, "compact");
    }

    private void nameMorphTargets(View view, String name) {
        view.setTransitionName(name);
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                nameMorphTargets(group.getChildAt(i), name + "/" + i);
            }
        }
    }

    private void animatePeekTint(Panels.Panel panel, boolean animate) {
        GradientDrawable surface = (GradientDrawable) peekCard.getBackground();
        if (peekTint != null) peekTint.cancel();
        if (!egg && !nxe) {
            surface.setColor(getColor(R.color.card));
            return;
        }
        int from = surface.getColor().getDefaultColor();
        int to = android.graphics.Color.rgb(
                30 + android.graphics.Color.red(panel.cartColor) / 14,
                30 + android.graphics.Color.green(panel.cartColor) / 14,
                30 + android.graphics.Color.blue(panel.cartColor) / 14);
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            surface.setColor(to);
            return;
        }
        peekTint = ValueAnimator.ofArgb(from, to);
        peekTint.setDuration(480);
        peekTint.addUpdateListener(a -> surface.setColor((int) a.getAnimatedValue()));
        peekTint.start();
    }

    private void fitPeekToViewport() {
        if (peekHost == null || homeScroll == null || peekHost.getChildCount() == 0) return;
        View card = peekHost.getChildAt(0);
        if (!egg && !nxe) {
            card.setMinimumHeight(0);
            return;
        }
        int viewportInner = homeScroll.getHeight()
                - homeScroll.getPaddingTop() - homeScroll.getPaddingBottom();
        ViewGroup column = (ViewGroup) peekHost.getParent();
        if (viewportInner <= 0 || column == null) return;
        int needed = viewportInner - peekHost.getTop() - column.getPaddingBottom();
        if (needed <= 0) return;
        if (Math.abs(needed - card.getMinimumHeight()) <= 1) return;
        card.setMinimumHeight(needed);
        card.requestLayout();
    }

    private View brightnessSlider() {
        return brightnessSlider(false);
    }

    private View brightnessSlider(boolean compact) {
        boolean canWrite = Settings.System.canWrite(this);
        if (!canWrite && compact) {
            return permissionRow(getString(R.string.brightness), getString(R.string.brightness_locked));
        }
        int value = Settings.System.getInt(getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, 128);
        View slider = sliderRow(getString(R.string.brightness), null, 1, 255, value, progress -> {
            if (!Settings.System.canWrite(this)) {
                requestWriteAccess();
                return;
            }
            Settings.System.putInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, progress);
        }, compact);
        if (!canWrite) {
            ViewGroup block = (ViewGroup) slider;
            for (int i = 0; i < block.getChildCount(); i++) {
                if (block.getChildAt(i) instanceof SeekBar bar) bar.setEnabled(false);
            }
            TextView note = text(getString(R.string.brightness_locked), 13,
                    getColor(R.color.muted), false);
            note.setPadding(0, dp(2), 0, dp(4));
            block.addView(note);
            TextView allow = chip(getString(R.string.grant));
            bindTap(allow, v -> requestWriteAccess());
            block.addView(allow);
        }
        return slider;
    }

    private View depthSwitch() {
        return depthSwitch(false);
    }

    private View depthSwitch(boolean compact) {
        boolean checked = Settings.System.getInt(getContentResolver(), KEY_DEPTH, 0) == 1;
        return switchRow(getString(R.string.depth_toggle), getString(R.string.depth_toggle_help),
                checked, (button, value) -> {
                    if (!Settings.System.canWrite(this)) {
                        requestWriteAccess();
                        button.setChecked(!value);
                        return;
                    }
                    Settings.System.putInt(getContentResolver(), KEY_DEPTH, value ? 1 : 0);
                }, compact);
    }

    private View notificationStartControl() {
        return notificationStartControl(true);
    }

    private View notificationStartControl(boolean includeSlider) {
        int stored = PetalConfig.getDepthNotificationStartPct(this);
        boolean custom = stored != DepthNotificationPlacement.AUTOMATIC;
        int remembered = prefs.getInt(KEY_NOTIFICATION_LAST, DepthNotificationPlacement.DEFAULT_PERCENT);
        int[] value = {custom ? stored : Math.max(DepthNotificationPlacement.MIN_PERCENT,
                Math.min(DepthNotificationPlacement.MAX_PERCENT, remembered))};
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        TextView summary = text(notificationPositionSummary(stored), 13, getColor(R.color.muted), false);
        View[] slider = {null};
        block.addView(switchRow(getString(R.string.notification_start), null, custom,
                (button, checked) -> {
                    if (!Settings.System.canWrite(this)) {
                        requestWriteAccess();
                        button.setChecked(!checked);
                        return;
                    }
                    int next = checked ? value[0] : DepthNotificationPlacement.AUTOMATIC;
                    if (!Settings.System.putInt(getContentResolver(), KEY_NOTIFICATION_START, next)) {
                        button.setChecked(!checked);
                        return;
                    }
                    if (checked) prefs.edit().putInt(KEY_NOTIFICATION_LAST, value[0]).apply();
                    summary.setText(notificationPositionSummary(next));
                    if (slider[0] != null) slider[0].setVisibility(checked ? View.VISIBLE : View.GONE);
                }, !includeSlider));
        block.addView(summary);
        if (!includeSlider) return block;
        slider[0] = sliderRow(getString(R.string.notification_position),
                getString(R.string.notification_start_help), DepthNotificationPlacement.MIN_PERCENT,
                DepthNotificationPlacement.MAX_PERCENT, value[0],
                progress -> {
                    if (!Settings.System.canWrite(this)) {
                        requestWriteAccess();
                        return;
                    }
                    if (Settings.System.putInt(getContentResolver(), KEY_NOTIFICATION_START, progress)) {
                        value[0] = progress;
                        prefs.edit().putInt(KEY_NOTIFICATION_LAST, progress).apply();
                        summary.setText(notificationPositionSummary(progress));
                    }
                }, false, progress -> getString(R.string.value_percent, progress));
        block.addView(slider[0]);
        slider[0].setVisibility(custom ? View.VISIBLE : View.GONE);
        for (int i = 0; i < ((ViewGroup) slider[0]).getChildCount(); i++) {
            View child = ((ViewGroup) slider[0]).getChildAt(i);
            if (child instanceof SeekBar) child.setEnabled(Settings.System.canWrite(this));
        }
        return block;
    }

    private String notificationPositionSummary(int percent) {
        return percent == DepthNotificationPlacement.AUTOMATIC
                ? getString(R.string.notification_position_automatic)
                : getString(R.string.notification_position_value, percent);
    }

    private View volumeSlider() {
        return volumeSlider(false);
    }

    private View volumeSlider(boolean compact) {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int value = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        return sliderRow(getString(R.string.volume), null, 0, max, value,
                progress -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0), compact);
    }

    private View permissionRow(String label, String help) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(60));
        row.setPadding(0, dp(6), 0, dp(6));
        row.setClickable(true);
        row.setFocusable(true);
        feedback(row);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_display);
        icon.setImageTintList(ColorStateList.valueOf(controlIconColor()));
        row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, dp(8), 0);
        labels.addView(text(label, 16, getColor(R.color.ink), false));
        TextView sub = text(help, 13, getColor(R.color.muted), false);
        sub.setPadding(0, dp(2), 0, 0);
        labels.addView(sub);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        bindTap(row, v -> requestWriteAccess());
        row.setContentDescription(label + ", " + help);
        return row;
    }

    // Detail pages and search.

    private void openSearch() {
        if (overlay != null) root.removeView(overlay);
        detailPanel = null;
        detailFromAllSettings = false;
        showingAllSettings = false;
        searching = true;
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(getColor(R.color.petal_scrim));
        overlay.setClickable(true);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(12), dp(20), dp(20));
        overlay.addView(column, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = roundIcon(R.drawable.ic_back, getString(R.string.back));
        bindTap(back, v -> closeDetail());
        head.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        searchInput = new EditText(this);
        searchInput.setTypeface(uiTypeface(false));
        searchInput.setTextSize(18);
        searchInput.setSingleLine(true);
        searchInput.setHint(R.string.search_settings);
        searchInput.setContentDescription(getString(R.string.search_settings));
        searchInput.setTextColor(getColor(R.color.ink));
        searchInput.setHintTextColor(getColor(R.color.muted));
        searchInput.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        searchInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        searchInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        head.addView(searchInput, new LinearLayout.LayoutParams(0, -2, 1));
        column.addView(head);
        ScrollView scroll = new HapticScrollView(this);
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(results);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                showSearchResults(results, s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(v.getWindowToken(), 0);
            return true;
        });
        showSearchResults(results, "");
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        homeScroll.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if (ValueAnimator.areAnimatorsEnabled()) {
            overlay.setAlpha(0f);
            overlay.setTranslationY(dp(12));
            overlay.animate().alpha(1f).translationY(0f)
                    .setDuration(240)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
        overlay.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            column.setPadding(dp(20) + bars.left, dp(12) + bars.top,
                    dp(20) + bars.right, dp(20) + Math.max(bars.bottom, ime.bottom));
            return insets;
        });
        overlay.requestApplyInsets();
        searchInput.requestFocus();
        searchInput.post(() -> {
            if (searching && searchInput != null) {
                getSystemService(InputMethodManager.class).showSoftInput(searchInput,
                        InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void showSearchResults(LinearLayout results, String query) {
        results.removeAllViews();
        if (query.isBlank()) {
            TextView hint = text(getString(R.string.search_hint), 14, getColor(R.color.muted), false);
            hint.setPadding(0, dp(16), 0, dp(8));
            results.addView(hint);
            TextView quick = text(getString(R.string.quick_search), 13,
                    getColor(R.color.accent), true);
            quick.setPadding(0, dp(8), 0, dp(6));
            results.addView(quick);
            String[][] suggestions = {
                    {"Wi-Fi", "Wi-Fi"}, {"Bluetooth", "Bluetooth"},
                    {"Brightness", "brightness"}, {"Sound", "sound"},
                    {"Battery", "battery"}, {"Privacy", "privacy"}};
            for (int i = 0; i < suggestions.length; i += 2) {
                LinearLayout row = new LinearLayout(this);
                for (int j = i; j < i + 2; j++) {
                    String label = suggestions[j][0];
                    String key = suggestions[j][1];
                    TextView choice = chip(label);
                    choice.setGravity(Gravity.CENTER);
                    bindTap(choice, v -> {
                        searchInput.setText(key);
                        searchInput.setSelection(key.length());
                    });
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
                    params.setMarginEnd(j == i ? dp(8) : 0);
                    row.addView(choice, params);
                }
                results.addView(row);
            }
            return;
        }
        int count = 0;
        for (SettingsSearch.Entry entry : SettingsSearch.ENTRIES) {
            if (!entry.matches(query)) continue;
            count++;
            Panels.Panel panel = Panels.byId(entry.panelId);
            LinearLayout row = actionRow(panel.icon, entry.title, panel.title,
                    entry.action == null ? Settings.ACTION_SETTINGS : entry.action);
            if (entry.action == null) {
                row.setContentDescription(entry.title + ", " + panel.title);
                bindTap(row, v -> openDetail(panel));
            }
            results.addView(row);
        }

        appResults = results;
        appQuery = query;
        int appMatches = addAppResults(results, query);

        if (count == 0 && appMatches == 0) {
            TextView empty = text(getString(R.string.search_empty), 16, getColor(R.color.muted), false);
            empty.setPadding(0, dp(24), 0, dp(16));
            results.addView(empty);
        }
        results.addView(actionRow(R.drawable.ic_search, getString(R.string.open_settings),
                "Every option on your device", Settings.ACTION_SETTINGS));
    }

    /** Installed apps matching the query, each opening its system App info page. */
    private int addAppResults(LinearLayout results, String query) {
        if (appIndex == null) {
            loadAppIndex();
            return 0;
        }
        String needle = query.trim().toLowerCase(java.util.Locale.ROOT);
        int shown = 0;
        boolean header = false;
        for (AppResult app : appIndex) {
            if (!app.label.toLowerCase(java.util.Locale.ROOT).contains(needle)
                    && !app.packageName.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                continue;
            }
            if (!header) {
                TextView title = text("Apps", 13, getColor(R.color.accent), true);
                title.setAccessibilityHeading(true);
                title.setPadding(0, dp(18), 0, dp(6));
                results.addView(title);
                header = true;
            }
            results.addView(appRow(app));
            if (++shown == 15) break;
        }
        return shown;
    }

    private LinearLayout appRow(AppResult app) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(72));
        row.setPadding(0, dp(10), 0, dp(10));
        row.setClickable(true);
        row.setFocusable(true);
        feedback(row);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, dp(8), 0);
        labels.addView(text(app.label, 16, getColor(R.color.ink), false));
        TextView sub = text(app.launchable ? "App info" : "App info · system",
                13, getColor(R.color.muted), false);
        sub.setPadding(0, dp(3), 0, 0);
        labels.addView(sub);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        bindTap(row, v -> openAppInfo(app.packageName));
        row.setContentDescription(app.label + ", App info");
        return row;
    }

    private void openAppInfo(String packageName) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + packageName));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException missing) {
            openAospSettings();
        }
    }

    private void loadAppIndex() {
        if (loadingApps) return;
        loadingApps = true;
        android.content.pm.PackageManager pm = getPackageManager();
        new Thread(() -> {
            List<AppResult> loaded = new java.util.ArrayList<>();
            Set<String> launchers = new java.util.HashSet<>();
            Intent launcher = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER);
            for (android.content.pm.ResolveInfo info
                    : pm.queryIntentActivities(launcher, 0)) {
                if (info.activityInfo != null) launchers.add(info.activityInfo.packageName);
            }
            for (android.content.pm.ApplicationInfo info
                    : pm.getInstalledApplications(0)) {
                if (!info.enabled) continue;
                CharSequence label;
                try {
                    label = pm.getApplicationLabel(info);
                } catch (RuntimeException broken) {
                    continue;
                }
                if (label == null || label.length() == 0) continue;
                Drawable icon;
                try {
                    icon = pm.getApplicationIcon(info);
                } catch (RuntimeException broken) {
                    icon = getDrawable(R.drawable.ic_apps);
                }
                loaded.add(new AppResult(icon, label.toString(), info.packageName,
                        launchers.contains(info.packageName)));
            }
            loaded.sort((a, b) -> {
                if (a.launchable != b.launchable) return a.launchable ? -1 : 1;
                return a.label.compareToIgnoreCase(b.label);
            });
            runOnUiThread(() -> {
                appIndex = loaded;
                loadingApps = false;
                if (appResults != null && appQuery != null && !isFinishing()) {
                    showSearchResults(appResults, appQuery);
                }
            });
        }, "petal-app-index").start();
    }

    // Full category browser.

    private void openAllSettings() {
        if (searchInput != null) getSystemService(InputMethodManager.class)
                .hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        if (overlay != null) root.removeView(overlay);
        detailPanel = null;
        detailFromAllSettings = false;
        searching = false;
        showingAllSettings = true;
        searchInput = null;
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(getColor(R.color.petal_scrim));
        overlay.setClickable(true);
        overlay.setFocusable(true);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(12), dp(20), dp(20));
        overlay.addView(column, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = roundIcon(R.drawable.ic_back, getString(R.string.back));
        bindTap(back, v -> closeDetail());
        head.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView label = text(getString(R.string.title), 14, getColor(R.color.muted), false);
        label.setPadding(dp(11), 0, 0, 0);
        head.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView full = chip(getString(R.string.full_settings));
        full.setBackground(null);
        full.setContentDescription(getString(R.string.open_settings));
        bindTap(full, v -> openSystem(Settings.ACTION_SETTINGS));
        head.addView(full);
        column.addView(head);

        ScrollView scroll = new HapticScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(4), 0, dp(8));
        scroll.addView(body);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView title = text(getString(R.string.all_settings), 32, getColor(R.color.ink), false);
        title.setAccessibilityHeading(true);
        title.setLetterSpacing(-0.02f);
        title.setPadding(0, dp(8), 0, dp(4));
        body.addView(title);
        TextView sub = text(getString(R.string.all_settings_subtitle), 15,
                getColor(R.color.muted), false);
        sub.setPadding(0, 0, 0, dp(14));
        body.addView(sub);

        // Leave enough width for category names to wrap between words.
        int cols = 2;
        float fontScale = getResources().getConfiguration().fontScale;
        int tileHeight = dp(92) + dp(Math.max(0, fontScale - 1f) * 70);
        java.util.ArrayList<Panels.Panel> gridPanels = new java.util.ArrayList<>();
        for (Panels.Panel candidate : Panels.ALL) {
            if (!"hub".equals(candidate.id)) gridPanels.add(candidate);
        }
        for (int rowStart = 0; rowStart < gridPanels.size(); rowStart += cols) {
            LinearLayout row = new LinearLayout(this);
            for (int col = 0; col < cols; col++) {
                int position = rowStart + col;
                Panels.Panel panel = gridPanels.get(position);
                View tile = settingsTile(panel.title, panel.icon, v -> openDetail(panel));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, tileHeight, 1f);
                if (col > 0) params.setMarginStart(dp(8));
                row.addView(tile, params);
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            if (rowStart > 0) rowParams.topMargin = dp(8);
            body.addView(row, rowParams);
        }

        TextView more = text(getString(R.string.more_settings), 13, getColor(R.color.accent), true);
        more.setAccessibilityHeading(true);
        more.setPadding(0, dp(18), 0, dp(8));
        body.addView(more);
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(rounded(getColor(R.color.card), getColor(R.color.line), normalRadius(20, 14)));
        group.setPadding(dp(14), dp(2), dp(14), dp(2));
        group.addView(actionRow(R.drawable.ic_display, "Accessibility",
                "Screen reader, magnification, hearing",
                Settings.ACTION_ACCESSIBILITY_SETTINGS, true));
        group.addView(actionRow(R.drawable.ic_apps, "System",
                "Languages, date & time, backup, reset",
                "android.settings.SYSTEM_SETTINGS", true));
        body.addView(group);

        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        homeScroll.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        overlay.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            column.setPadding(dp(20) + bars.left, dp(12) + bars.top,
                    dp(20) + bars.right, dp(20) + bars.bottom);
            return insets;
        });
        overlay.requestApplyInsets();
        if (ValueAnimator.areAnimatorsEnabled()) {
            overlay.setAlpha(0f);
            overlay.setTranslationY(dp(16));
            overlay.animate().alpha(1f).translationY(0f).setDuration(220).start();
        }
    }

    private LinearLayout settingsTile(String label, int iconRes, View.OnClickListener listener) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackground(rounded(getColor(R.color.card), getColor(R.color.line), normalRadius(20, 14)));
        tile.setPadding(dp(10), dp(10), dp(10), dp(10));
        tile.setClickable(true);
        tile.setFocusable(true);
        feedback(tile);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(controlIconColor()));
        tile.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView title = text(label, 16, getColor(R.color.ink), false);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(0, dp(7), 0, 0);
        tile.addView(title);
        bindTap(tile, listener);
        tile.setContentDescription(label);
        return tile;
    }

    private void openDetail(Panels.Panel panel) {
        openDetail(panel, true);
    }

    private void openDetail(Panels.Panel panel, boolean animate) {
        if (searchInput != null) getSystemService(InputMethodManager.class)
                .hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        detailFromAllSettings = showingAllSettings;
        detailPanel = panel;
        searching = false;
        showingAllSettings = false;
        searchInput = null;
        if (overlay != null) root.removeView(overlay);
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(getColor(R.color.petal_scrim));
        overlay.setClickable(true);
        overlay.setFocusable(true);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(16), dp(20), dp(24));
        overlay.addView(column, new FrameLayout.LayoutParams(-1, -1));
        View top = new View(this) {
            @Override protected void onDraw(Canvas canvas) {
                // nothing
            }
        };
        column.addView(top, new LinearLayout.LayoutParams(-1, dp(4)));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = roundIcon(R.drawable.ic_back, getString(R.string.back));
        bindTap(back, v -> closeDetail());
        head.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView label = text(getString(R.string.title), 14, getColor(R.color.muted), false);
        label.setPadding(dp(11), 0, 0, 0);
        head.addView(label);
        column.addView(head);

        ScrollView scroll = new HapticScrollView(this);
        detailScroll = scroll;
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(6), 0, 0);
        scroll.addView(body);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView title = text(panel.title, 32, getColor(R.color.ink), false);
        title.setAccessibilityHeading(true);
        title.setLetterSpacing(-0.02f);
        title.setPadding(0, dp(10), 0, dp(4));
        body.addView(title);
        TextView sub = text(panel.sub, 15, getColor(R.color.muted), false);
        sub.setPadding(0, 0, 0, dp(14));
        body.addView(sub);

        switch (panel.id) {
            case "connect" -> {
                body.addView(actionRow(R.drawable.ic_wifi, "Wi-Fi", "Networks and internet",
                        Settings.ACTION_WIFI_SETTINGS));
                body.addView(actionRow(R.drawable.ic_bluetooth, "Bluetooth", "Paired devices",
                        Settings.ACTION_BLUETOOTH_SETTINGS));
                body.addView(actionRow(R.drawable.ic_wifi, "Mobile data", "Data usage",
                        Settings.ACTION_DATA_USAGE_SETTINGS));
                body.addView(actionRow(R.drawable.ic_wifi, "Airplane mode", "Radio controls",
                        Settings.ACTION_AIRPLANE_MODE_SETTINGS));
            }
            case "display" -> {
                body.addView(card(brightnessSlider()));
                body.addView(card(depthSwitch()));
                body.addView(actionRow(R.drawable.ic_display, "Text size & display",
                        "Full display settings", Settings.ACTION_DISPLAY_SETTINGS));
            }
            case "notifications" -> {
                body.addView(actionRow(R.drawable.ic_bell, "Notification settings",
                        "Alerts, importance, lock screen", "android.settings.NOTIFICATION_SETTINGS"));
                body.addView(actionRow(R.drawable.ic_bell, "Notification history",
                        "Recently dismissed", "android.settings.NOTIFICATION_HISTORY"));
                body.addView(card(notificationStartControl()));
            }
            case "sound" -> {
                body.addView(card(volumeSlider()));
                body.addView(actionRow(R.drawable.ic_sound, "Do Not Disturb",
                        "Quiet time and exceptions", "android.settings.ZEN_MODE_SETTINGS"));
                body.addView(actionRow(R.drawable.ic_sound, "Sound & vibration",
                        "Ringtones, touch feedback", Settings.ACTION_SOUND_SETTINGS));
            }
            case "privacy" -> {
                body.addView(actionRow(R.drawable.ic_shield, "Privacy controls",
                        "Camera, microphone, location", "android.settings.PRIVACY_SETTINGS"));
                body.addView(actionRow(R.drawable.ic_shield, "Location access",
                        "Recent requests and toggles", Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                body.addView(actionRow(R.drawable.ic_shield, "Security",
                        "Screen lock and accounts", Settings.ACTION_SECURITY_SETTINGS));
            }
            case "battery" -> {
                BatteryManager battery = getSystemService(BatteryManager.class);
                int level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                body.addView(infoRow("Battery level", level >= 0 && level <= 100
                        ? level + "%" : "Unavailable"));
                body.addView(actionRow(R.drawable.ic_battery, "Battery saver",
                        "Extend the last few percent", Settings.ACTION_BATTERY_SAVER_SETTINGS));
                body.addView(actionRow(R.drawable.ic_battery, "Battery usage",
                        "App and system drain", Intent.ACTION_POWER_USAGE_SUMMARY));
            }
            case "apps" -> {
                body.addView(actionRow(R.drawable.ic_apps, "All apps",
                        "Manage installed applications", Settings.ACTION_APPLICATION_SETTINGS));
                body.addView(actionRow(R.drawable.ic_apps, "Storage",
                        "Free space and cleanup", Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
                body.addView(actionRow(R.drawable.ic_apps, "Default apps",
                        "Browsers, phone, home", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
            }
            case "about" -> {
                body.addView(infoRow(getString(R.string.device_name), deviceName()));
                body.addView(infoRow(getString(R.string.petal_version), petalVersion()));
                body.addView(infoRow(getString(R.string.android_version), Build.VERSION.RELEASE
                        + " · API " + Build.VERSION.SDK_INT));
                body.addView(infoRow(getString(R.string.build_number), petalBuild));
                body.addView(actionRow(R.drawable.ic_info, "Device info",
                        "Full system information", Settings.ACTION_DEVICE_INFO_SETTINGS));
                body.addView(runRow(R.drawable.ic_apps, getString(R.string.go_to_aosp_settings),
                        null, this::openAospSettings));
            }
            default -> {
                for (Panels.Action action : panel.actions) {
                    body.addView(actionRow(action.icon, action.label, action.help,
                            action.action));
                }
            }
            case "hub" -> {
                body.addView(runRow(R.drawable.ic_petal, "Open petalOS Hub",
                        "Themes, island, launcher, updates", this::openHub));
                body.addView(runRow(R.drawable.ic_info, "petalOS version",
                        "Version and credits", this::openHubVersion));
                body.addView(infoRow(getString(R.string.petal_version), petalBuild));
            }
        }

        TextView all = chip(getString(R.string.view_all_settings));
        all.setGravity(Gravity.CENTER);
        bindTap(all, v -> openAllSettings());
        LinearLayout.LayoutParams allParams = new LinearLayout.LayoutParams(-1, -2);
        allParams.topMargin = dp(16);
        body.addView(all, allParams);

        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        homeScroll.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        overlay.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            column.setPadding(dp(20), dp(16) + bars.top, dp(20), dp(24) + bars.bottom);
            return insets;
        });
        overlay.requestApplyInsets();
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            overlay.setAlpha(0f);
            overlay.setTranslationY(dp(16));
            overlay.animate().alpha(1f).translationY(0f).setDuration(220).start();
        }
    }

    private void closeDetail() {
        if (overlay == null) return;
        boolean returnToAllSettings = detailFromAllSettings;
        FrameLayout closing = overlay;
        overlay = null;
        detailPanel = null;
        detailScroll = null;
        detailFromAllSettings = false;
        searching = false;
        showingAllSettings = false;
        buildTaps = 0;
        if (searchInput != null) {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(
                    searchInput.getWindowToken(), 0);
            searchInput = null;
        }
        if (returnToAllSettings) {
            root.removeView(closing);
            openAllSettings();
            return;
        }
        appResults = null;
        appQuery = null;
        homeScroll.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        buildPeek(carousel.current());
        if (ValueAnimator.areAnimatorsEnabled()) {
            closing.animate().alpha(0f).translationY(dp(12))
                    .setDuration(170)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .withEndAction(() -> root.removeView(closing))
                    .start();
        } else {
            root.removeView(closing);
        }
    }

    private void onBack() {
        if (searching && searchInput != null) {
            WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
            if (insets != null && insets.isVisible(WindowInsets.Type.ime())) {
                getSystemService(InputMethodManager.class)
                        .hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
                return;
            }
        }
        if (overlay != null) closeDetail();
        else finish();
    }

    // Shared controls.

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(uiTypeface(bold));
        return view;
    }

    private Typeface uiTypeface(boolean bold) {
        return egg || nxe
                ? Typeface.create(getResources().getFont(R.font.petal_rubik), bold ? Typeface.BOLD : Typeface.NORMAL)
                : Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL);
    }

    private int controlIconColor() {
        return getColor(egg || nxe ? R.color.accent : R.color.muted);
    }

    private TextView chip(String label) {
        TextView chip = text(label, 14, getColor(R.color.accent), true);
        chip.setMinimumHeight(dp(48));
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(12), dp(8), dp(12), dp(8));
        chip.setBackground(rounded(getColor(R.color.card), getColor(R.color.line), normalRadius(20, 12)));
        chip.setClickable(true);
        chip.setFocusable(true);
        feedback(chip);
        return chip;
    }

    private ImageView roundIcon(int res, String description) {
        ImageView view = new ImageView(this);
        view.setImageResource(res);
        view.setImageTintList(ColorStateList.valueOf(getColor(R.color.ink)));
        view.setContentDescription(description);
        view.setBackground(rounded(getColor(R.color.card), getColor(R.color.line), normalRadius(99, 12)));
        view.setPadding(dp(14), dp(14), dp(14), dp(14));
        view.setClickable(true);
        view.setFocusable(true);
        feedback(view);
        return view;
    }

    private void feedback(View view) {
        view.setSoundEffectsEnabled(true);
        view.setHapticFeedbackEnabled(true);
        view.setForeground(new RippleDrawable(ColorStateList.valueOf(getColor(R.color.soft)),
                null, rounded(0xFFFFFFFF, 0, normalRadius(20, 12))));
    }

    private void bindTap(View view, View.OnClickListener listener) {
        view.setSoundEffectsEnabled(true);
        view.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            // performClick already handles the sound; don't play the bloody thing twice.
            listener.onClick(v);
        });
    }

    private GradientDrawable rounded(int fill, int stroke, float radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radiusDp));
        shape.setStroke(Math.max(1, dp(1)), stroke);
        return shape;
    }

    private LinearLayout card(View child) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(getColor(R.color.card), getColor(R.color.line), normalRadius(24, 16)));
        box.setPadding(dp(12), dp(4), dp(12), dp(4));
        box.addView(child);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        box.setLayoutParams(params);
        return box;
    }

    private LinearLayout switchRow(String label, String help, boolean checked,
            CompoundButton.OnCheckedChangeListener listener) {
        return switchRow(label, help, checked, listener, false);
    }

    private LinearLayout switchRow(String label, String help, boolean checked,
            CompoundButton.OnCheckedChangeListener listener, boolean compact) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(compact ? 64 : 72));
        row.setPadding(0, dp(compact ? 6 : 10), 0, dp(compact ? 6 : 10));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(label, 16, getColor(R.color.ink), false);
        labels.addView(title);
        if (help != null) {
            TextView sub = text(help, 13, getColor(R.color.muted), false);
            sub.setPadding(0, dp(2), 0, 0);
            labels.addView(sub);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        toggle.setThumbTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        toggle.setTrackTintList(ColorStateList.valueOf(getColor(R.color.soft)));
        toggle.setContentDescription(label);
        toggle.setMinimumHeight(dp(48));
        boolean[] updating = {false};
        toggle.setOnCheckedChangeListener((button, value) -> {
            if (updating[0]) return;
            updating[0] = true;
            try {
                listener.onCheckedChanged(button, value);
                if (button.isChecked() == value) {
                    button.performHapticFeedback(value ? HapticFeedbackConstants.TOGGLE_ON
                            : HapticFeedbackConstants.TOGGLE_OFF);
                }
            } finally {
                updating[0] = false;
            }
        });
        row.addView(toggle);
        return row;
    }

    private LinearLayout sliderRow(String label, String help, int min, int max, int value,
            IntConsumer onChange) {
        return sliderRow(label, help, min, max, value, onChange, false);
    }

    private LinearLayout sliderRow(String label, String help, int min, int max, int value,
            IntConsumer onChange, boolean compact) {
        return sliderRow(label, help, min, max, value, onChange, compact,
                progress -> sliderValue(label, min, max, progress));
    }

    private LinearLayout sliderRow(String label, String help, int min, int max, int value,
            IntConsumer onChange, boolean compact, java.util.function.IntFunction<String> formatValue) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(compact ? 2 : 8), 0, dp(compact ? 2 : 8));
        TextView valueView = null;
        if (label != null) {
            LinearLayout top = new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = text(label, 16, getColor(R.color.ink), false);
            top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
            valueView = text(formatValue.apply(value), 14, getColor(R.color.accent), true);
            top.addView(valueView);
            row.addView(top);
        }
        SeekBar bar = new SeekBar(this);
        bar.setContentDescription(label != null ? label : help);
        bar.setMinimumHeight(dp(compact ? 40 : 48));
        bar.setMin(min);
        bar.setMax(max);
        bar.setProgress(value);
        bar.setProgressTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        bar.setThumbTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        bar.setPadding(dp(2), dp(compact ? 2 : 6), dp(2), dp(compact ? 2 : 6));
        TextView shown = valueView;
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private int lastStep = step(value);
            private long lastTick;

            private int step(int progress) {
                return Math.round((progress - min) * 20f / (max - min));
            }

            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (shown != null) shown.setText(formatValue.apply(progress));
                if (!fromUser) return;
                onChange.accept(progress);
                int next = step(progress);
                long now = android.os.SystemClock.uptimeMillis();
                if (next != lastStep && now - lastTick >= 50) {
                    seekBar.performHapticFeedback(progress == min || progress == max
                            ? HapticFeedbackConstants.SEGMENT_TICK
                            : HapticFeedbackConstants.SEGMENT_FREQUENT_TICK);
                    lastTick = now;
                }
                lastStep = next;
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                lastStep = step(seekBar.getProgress());
                seekBar.playSoundEffect(SoundEffectConstants.CLICK);
                seekBar.performHapticFeedback(HapticFeedbackConstants.GESTURE_START);
            }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                seekBar.performHapticFeedback(HapticFeedbackConstants.GESTURE_END);
            }
        });
        row.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        if (help != null) {
            TextView sub = text(help, 13, getColor(R.color.muted), false);
            sub.setPadding(0, 0, 0, 0);
            row.addView(sub);
        }
        return row;
    }

    private String sliderValue(String label, int min, int max, int value) {
        return Math.round((value - min) * 100f / (max - min)) + "%";
    }

    private LinearLayout actionRow(int iconRes, String label, String help, String action) {
        return actionRow(iconRes, label, help, action, false);
    }

    private LinearLayout actionRow(int iconRes, String label, String help, String action,
            boolean compact) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(compact ? 64 : 72));
        row.setPadding(0, dp(compact ? 6 : 10), 0, dp(compact ? 6 : 10));
        row.setClickable(true);
        row.setFocusable(true);
        feedback(row);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(controlIconColor()));
        row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, dp(8), 0);
        TextView title = text(label, 16, getColor(R.color.ink), false);
        labels.addView(title);
        if (help != null) {
            TextView sub = text(help, 13, getColor(R.color.muted), false);
            sub.setPadding(0, dp(3), 0, 0);
            labels.addView(sub);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        bindTap(row, v -> openSystem(action));
        row.setContentDescription(label + (help == null ? "" : ", " + help)
                + ". " + getString(R.string.system_screen));
        return row;
    }

    private LinearLayout buildNumberRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setPadding(0, dp(8), 0, dp(8));
        row.setClickable(true);
        row.setFocusable(true);
        feedback(row);
        TextView title = text(getString(R.string.build_number), 15, getColor(R.color.ink), false);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView value = text(petalBuild, 14, getColor(R.color.muted), false);
        value.setGravity(Gravity.END);
        value.setMaxLines(1);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(value, new LinearLayout.LayoutParams(-2, -2));
        row.setContentDescription(getString(R.string.build_number) + ", " + petalBuild);
        row.setOnClickListener(v -> {
            buildTaps++;
            if (buildTaps >= 26) {
                buildTaps = 0;
                toggleCartridges();
            } else {
                row.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
        });
        return row;
    }

    private LinearLayout infoRow(String label, String value) {
        return infoRow(label, value, false);
    }

    private LinearLayout infoRow(String label, String value, boolean compact) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(compact ? 36 : 0));
        row.setPadding(0, dp(compact ? 4 : 8), 0, dp(compact ? 4 : 8));
        TextView title = text(label, compact ? 14 : 15, getColor(R.color.ink), false);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView detail = text(value, 14, getColor(R.color.muted), false);
        detail.setGravity(Gravity.END);
        detail.setMaxLines(1);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(detail, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private LinearLayout runRow(int iconRes, String label, String help, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(60));
        row.setPadding(0, dp(6), 0, dp(6));
        row.setClickable(true);
        row.setFocusable(true);
        feedback(row);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(controlIconColor()));
        row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, dp(8), 0);
        labels.addView(text(label, 16, getColor(R.color.ink), false));
        if (help != null) {
            TextView sub = text(help, 13, getColor(R.color.muted), false);
            sub.setPadding(0, dp(2), 0, 0);
            labels.addView(sub);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        row.setContentDescription(label + (help == null ? "" : ", " + help));
        bindTap(row, v -> action.run());
        return row;
    }

    private void openHub() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("org.petalos.hub", "org.petalos.hub.HubActivity"));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException missing) {
            Toast.makeText(this, "petalOS Hub isn't available here.", Toast.LENGTH_SHORT).show();
        }
    }

    private void openHubVersion() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("org.petalos.hub",
                "org.petalos.hub.PetalVersionActivity"));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException missing) {
            openHub();
        }
    }

    private void openSystem(String action) {
        if (Settings.ACTION_SETTINGS.equals(action)) {
            openAospSettings();
            return;
        }
        try {
            if (action.contains("/")) {
                // Some AOSP dashboards only expose an explicit component.
                startActivity(new Intent().setComponent(
                        ComponentName.unflattenFromString(action)));
            } else {
                startActivity(new Intent(action));
            }
        } catch (ActivityNotFoundException | SecurityException missing) {
            openAospSettings();
        }
    }

    private void openAospSettings() {
        try {
            // The original homepage refuses to run as a non-root activity, so give
            // it its own task. Back then returns to the Petal front end.
            startActivity(new Intent(Settings.ACTION_SETTINGS)
                    .setComponent(new ComponentName("com.android.settings",
                            "com.android.settings.homepage.SettingsHomepageActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        } catch (ActivityNotFoundException missing) {
            Toast.makeText(this, "That screen isn't available here.", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestWriteAccess() {
        if (writeDialog != null && writeDialog.isShowing()) return;
        writeDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.write_settings_title)
                .setMessage(R.string.write_settings_body)
                .setPositiveButton(R.string.grant, (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:" + getPackageName()));
                    try {
                        startActivity(intent);
                    } catch (ActivityNotFoundException missing) {
                        openAospSettings();
                    }
                })
                .setNegativeButton(R.string.not_now, null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (carousel != null) buildPeek(carousel.current());
        if (detailPanel != null) {
            int scrollY = detailScroll == null ? 0 : detailScroll.getScrollY();
            boolean fromAllSettings = detailFromAllSettings;
            openDetail(detailPanel, false);
            detailFromAllSettings = fromAllSettings;
            detailScroll.post(() -> {
                if (detailScroll != null) detailScroll.scrollTo(0, scrollY);
            });
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("panel", carousel.current().id);
        state.putBoolean("searching", searching);
        state.putBoolean("allSettings", showingAllSettings);
        state.putBoolean("detailFromAllSettings", detailFromAllSettings);
        if (searchInput != null) state.putString("query", searchInput.getText().toString());
        if (detailPanel != null) state.putString("detail", detailPanel.id);
    }

    private String petalVersion() {
        return petalBuild;
    }

    private String readPetalBuild() {
        String version = getString(R.string.petal_os_version);
        try {
            version = (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, "ro.petal.version", version);
        } catch (ReflectiveOperationException ignored) {
            // The standalone SDK preview uses the release resource.
        }
        return "petalOS" + version.replace(".", "") + Build.MODEL.replace(" ", "");
    }

    private String deviceName() {
        return Build.MANUFACTURER + " " + Build.MODEL;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    interface IntConsumer {
        void accept(int value);
    }

    private static final class AppResult {
        final Drawable icon;
        final String label;
        final String packageName;
        final boolean launchable;

        AppResult(Drawable icon, String label, String packageName, boolean launchable) {
            this.icon = icon;
            this.label = label;
            this.packageName = packageName;
            this.launchable = launchable;
        }
    }

}
