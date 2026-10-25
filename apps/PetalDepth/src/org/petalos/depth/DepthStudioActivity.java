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

package org.petalos.depth;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.petalos.config.PetalConfig;
import org.petalos.config.DepthNotificationPlacement;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


public class DepthStudioActivity extends Activity {

    private static final String TAG = "DepthStudio";
    private static final int REQUEST_PHOTO = 1;

    private DepthWidgets.PresetCard[] mPresetCards;
    private DepthWidgets.InkSwatch[] mInkSwatches;
    private Switch mDateToggle;
    private LiquidPill mStackChip;
    private final LiquidPill[] mRefineTools = new LiquidPill[2];
    private DepthWidgets.FancySlider mSizeSlider;
    private DepthWidgets.FancySlider mMotionSlider;
    private Switch mNotificationToggle;
    private SeekBar mNotificationSlider;
    private TextView mNotificationPositionLabel;
    private LiquidPill mSubjectChip;
    private LinearLayout mSheet;
    private OutlinePrompt mLastPrompt;
    private android.widget.Button mOutlineCancel;

    // palette copied from SystemUI
    private static final int COLOR_CANVAS = 0xFF0C0C0E;
    private static final int COLOR_GLASS = 0xE6121214;
    private static final int COLOR_GLASS_STROKE = 0x26FFFFFF;
    private static final int COLOR_GLYPH_DARK = 0xFF161618;
    private static final int COLOR_GLYPH_LIGHT = 0xF2FFFFFF;
    private static final int COLOR_GLYPH_DIM = 0x99FFFFFF;
    private static final int COLOR_DANGER = 0xFFFF453A;
    private static final int COLOR_GOLD = 0xFFFFB300;

    private SegmenterHelper mSegmenter;
    private DepthPreviewView mPreview;
    private StatusPillView mStatus;
    private LiquidPill mApplyButton;
    private boolean mBusy = false;
    private int mBlurAmount;
    private Bitmap mBlurredBase;
    private Bitmap mBlendedCutout;
    private Bitmap mFrostedBase;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSegmenter = new SegmenterHelper();
        mBlurAmount = getPreferences(0).getInt("background_blur", 0);
        // feed the clock rubik, shrug if the resource is missing
        try {
            org.petalos.config.DepthClockMaterials.setBaseTypeface(
                    getResources().getFont(R.font.petal_rubik));
        } catch (Exception ignored) {
        }

        getWindow().setStatusBarColor(COLOR_CANVAS);
        getWindow().setNavigationBarColor(COLOR_CANVAS);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_CANVAS);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        TextView heading = new TextView(this);
        heading.setText(R.string.depth_app_name);
        heading.setTextColor(COLOR_GLYPH_LIGHT);
        heading.setTextSize(24);
        heading.setPadding(dp(24), dp(12), dp(24), dp(8));
        root.addView(heading);
        mOutlineCancel = new android.widget.Button(this);
        mOutlineCancel.setText(R.string.depth_cancel_outline);
        mOutlineCancel.setAllCaps(false);
        mOutlineCancel.setVisibility(View.GONE);
        mOutlineCancel.setOnClickListener(v -> finishOutlineMode());
        root.addView(mOutlineCancel, new LinearLayout.LayoutParams(-1, dp(48)));
        mStatus = new StatusPillView(this);
        mStatus.setText(getString(R.string.depth_pick_photo));
        root.addView(mStatus, new LinearLayout.LayoutParams(-1, dp(44)));

        FrameLayout previewArea = new FrameLayout(this);
        root.addView(previewArea, new LinearLayout.LayoutParams(-1, 0, 1.15f));
        mPreview = new DepthPreviewView(this);
        mPreview.setBackground(capsuleBackground(COLOR_CANVAS, COLOR_GLASS_STROKE, dp(24)));
        mPreview.setClipToOutline(true);
        previewArea.addView(mPreview, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        previewArea.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            float aspect = getResources().getDisplayMetrics().widthPixels
                    / (float) getResources().getDisplayMetrics().heightPixels;
            int height = Math.max(1, b - t - dp(16));
            int width = Math.max(1, Math.min(r - l - dp(40), Math.round(height * aspect)));
            height = Math.round(width / aspect);
            ViewGroup.LayoutParams lp = mPreview.getLayoutParams();
            if (lp.width != width || lp.height != height) {
                lp.width = width;
                lp.height = height;
                mPreview.setLayoutParams(lp);
            }
        });
        mSheet = buildSheet();
        root.addView(mSheet, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        root.requestApplyInsets();
        if (PetalConfig.getDepthClockStyle(this) == DepthClockRenderer.STYLE_OFF) {
            DepthPresets.apply(this, DepthPresets.CINEMA);
        }
        // rebuild a corrupt cutout on launch
        android.os.AsyncTask.execute(() -> {
            File filesDir = getFilesDir();
            if (healCutout(filesDir) && PetalConfig.isDepthEnabled(this)) {
                int generation = PetalConfig.getDepthGeneration(this) + 1;
                Settings.System.putInt(getContentResolver(),
                        PetalConfig.KEY_DEPTH_GENERATION, generation);
            }
        });
        mPreview.setDateOn(PetalConfig.isDepthClockDateEnabled(this));
        refreshSelections();
        restoreDraft();
        mPreview.startTicker();
    }

    
    private LinearLayout buildSheet() {
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(capsuleBackground(COLOR_GLASS, COLOR_GLASS_STROKE, dp(28)));
        sheet.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(0, 0, 0, dp(10));
        LinearLayout[] panels = {buildPhotoPanel(), buildClockPanel(), buildEffectsPanel()};
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        FrameLayout host = new FrameLayout(this);
        scroll.addView(host);
        for (LinearLayout panel : panels) host.addView(panel);
        int[] labels = {R.string.depth_tab_photo, R.string.depth_tab_clock,
                R.string.depth_tab_adjust};
        LiquidPill[] buttons = new LiquidPill[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int selected = i;
            LiquidPill button = new LiquidPill(this);
            buttons[i] = button;
            button.setLabel(getString(labels[i]));
            button.setChecked(i == 0);
            button.setOnClickListener(v -> {
                setRefinementMode(0);
                mPreview.setShowNotificationGuide(selected == 2);
                for (int j = 0; j < panels.length; j++) {
                    panels[j].setVisibility(j == selected ? View.VISIBLE : View.GONE);
                    buttons[j].setChecked(j == selected);
                }
                scroll.scrollTo(0, 0);
            });
            LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
            if (i > 0) tabLp.setMarginStart(dp(6));
            tabs.addView(button, tabLp);
            panels[i].setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        }
        sheet.addView(tabs);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        mApplyButton = new LiquidPill(this);
        mApplyButton.setColors(0xFFFFFFFF, COLOR_GLYPH_DARK);
        mApplyButton.setLabel(getString(R.string.depth_apply));
        mApplyButton.setEnabled(false);
        mApplyButton.setOnClickListener(v -> applyDepth());
        sheet.addView(mApplyButton, new LinearLayout.LayoutParams(-1, dp(52)));

        return sheet;
    }

    private LinearLayout editorPanel(int heading, int description) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(2), dp(4), dp(2), dp(16));
        TextView title = sectionLabel(getString(heading));
        title.setPadding(0, dp(4), 0, dp(8));
        panel.addView(title);
        TextView help = new TextView(this);
        help.setText(description);
        help.setTextColor(COLOR_GLYPH_DIM);
        help.setTextSize(13);
        help.setPadding(0, 0, 0, dp(12));
        panel.addView(help);
        return panel;
    }

    private LinearLayout buildPhotoPanel() {
        LinearLayout panel = editorPanel(R.string.depth_photo_heading,
                R.string.depth_studio_subtitle);
        LiquidPill photo = new LiquidPill(this);
        photo.setColors(0xFFFFFFFF, COLOR_GLYPH_DARK);
        photo.setLabel(getString(R.string.depth_pick_short));
        photo.setOnClickListener(v -> pickPhoto());
        panel.addView(photo, new LinearLayout.LayoutParams(-1, dp(48)));
        mSubjectChip = new LiquidPill(this);
        mSubjectChip.setLabel(getString(R.string.depth_subject_reselect));
        mSubjectChip.setCompact(true);
        mSubjectChip.setOnClickListener(v -> {
            if (mBusy) return;
            if (!mPreview.hasBase()) {
                pickPhoto();
                return;
            }
            startOutlineMode();
        });
        LinearLayout.LayoutParams subjectLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        subjectLp.setMargins(0, dp(8), 0, 0);
        panel.addView(mSubjectChip, subjectLp);

        LinearLayout refine = new LinearLayout(this);
        int[] labels = {R.string.depth_ai_keep, R.string.depth_ai_remove};
        for (int i = 0; i < labels.length; i++) {
            final int mode = i + 1;
            LiquidPill tool = new LiquidPill(this);
            tool.setLabel(getString(labels[i]));
            tool.setOnClickListener(v -> {
                if (mBusy) return;
                if (mLastPrompt == null) {
                    mStatus.setText(getString(R.string.depth_draw_first));
                    return;
                }
                setRefinementMode(mPreview.mBrushMode == mode ? 0 : mode);
            });
            mRefineTools[i] = tool;
            LinearLayout.LayoutParams toolLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
            if (i > 0) toolLp.setMargins(dp(8), 0, 0, 0);
            refine.addView(tool, toolLp);
        }
        refine.setPadding(0, dp(8), 0, dp(8));
        panel.addView(refine);
        TextView help = new TextView(this);
        help.setText(R.string.depth_refine_help);
        help.setTextColor(COLOR_GLYPH_DIM);
        help.setTextSize(13);
        panel.addView(help);
        return panel;
    }

    private LinearLayout buildClockPanel() {
        LinearLayout panel = editorPanel(R.string.depth_clock_heading,
                R.string.depth_clock_help);
        mDateToggle = new Switch(this);
        mDateToggle.setText(R.string.depth_show_date);
        mDateToggle.setTextColor(COLOR_GLYPH_LIGHT);
        mDateToggle.setChecked(PetalConfig.isDepthClockDateEnabled(this));
        mDateToggle.setOnCheckedChangeListener((button, checked) -> {
            Settings.System.putInt(getContentResolver(),
                    PetalConfig.KEY_DEPTH_CLOCK_DATE, checked ? 1 : 0);
            mPreview.setDateOn(checked);
        });
        panel.addView(mDateToggle, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout gallery = new LinearLayout(this);
        gallery.setOrientation(LinearLayout.VERTICAL);
        mPresetCards = new DepthWidgets.PresetCard[DepthPresets.ALL.length];
        LinearLayout row = null;
        for (int i = 0; i < DepthPresets.ALL.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                gallery.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            DepthPresets.Preset preset = DepthPresets.ALL[i];
            DepthWidgets.PresetCard card = new DepthWidgets.PresetCard(this, preset,
                    getString(preset.nameRes));
            card.setPick(() -> {
                DepthPresets.apply(this, preset);
                mPreview.reloadComposition();
                refreshSelections();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(112), 1f);
            lp.setMargins(dp(3), dp(3), dp(3), dp(8));
            row.addView(card, lp);
            mPresetCards[i] = card;
        }
        if (DepthPresets.ALL.length % 2 != 0) {
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        panel.addView(gallery);
        // hide the rest till someone wants a browse
        for (int i = 1; i < gallery.getChildCount(); i++) {
            gallery.getChildAt(i).setVisibility(View.GONE);
        }
        LiquidPill browse = new LiquidPill(this);
        browse.setLabel(getString(R.string.depth_browse_styles));
        browse.setOnClickListener(v -> {
            boolean expanded = gallery.getChildCount() > 1
                    && gallery.getChildAt(1).getVisibility() == View.VISIBLE;
            for (int i = 1; i < gallery.getChildCount(); i++) {
                gallery.getChildAt(i).setVisibility(expanded ? View.GONE : View.VISIBLE);
            }
            browse.setLabel(getString(expanded ? R.string.depth_browse_styles
                    : R.string.depth_collapse_styles));
            browse.setChecked(!expanded);
        });
        panel.addView(browse, new LinearLayout.LayoutParams(-1, dp(48)));
        DepthWidgets.FancySlider size = new DepthWidgets.FancySlider(this,
                getString(R.string.depth_tune_size), "%", 60, 160,
                PetalConfig.getDepthClockScalePct(this));
        mSizeSlider = size;
        size.setListener(value -> {
            Settings.System.putInt(getContentResolver(),
                    PetalConfig.KEY_DEPTH_CLOCK_SCALE, value);
            mPreview.invalidate();
        });
        panel.addView(size, new LinearLayout.LayoutParams(-1, dp(64)));
        // ink colours
        LinearLayout inks = new LinearLayout(this);
        inks.setOrientation(LinearLayout.HORIZONTAL);
        int[] palette = {
                DepthWidgets.InkSwatch.AUTO,
                0xFFFFFFFF,
                0xFF16161A,
                0xFFFF3B30,
                0xFFFFC94D,
                0xFF9BE8FF,
        };
        mInkSwatches = new DepthWidgets.InkSwatch[palette.length];
        for (int i = 0; i < palette.length; i++) {
            DepthWidgets.InkSwatch swatch = new DepthWidgets.InkSwatch(this, palette[i]);
            final int color = palette[i];
            swatch.setPick(() -> {
                if (color == DepthWidgets.InkSwatch.AUTO) {
                    Settings.System.putInt(getContentResolver(),
                            PetalConfig.KEY_DEPTH_CLOCK_COLOR, PetalConfig.DISABLED);
                    mPreview.setAutoInk();
                } else {
                    Settings.System.putInt(getContentResolver(),
                            PetalConfig.KEY_DEPTH_CLOCK_COLOR, color);
                    mPreview.setInk(color);
                }
                refreshSelections();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(48));
            lp.setMargins(dp(2), dp(8), dp(10), 0);
            inks.addView(swatch, lp);
            mInkSwatches[i] = swatch;
        }
        HorizontalScrollView inkScroll = new HorizontalScrollView(this);
        inkScroll.setHorizontalScrollBarEnabled(false);
        inkScroll.addView(inks);
        panel.addView(inkScroll);

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.HORIZONTAL);
        options.setPadding(0, dp(8), 0, 0);
        // stack the digits or not
        mStackChip = new LiquidPill(this);
        mStackChip.setLabel(getString(R.string.depth_layout_stacked));
        mStackChip.setCompact(true);
        mStackChip.setOnClickListener(v -> {
            int style = PetalConfig.getDepthClockStyle(this);
            boolean next = !(PetalConfig.isDepthClockStackVertical(this)
                    || org.petalos.config.DepthClockMaterials.isSquare(style));
            if (org.petalos.config.DepthClockMaterials.isSquare(style)) {
                Settings.System.putInt(getContentResolver(), PetalConfig.KEY_DEPTH_CLOCK_STYLE,
                        style - 4);
            }
            Settings.System.putInt(getContentResolver(),
                    PetalConfig.KEY_DEPTH_CLOCK_STACK, next ? 1 : 0);
            mPreview.invalidate();
            refreshSelections();
        });
        LinearLayout.LayoutParams stackLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        stackLp.setMargins(dp(8), dp(8), 0, 0);
        options.addView(mStackChip, stackLp);

        panel.addView(options);

        return panel;
    }

    private LinearLayout buildEffectsPanel() {
        LinearLayout panel = editorPanel(R.string.depth_effects_heading,
                R.string.depth_effects_help);
        DepthWidgets.FancySlider parallax = new DepthWidgets.FancySlider(this,
                getString(R.string.depth_motion_strength), "%", 0, 100, motionSliderValue());
        mMotionSlider = parallax;
        parallax.setListener(value -> {
            Settings.System.putInt(getContentResolver(),
                    PetalConfig.KEY_DEPTH_PARALLAX, value > 0 ? 1 : 0);
            if (value > 0) Settings.System.putInt(getContentResolver(),
                    PetalConfig.KEY_DEPTH_TILT_DP, 4 + Math.round((value - 1) * 20f / 99f));
        });
        panel.addView(parallax, new LinearLayout.LayoutParams(-1, dp(58)));
        DepthWidgets.FancySlider blur = new DepthWidgets.FancySlider(this,
                getString(R.string.depth_background_blur), "%", 0, 100, mBlurAmount);
        blur.setListener(value -> {
            mBlurAmount = value;
            getPreferences(0).edit().putInt("background_blur", value).apply();
            updateBlur();
        });
        panel.addView(blur, new LinearLayout.LayoutParams(-1, dp(58)));

        TextView notificationHeading = sectionLabel(getString(R.string.depth_notification_start));
        notificationHeading.setPadding(0, dp(20), 0, dp(8));
        panel.addView(notificationHeading);
        TextView notificationHelp = new TextView(this);
        notificationHelp.setText(R.string.depth_notification_help);
        notificationHelp.setTextColor(COLOR_GLYPH_DIM);
        notificationHelp.setTextSize(13);
        panel.addView(notificationHelp);
        mNotificationToggle = new Switch(this);
        mNotificationToggle.setText(R.string.depth_notification_custom);
        mNotificationToggle.setTextColor(COLOR_GLYPH_LIGHT);
        panel.addView(mNotificationToggle, new LinearLayout.LayoutParams(-1, dp(48)));
        mNotificationPositionLabel = new TextView(this);
        mNotificationPositionLabel.setTextColor(COLOR_GLYPH_DIM);
        mNotificationPositionLabel.setTextSize(13);
        panel.addView(mNotificationPositionLabel);
        mNotificationSlider = new SeekBar(this);
        mNotificationSlider.setId(View.generateViewId());
        mNotificationPositionLabel.setLabelFor(mNotificationSlider.getId());
        mNotificationSlider.setContentDescription(getString(R.string.depth_notification_start));
        mNotificationSlider.setMin(DepthNotificationPlacement.MIN_PERCENT);
        mNotificationSlider.setMax(DepthNotificationPlacement.MAX_PERCENT);
        mNotificationSlider.setProgress(getPreferences(0).getInt("notification_start_last",
                DepthNotificationPlacement.DEFAULT_PERCENT));
        mNotificationSlider.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        mNotificationSlider.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        panel.addView(mNotificationSlider, new LinearLayout.LayoutParams(-1, dp(48)));
        mNotificationSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar slider, int progress, boolean fromUser) {
                if (fromUser) saveNotificationStart(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar slider) {}
            @Override public void onStopTrackingTouch(SeekBar slider) {}
        });
        updateNotificationStartControls(PetalConfig.getDepthNotificationStartPct(this));
        mNotificationToggle.setOnCheckedChangeListener((button, checked) -> saveNotificationStart(
                checked ? mNotificationSlider.getProgress() : DepthNotificationPlacement.AUTOMATIC));

        LiquidPill disable = new LiquidPill(this);
        disable.setColors(0x29FF453A, COLOR_DANGER);
        disable.setLabel(getString(R.string.depth_disable_short));
        disable.setOnClickListener(v -> disableDepth());
        LinearLayout.LayoutParams disableLp = new LinearLayout.LayoutParams(-1, dp(48));
        disableLp.topMargin = dp(16);
        panel.addView(disable, disableLp);
        return panel;
    }

    private void saveNotificationStart(int percent) {
        percent = DepthNotificationPlacement.clampPercent(percent);
        Settings.System.putInt(getContentResolver(), PetalConfig.KEY_DEPTH_NOTIFICATION_START, percent);
        if (percent != DepthNotificationPlacement.AUTOMATIC) {
            getPreferences(0).edit().putInt("notification_start_last", percent).apply();
        }
        updateNotificationStartControls(percent);
    }

    private void updateNotificationStartControls(int percent) {
        boolean custom = percent != DepthNotificationPlacement.AUTOMATIC;
        mNotificationToggle.setChecked(custom);
        mNotificationSlider.setEnabled(custom);
        mNotificationSlider.setAlpha(custom ? 1f : 0.4f);
        if (custom) mNotificationSlider.setProgress(percent);
        String label = custom ? getString(R.string.depth_notification_percent, percent)
                : getString(R.string.depth_notification_automatic);
        mNotificationPositionLabel.setText(label);
        mNotificationSlider.setStateDescription(label);
        mPreview.setNotificationStart(percent);
    }

    private void setRefinementMode(int mode) {
        mPreview.mBrushMode = mode;
        for (int i = 0; i < mRefineTools.length; i++) {
            if (mRefineTools[i] != null) mRefineTools[i].setChecked(mode == i + 1);
        }
        mStatus.setText(getString(mode == 1 ? R.string.depth_keep_hint
                : mode == 2 ? R.string.depth_remove_hint
                : mPreview.getCutout() == null ? R.string.depth_hint_tap
                : R.string.depth_hint_drag_clock));
        mPreview.invalidate();
    }

    private int motionSliderValue() {
        return PetalConfig.isDepthParallaxEnabled(this)
                ? 1 + Math.round((PetalConfig.getDepthTiltDp(this) - 4) * 99f / 20f) : 0;
    }

    private void refreshSelections() {
        mSizeSlider.setValue(PetalConfig.getDepthClockScalePct(this));
        mMotionSlider.setValue(motionSliderValue());
        updateNotificationStartControls(PetalConfig.getDepthNotificationStartPct(this));
        int currentInk = PetalConfig.getDepthClockColor(this);
        boolean showDate = PetalConfig.isDepthClockDateEnabled(this);
        mDateToggle.setChecked(showDate);
        mPreview.setDateOn(showDate);
        boolean stacked = PetalConfig.isDepthClockStackVertical(this)
                || org.petalos.config.DepthClockMaterials.isSquare(
                        PetalConfig.getDepthClockStyle(this));
        mStackChip.setChecked(stacked);
        mStackChip.setLabel(getString(stacked ? R.string.depth_layout_stacked
                : R.string.depth_layout_horizontal));
        int style = PetalConfig.getDepthClockStyle(this);
        for (int i = 0; i < mPresetCards.length; i++) {
            mPresetCards[i].setCardSelected(DepthPresets.ALL[i].style == style
                    && DepthPresets.ALL[i].stackVertical == stacked);
        }
        for (int i = 0; i < mInkSwatches.length; i++) {
            int color = mInkSwatches[i].getColor();
            boolean selected =
                    (color == DepthWidgets.InkSwatch.AUTO && currentInk == PetalConfig.DISABLED)
                            || color == currentInk;
            mInkSwatches[i].setSwatchSelected(selected);
        }
    }

    private android.graphics.drawable.GradientDrawable capsuleBackground(int fill, int stroke,
            int radius) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        d.setStroke(Math.max(1, dp(1)), stroke);
        return d;
    }

    private TextView sectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(COLOR_GLYPH_DIM);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setAllCaps(true);
        label.setLetterSpacing(0.08f);
        return label;
    }

    
    private void restoreDraft() {
        // pull the raw mask back in
        File rawCutoutFile = new File(getFilesDir(), "cutout_raw.png");
        File baseFile = new File(getFilesDir(), "base.png");
        if (!baseFile.exists()) {
            // old drafts only saved a cutout
            baseFile = new File(getFilesDir(), "base.jpg");
        }
        File cutoutFile = rawCutoutFile.exists()
                ? rawCutoutFile : new File(getFilesDir(), "cutout.png");
        if (!baseFile.exists()) return;
        Bitmap base = BitmapFactory.decodeFile(baseFile.getAbsolutePath());
        if (base == null) return;
        mPreview.setBase(base);
        if (cutoutFile.exists()) {
            Bitmap cutout = BitmapFactory.decodeFile(cutoutFile.getAbsolutePath());
            if (cutout != null && cutout.hasAlpha() && validDraftCutout(cutout)) {
                mPreview.setCutout(cutout);
                mApplyButton.setEnabled(true);
                mStatus.setText(getString(R.string.depth_hint_drag_clock));
                return;
            }
        }
        mStatus.setText(getString(R.string.depth_hint_tap));
    }

    private boolean validDraftCutout(Bitmap bitmap) {
        float[] samples = new float[4096];
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                samples[y * 64 + x] = Color.alpha(bitmap.getPixel(
                        x * bitmap.getWidth() / 64, y * bitmap.getHeight() / 64)) / 255f;
            }
        }
        try {
            MaskAlpha.fromConfidence(samples);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void pickPhoto() {
        if (mBusy) return;
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(
                Intent.createChooser(intent, getString(R.string.depth_pick_photo)),
                REQUEST_PHOTO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PHOTO || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        Bitmap cropped = loadAndCrop(data.getData());
        if (cropped == null) {
            mStatus.setText(getString(R.string.depth_error));
            Toast.makeText(this, R.string.depth_error, Toast.LENGTH_SHORT).show();
            return;
        }
        mPreview.setBase(cropped);
        mLastPrompt = null;
        mApplyButton.setEnabled(false);
        startOutlineMode();
    }

    
    private Bitmap loadAndCrop(Uri uri) {
        try {
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int screenH = getResources().getDisplayMetrics().heightPixels;

            Bitmap src = android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(getContentResolver(), uri),
                    (decoder, info, source) -> {
                        decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                        int width = info.getSize().getWidth();
                        int height = info.getSize().getHeight();
                        float downscale = Math.min(1f, Math.min(screenW * 2f / width,
                                screenH * 2f / height));
                        decoder.setTargetSize(Math.max(1, Math.round(width * downscale)),
                                Math.max(1, Math.round(height * downscale)));
                    });
            float ratio = Math.max(screenW / (float) src.getWidth(),
                    screenH / (float) src.getHeight());
            Bitmap cropped = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(cropped);
            float width = src.getWidth() * ratio;
            float height = src.getHeight() * ratio;
            canvas.drawBitmap(src, null, new RectF((screenW - width) / 2f,
                    (screenH - height) / 2f, (screenW + width) / 2f,
                    (screenH + height) / 2f), new Paint(Paint.FILTER_BITMAP_FLAG));
            src.recycle();
            return cropped;
        } catch (Exception e) {
            return null;
        }
    }

    private void startOutlineMode() {
        if (mBusy) return;
        mPreview.mBrushMode = 3;
        mPreview.mOutlinePoints.clear();
        mSheet.setVisibility(View.GONE);
        mOutlineCancel.setVisibility(View.VISIBLE);
        mStatus.setText(getString(R.string.depth_outline_hint));
        mPreview.invalidate();
    }

    private void finishOutlineMode() {
        setRefinementMode(0);
        mPreview.mOutlinePoints.clear();
        mSheet.setVisibility(View.VISIBLE);
        mOutlineCancel.setVisibility(View.GONE);
        mStatus.setText(getString(mPreview.getCutout() == null
                ? R.string.depth_hint_tap : R.string.depth_hint_drag_clock));
        mPreview.invalidate();
    }

    @Override
    public void onBackPressed() {
        if (mOutlineCancel.getVisibility() == View.VISIBLE) {
            finishOutlineMode();
        } else {
            super.onBackPressed();
        }
    }

    private void onPreviewTap(float normX, float normY) {
        refineSubject(OutlinePrompt.tap(normX, normY));
    }

    private void refineSubject(OutlinePrompt prompt) {
        if (mBusy || !mPreview.hasBase()) return;
        mBusy = true;
        mApplyButton.setEnabled(false);
        mStatus.setText(getString(R.string.depth_segmenting));
        mStatus.setBusy(true);
        final Bitmap base = mPreview.getBase();
        final SegmenterHelper segmenter = mSegmenter;
        segmenter.getExecutor().execute(() -> {
            try {
                segmenter.ensureInitialized(getApplicationContext());
                Bitmap cutout = segmenter.cutout(base, prompt);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) {
                        cutout.recycle();
                        return;
                    }
                    mBusy = false;
                    mStatus.setBusy(false);
                    mLastPrompt = prompt;
                    mPreview.setCutout(cutout);
                    mApplyButton.setEnabled(true);
                    mStatus.setText(getString(R.string.depth_ai_ready));
                });
            } catch (Exception e) {
                android.util.Log.w(TAG, "MobileSAM failed", e);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    mBusy = false;
                    mStatus.setBusy(false);
                    mApplyButton.setEnabled(mPreview.getCutout() != null);
                    mStatus.setText(getString(R.string.depth_retry_subject));
                });
            }
        });
    }

    private void applyDepth() {
        Bitmap base = mPreview.getBase();
        Bitmap cutout = mPreview.getCutout();
        if (mBusy || base == null || cutout == null) {
            return;
        }
        Bitmap wallpaper = mBlurredBase != null ? mBlurredBase : base;
        Bitmap frost = mFrostedBase;
        Bitmap foreground = mBlendedCutout != null ? mBlendedCutout : cutout;
        mBusy = true;
        mApplyButton.setEnabled(false);
        mStatus.setBusy(true);
        mStatus.setText(getString(R.string.depth_applying));
        mSegmenter.getExecutor().execute(() -> {
            try {
                Bitmap raw = cutout;
                File rawFile = new File(getFilesDir(), "cutout_raw.png");
                savePngAtomic(raw, rawFile);
                File cutoutFile = new File(getFilesDir(), "cutout.png");
                Bitmap sized = foreground;
                savePngAtomic(sized, cutoutFile);

                File baseFile = new File(getFilesDir(), "base.png");
                // save base too so the preview can restore
                savePngAtomic(base, baseFile);

                if (frost != null) {
                    savePngAtomic(frost, new File(getFilesDir(), "frost.png"));
                }
                savePngAtomic(wallpaper, new File(getFilesDir(), "liquid.png"));
                DepthContentProvider.cacheLockscreenAsset(this, "liquid.png");
                DepthContentProvider.cacheLockscreenAsset(this, "cutout.png");
                DepthContentProvider.cacheLockscreenAsset(this, "frost.png");
                WallpaperManager wm = WallpaperManager.getInstance(this);
                boolean wasEnabled = PetalConfig.isDepthEnabled(this);
                Settings.System.putInt(getContentResolver(), PetalConfig.KEY_DEPTH_ENABLED, 1);
                try {
                    wm.setBitmap(wallpaper,
                            new Rect(0, 0, wallpaper.getWidth(), wallpaper.getHeight()), true,
                            WallpaperManager.FLAG_LOCK);
                } catch (Exception error) {
                    Settings.System.putInt(getContentResolver(),
                            PetalConfig.KEY_DEPTH_ENABLED, wasEnabled ? 1 : 0);
                    throw error;
                }

                Settings.System.putString(getContentResolver(),
                        PetalConfig.KEY_DEPTH_CUTOUT_URI, "content://org.petalos.depth/cutout.png");
                int generation = PetalConfig.getDepthGeneration(this) + 1;
                Settings.System.putInt(getContentResolver(),
                        PetalConfig.KEY_DEPTH_GENERATION, generation);
                Settings.System.putInt(getContentResolver(),
                        PetalConfig.KEY_DEPTH_ENABLED, 1);

                android.util.Log.i(TAG, "apply: wallpaper and depth settings published");
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    mStatus.setText(getString(R.string.depth_hint_drag_clock));
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.depth_applied)
                            .setMessage(R.string.depth_applied_body)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            } catch (Exception e) {
                android.util.Log.e(TAG, "apply failed", e);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    mStatus.setText(getString(R.string.depth_error));
                    Toast.makeText(this, String.valueOf(e), Toast.LENGTH_LONG).show();
                });
            } finally {
                runOnUiThread(() -> {
                    mBusy = false;
                    if (isDestroyed() || isFinishing()) return;
                    mStatus.setBusy(false);
                    mApplyButton.setEnabled(true);
                });
            }
        });
    }

    private void updateBlur() {
        Bitmap base = mPreview.getBase();
        mBlurredBase = base == null || mBlurAmount == 0 ? null
                : BackgroundBlur.apply(base, mBlurAmount);
        mPreview.mClock.setLiquidBackdrop(mBlurredBase != null ? mBlurredBase : base);
        updateFeather();
        mPreview.invalidate();
    }

    private void updateFeather() {
        Bitmap raw = mPreview.getCutout();
        mBlendedCutout = raw == null || mBlurAmount == 0 ? null
                : BackgroundBlur.feather(raw, mBlurAmount);
    }

    private void disableDepth() {
        if (mBusy) return;
        Settings.System.putInt(getContentResolver(), PetalConfig.KEY_DEPTH_ENABLED, 0);
        Toast.makeText(this, R.string.depth_disabled_toast, Toast.LENGTH_SHORT).show();
    }

    // write temp then rename, avoids torn png
    static void savePngAtomic(Bitmap bitmap, File target) throws java.io.IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new java.io.IOException("Could not save " + target.getName());
            }
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new java.io.IOException("rename failed for " + target.getName());
        }
    }

    // rebuild cutout.png from raw if it went missing or corrupt
    static boolean healCutout(File filesDir) {
        File cutout = new File(filesDir, "cutout.png");
        File raw = new File(filesDir, "cutout_raw.png");
        if (!raw.exists()) {
            return false;
        }
        if (cutout.exists()) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(cutout.getAbsolutePath(), opts);
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                return false; // cutout fine, nothing to heal
            }
            android.util.Log.w(TAG, "cutout.png is corrupt, rebuilding from cutout_raw.png");
        } else {
            android.util.Log.w(TAG, "cutout.png is missing, rebuilding from cutout_raw.png");
        }
        Bitmap bitmap = BitmapFactory.decodeFile(raw.getAbsolutePath());
        if (bitmap == null) {
            return false;
        }
        try {
            savePngAtomic(bitmap, cutout);
            return true;
        } catch (java.io.IOException e) {
            android.util.Log.w(TAG, "cutout heal failed", e);
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        mPreview.stopTicker();
        if (mSegmenter != null) {
            mSegmenter.close();
            mSegmenter = null;
        }
        super.onDestroy();
    }

    
    private class LiquidPill extends View {

        private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private int mFillColor = 0x2EFFFFFF;
        private int mStrokeColor = 0x1FFFFFFF;
        private int mTextColor = COLOR_GLYPH_LIGHT;
        private String mLabel = "";
        private boolean mCompact = false;
        private boolean mChecked = false;

        LiquidPill(android.content.Context context) {
            super(context);
            mTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            mTextPaint.setTextAlign(Paint.Align.CENTER);
            mTextPaint.setTextSize(sp(14));
            mTextPaint.setColor(COLOR_GLYPH_LIGHT);
            setClickable(true);
            setFocusable(true);
        }

        void setCompact(boolean compact) {
            mCompact = compact;
            mTextPaint.setTextSize(sp(compact ? 12 : 14));
            invalidate();
        }

        void setChecked(boolean checked) {
            mChecked = checked;
            if (checked) {
                mFillColor = 0xFFFFFFFF;
                mTextColor = COLOR_GLYPH_DARK;
            } else {
                mFillColor = 0x2EFFFFFF;
                mTextColor = COLOR_GLYPH_LIGHT;
            }
            setSelected(checked);
            invalidate();
        }

        void setColors(int fill, int text) {
            mFillColor = fill;
            mStrokeColor = 0x1FFFFFFF;
            mTextColor = text;
            mTextPaint.setColor(text);
            invalidate();
        }

        void setLabel(String label) {
            mLabel = label;
            setContentDescription(label);
            requestLayout();
            invalidate();
        }

        private int sp(int v) {
            return Math.round(v * getResources().getDisplayMetrics().scaledDensity);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int textWidth = (int) (mTextPaint.measureText(mLabel) + dp(28));
            setMeasuredDimension(
                    resolveSize(Math.max(textWidth, dp(56)), widthMeasureSpec),
                    MeasureSpec.getSize(heightMeasureSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = getHeight() / 2f;
            mFillPaint.setColor(isPressed() ? pressBlend(mFillColor) : mFillColor);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), r, r, mFillPaint);
            mStrokePaint.setColor(mStrokeColor);
            mStrokePaint.setStrokeWidth(Math.max(1, dp(1)));
            mStrokePaint.setStyle(Paint.Style.STROKE);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), r, r, mStrokePaint);
            mTextPaint.setColor(mTextColor);
            mTextPaint.setAlpha(isEnabled() ? 255 : 102);
            canvas.drawText(mLabel, getWidth() / 2f, getHeight() / 2f
                    - (mTextPaint.ascent() + mTextPaint.descent()) / 2f, mTextPaint);
        }

        private int pressBlend(int fill) {
            return Color.argb(Color.alpha(fill),
                    Color.red(fill) + 24, Color.green(fill) + 24, Color.blue(fill) + 24);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (isEnabled()) {
                        animate().scaleX(0.94f).scaleY(0.94f).setDuration(90)
                                .setInterpolator(null).start();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    animate().scaleX(1f).scaleY(1f).setDuration(220)
                            .setInterpolator(new OvershootInterpolator(2.4f)).start();
                    break;
                default:
                    break;
            }
            return super.onTouchEvent(event);
        }
    }

    
    private class StatusPillView extends TextView {
        StatusPillView(android.content.Context context) {
            super(context);
            setGravity(Gravity.CENTER);
            setTextSize(14);
            setTextColor(COLOR_GLYPH_LIGHT);
            setMaxLines(2);
            setEllipsize(android.text.TextUtils.TruncateAt.END);
            setPadding(dp(20), dp(4), dp(20), dp(4));
            setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        }

        void setBusy(boolean busy) {
            setAlpha(busy ? 0.7f : 1f);
        }
    }

    private class DepthPreviewView extends View {

        private static final long DROPLET_MS = 450L;

        private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint mDropletPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDragPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mEmptyClockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mEmptySubjectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mEmptyLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mDst = new RectF();
        private final DepthClockRenderer mClock = new DepthClockRenderer();
        private final android.os.Handler mHandler = new android.os.Handler(
                android.os.Looper.getMainLooper());
        private final java.text.SimpleDateFormat mDateFmt =
                new java.text.SimpleDateFormat("EEE d", Locale.getDefault());

        private int mBrushMode;
        private final List<Float> mOutlinePoints = new ArrayList<>();
        private final android.graphics.Path mOutlinePath = new android.graphics.Path();
        private final Paint mOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mAuroraPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Matrix mAuroraMatrix = new android.graphics.Matrix();
        private android.graphics.SweepGradient mAurora;
        private Bitmap mAuroraOutline;
        private final android.graphics.PorterDuffXfermode mAuroraMaskMode =
                new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN);
        private final Paint mAuroraMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private int mAuroraWidth;
        private Bitmap mBase;
        private Bitmap mCutout;
        private float mCutoutAlpha = 1f;
        private ValueAnimator mCutoutAnimator;

        private boolean mDateOn = true;
        
        private boolean mAutoInk = true;
        private int mInk = 0xFFFFFFFF;
        private float mClockAnchorX;
        private float mClockAnchorY;
        private boolean mDraggingClock;
        private boolean mDraggingNotificationStart;
        private boolean mShowNotificationGuide;
        private int mNotificationStartPercent = DepthNotificationPlacement.AUTOMATIC;
        private final Paint mNotificationGuidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mNotificationGuideRect = new RectF();

        private class Droplet {
            float x;
            float y;
            long startNanos;
        }

        private final List<Droplet> mDroplets = new ArrayList<>();

        DepthPreviewView(android.content.Context context) {
            super(context);
            reloadComposition();
            mEmptyClockPaint.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
            mEmptyClockPaint.setColor(0xFFFF5A36);
            mEmptyClockPaint.setTextAlign(Paint.Align.CENTER);
            mEmptySubjectPaint.setColor(0xFF18181C);
            mEmptyLabelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            mEmptyLabelPaint.setColor(0x99FFFFFF);
            mEmptyLabelPaint.setTextAlign(Paint.Align.CENTER);
            setOnTouchListener((v, event) -> {
                if (!mBusy && handleNotificationDrag(event)) return true;
                if (mBase == null || mBusy) return false;
                if (mBrushMode == 3) {
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) mOutlinePoints.clear();
                    if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE
                            || action == MotionEvent.ACTION_UP) {
                        int history = event.getHistorySize();
                        for (int i = 0; i < history; i++) {
                            addOutlinePoint(event.getHistoricalX(i), event.getHistoricalY(i));
                        }
                        addOutlinePoint(event.getX(), event.getY());
                        getParent().requestDisallowInterceptTouchEvent(true);
                        invalidate();
                    }
                    if (action == MotionEvent.ACTION_UP) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                        float[] loop = new float[mOutlinePoints.size()];
                        for (int i = 0; i < loop.length; i++) loop[i] = mOutlinePoints.get(i);
                        try {
                            OutlinePrompt prompt = OutlinePrompt.fromLoop(loop);
                            finishOutlineMode();
                            refineSubject(prompt);
                        } catch (IllegalArgumentException e) {
                            // leave the bad stroke up for a retry
                            mStatus.setText(getString(R.string.depth_outline_retry));
                            invalidate();
                        }
                    } else if (action == MotionEvent.ACTION_CANCEL) {
                        mOutlinePoints.clear();
                        getParent().requestDisallowInterceptTouchEvent(false);
                        invalidate();
                    }
                    return true;
                }
                if ((mBrushMode == 1 || mBrushMode == 2) && mLastPrompt != null) {
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        try {
                            refineSubject(mLastPrompt.corrected(event.getX() / getWidth(),
                                    event.getY() / getHeight(), mBrushMode == 1));
                        } catch (IllegalArgumentException e) {
                            mStatus.setText(getString(R.string.depth_draw_first));
                        }
                    }
                    return true;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (mCutout == null) {
                            addDroplet(event.getX(), event.getY());
                            onPreviewTap(event.getX() / getWidth(), event.getY() / getHeight());
                        } else {
                            mDraggingClock = true;
                            moveClock(event.getX(), event.getY());
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (mDraggingClock) moveClock(event.getX(), event.getY());
                        return mDraggingClock;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (mDraggingClock) {
                            moveClock(event.getX(), event.getY());
                            Settings.System.putInt(getContentResolver(),
                                    PetalConfig.KEY_DEPTH_CLOCK_X,
                                    Math.round(mClockAnchorX * 100f));
                            Settings.System.putInt(getContentResolver(),
                                    PetalConfig.KEY_DEPTH_CLOCK_ANCHOR,
                                    Math.round(mClockAnchorY * 100f));
                            mDraggingClock = false;
                            invalidate();
                            return true;
                        }
                        return false;
                    default:
                        return false;
                }
            });
        }

        private void addOutlinePoint(float x, float y) {
            x = Math.max(0, Math.min(1, x / getWidth()));
            y = Math.max(0, Math.min(1, y / getHeight()));
            int size = mOutlinePoints.size();
            if (size >= 2048) return;
            if (size >= 2 && Math.hypot(x - mOutlinePoints.get(size - 2),
                    y - mOutlinePoints.get(size - 1)) < 0.003) return;
            mOutlinePoints.add(x);
            mOutlinePoints.add(y);
        }

        private void moveClock(float x, float y) {
            if (getWidth() <= 0 || getHeight() <= 0) return;
            mClockAnchorX = Math.max(0.10f, Math.min(0.90f, Math.round(x / getWidth() * 20f) / 20f));
            mClockAnchorY = Math.max(0.05f, Math.min(0.85f, Math.round(y / getHeight() * 20f) / 20f));
            invalidate();
        }

        void setShowNotificationGuide(boolean show) {
            mShowNotificationGuide = show;
            invalidate();
        }

        void setNotificationStart(int percent) {
            mNotificationStartPercent = DepthNotificationPlacement.clampPercent(percent);
            invalidate();
        }

        private boolean handleNotificationDrag(MotionEvent event) {
            if (!mShowNotificationGuide || mBrushMode != 0 || getHeight() <= 0
                    || mNotificationStartPercent == DepthNotificationPlacement.AUTOMATIC) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                float guideY = getHeight() * mNotificationStartPercent / 100f;
                mDraggingNotificationStart = Math.abs(event.getY() - guideY) < getHeight() * 0.045f;
                if (!mDraggingNotificationStart) return false;
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            if (!mDraggingNotificationStart) return false;
            if (action == MotionEvent.ACTION_CANCEL) {
                updateNotificationStartControls(PetalConfig.getDepthNotificationStartPct(DepthStudioActivity.this));
            } else {
                int percent = DepthNotificationPlacement.clampPercent(Math.round(event.getY() / getHeight() * 100f));
                updateNotificationStartControls(percent);
                if (action == MotionEvent.ACTION_UP) saveNotificationStart(percent);
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mDraggingNotificationStart = false;
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            return true;
        }

        private void drawNotificationGuide(Canvas canvas) {
            if (!mShowNotificationGuide || mBrushMode != 0
                    || mNotificationStartPercent == DepthNotificationPlacement.AUTOMATIC) return;
            float width = getWidth();
            float y = getHeight() * mNotificationStartPercent / 100f;
            float inset = width * 0.045f;
            mNotificationGuidePaint.setColor(0xEEFFFFFF);
            mNotificationGuidePaint.setStrokeWidth(Math.max(1f, width * 0.005f));
            canvas.drawLine(inset, y, width - inset, y, mNotificationGuidePaint);
            mNotificationGuidePaint.setTextSize(width * 0.045f);
            canvas.drawText(getString(R.string.depth_notification_start), inset, y - width * 0.03f,
                    mNotificationGuidePaint);
            mNotificationGuideRect.set(width * 0.40f, y - width * 0.009f,
                    width * 0.60f, y + width * 0.009f);
            canvas.drawRoundRect(mNotificationGuideRect, width * 0.01f, width * 0.01f, mNotificationGuidePaint);
            float cardTop = y + width * 0.035f;
            float cardHeight = getHeight() * 0.065f;
            mNotificationGuidePaint.setColor(0xAA34343B);
            mNotificationGuideRect.set(inset, cardTop, width - inset, cardTop + cardHeight);
            canvas.drawRoundRect(mNotificationGuideRect, width * 0.045f, width * 0.045f, mNotificationGuidePaint);
            mNotificationGuidePaint.setColor(0xBBFFFFFF);
            canvas.drawCircle(inset + cardHeight * 0.5f, cardTop + cardHeight * 0.5f,
                    cardHeight * 0.18f, mNotificationGuidePaint);
            canvas.drawLine(inset + cardHeight, cardTop + cardHeight * 0.38f,
                    width * 0.70f, cardTop + cardHeight * 0.38f, mNotificationGuidePaint);
            mNotificationGuidePaint.setColor(0x66FFFFFF);
            canvas.drawLine(inset + cardHeight, cardTop + cardHeight * 0.65f,
                    width * 0.56f, cardTop + cardHeight * 0.65f, mNotificationGuidePaint);
        }

        void reloadComposition() {
            mClockAnchorX = PetalConfig.getDepthClockXPct(getContext()) / 100f;
            mClockAnchorY = PetalConfig.getDepthClockAnchorPct(getContext()) / 100f;
            invalidate();
        }

        void setClockAnchorX(float anchor) {
            mClockAnchorX = Math.max(0.10f, Math.min(0.90f, anchor));
            invalidate();
        }

        void setClockAnchorY(float anchor) {
            mClockAnchorY = Math.max(0.05f, Math.min(0.85f, anchor));
            invalidate();
        }

        void setBase(Bitmap base) {
            setRefinementMode(0);
            mBase = base;
            mFrostedBase = base == null ? null : BackgroundBlur.apply(base, 100);
            mClock.setBackdrop(mFrostedBase);
            updateBlur();
            setCutoutInternal(null, false);
            // mirror the stored ink into the editor
            mAutoInk = PetalConfig.getDepthClockColor(getContext()) == PetalConfig.DISABLED;
            if (!mAutoInk) {
                mInk = PetalConfig.getDepthClockColor(getContext());
            }
            invalidate();
        }

        void setCutout(Bitmap cutout) {
            setCutoutInternal(cutout, true);
        }

        void setDateOn(boolean on) {
            mDateOn = on;
            invalidate();
        }

        
        void setInk(int color) {
            mInk = color;
            mAutoInk = false;
            invalidate();
        }

        
        void setAutoInk() {
            mAutoInk = true;
            invalidate();
        }

        void startTicker() {
            mHandler.post(mTicker);
        }

        void stopTicker() {
            mHandler.removeCallbacks(mTicker);
        }

        private final Runnable mTicker = new Runnable() {
            @Override
            public void run() {
                invalidate();
                mHandler.postDelayed(this, 10_000L);
            }
        };

        
        private int resolveInk() {
            if (!mAutoInk || mBase == null || getWidth() == 0 || getHeight() == 0) {
                return mInk;
            }
            int y = Math.round(mBase.getHeight() * mClockAnchorY);
            int sampleW = Math.max(8, mBase.getWidth() / 6);
            long r = 0;
            long g = 0;
            long b = 0;
            int n = 0;
            for (int dy = -sampleW / 4; dy <= sampleW / 4; dy += 2) {
                int yy = y + dy;
                if (yy < 0 || yy >= mBase.getHeight()) continue;
                for (int dx = -sampleW; dx <= sampleW; dx += 4) {
                    int xx = Math.round(mBase.getWidth() * mClockAnchorX) + dx;
                    if (xx < 0 || xx >= mBase.getWidth()) continue;
                    int c = mBase.getPixel(xx, yy);
                    r += Color.red(c);
                    g += Color.green(c);
                    b += Color.blue(c);
                    n++;
                }
            }
            if (n == 0) {
                return 0xFFFFFFFF;
            }
            int lum = (int) ((r / n * 0.299f) + (g / n * 0.587f) + (b / n * 0.114f));
            return lum > 140 ? 0xFF16161A : 0xFFFFFFFF;
        }

        private Bitmap makeAuroraOutline(Bitmap cutout) {
            int width = Math.min(384, cutout.getWidth());
            int height = Math.max(1, Math.round(cutout.getHeight() * width / (float) cutout.getWidth()));
            Bitmap small = Bitmap.createScaledBitmap(cutout, width, height, true);
            int[] pixels = new int[width * height];
            small.getPixels(pixels, 0, width, 0, 0, width, height);
            byte[] alpha = new byte[pixels.length];
            for (int i = 0; i < pixels.length; i++) alpha[i] = (byte) (pixels[i] >>> 24);
            byte[] ring = MaskAlpha.outline(alpha, width, height);
            for (int i = 0; i < pixels.length; i++) pixels[i] = ((ring[i] & 255) << 24) | 0xffffff;
            if (small != cutout) small.recycle();
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        }

        private void setCutoutInternal(Bitmap cutout, boolean animate) {
            if (mCutoutAnimator != null) {
                mCutoutAnimator.cancel();
                mCutoutAnimator = null;
            }
            mCutout = cutout;
            mAuroraOutline = cutout == null ? null : makeAuroraOutline(cutout);
            updateFeather();
            if (cutout == null) {
                mCutoutAlpha = 1f;
                invalidate();
                return;
            }
            if (!animate) {
                mCutoutAlpha = 1f;
                invalidate();
                return;
            }
            mCutoutAlpha = 0f;
            mCutoutAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(420);
            mCutoutAnimator.setInterpolator(new OvershootInterpolator(0.6f));
            mCutoutAnimator.addUpdateListener(a -> {
                mCutoutAlpha = (float) a.getAnimatedValue();
                invalidate();
            });
            mCutoutAnimator.start();
        }

        private void addDroplet(float x, float y) {
            Droplet d = new Droplet();
            d.x = x;
            d.y = y;
            d.startNanos = System.nanoTime();
            mDroplets.add(d);
            postInvalidateOnAnimation();
        }

        boolean hasBase() {
            return mBase != null;
        }

        Bitmap getBase() {
            return mBase;
        }

        Bitmap getCutout() {
            return mCutout;
        }

        private float sp(int value) {
            return value * getResources().getDisplayMetrics().scaledDensity;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(COLOR_CANVAS);
            if (mBase == null) {
                float w = getWidth();
                float h = getHeight();
                mEmptyClockPaint.setTextSize(Math.min(w * 0.42f, h * 0.19f));
                canvas.drawText("05", w * 0.5f, h * 0.29f, mEmptyClockPaint);
                canvas.drawText("30", w * 0.5f, h * 0.45f, mEmptyClockPaint);
                // placeholder body under the clock
                canvas.drawCircle(w * 0.5f, h * 0.31f, w * 0.105f, mEmptySubjectPaint);
                RectF body = new RectF(w * 0.31f, h * 0.36f, w * 0.69f, h * 0.67f);
                canvas.drawOval(body, mEmptySubjectPaint);
                mEmptyLabelPaint.setTextSize(sp(13));
                canvas.drawText(getString(R.string.depth_empty_preview), w * 0.5f,
                        h * 0.72f, mEmptyLabelPaint);
                drawNotificationGuide(canvas);
                return;
            }

            int style = PetalConfig.getDepthClockStyle(getContext());
            // square presets force vertical
            boolean vertical = PetalConfig.isDepthClockStackVertical(getContext())
                    || org.petalos.config.DepthClockMaterials.isSquare(style);
            float anchorFrac = mClockAnchorY;
            float scale = PetalConfig.getDepthClockScalePct(getContext()) / 100f;
            int ink = resolveInk();

            java.util.Calendar cal = java.util.Calendar.getInstance();
            mDateFmt.setTimeZone(cal.getTimeZone());
            String hour = String.format(Locale.US, "%02d", cal.get(java.util.Calendar.HOUR_OF_DAY));
            String minute = String.format(Locale.US, "%02d", cal.get(java.util.Calendar.MINUTE));
            String date = mDateFmt.format(cal.getTime()).toUpperCase(Locale.getDefault());

            mDst.set(0, 0, getWidth(), getHeight());
            mPaint.setAlpha(255);
            canvas.drawBitmap(mBrushMode == 3 || mBlurredBase == null
                    ? mBase : mBlurredBase, null, mDst, mPaint);

            // clock goes behind the subject, like systemui
            float anchorXFrac = mClockAnchorX;
            if (mBrushMode != 3) mClock.draw(canvas, getWidth(), getHeight(), style, vertical, anchorXFrac,
                    anchorFrac, scale,
                    ink, mDateOn, vertical ? hour : hour + ":", minute, date);

            if (mCutout != null && mBrushMode != 3) {
                mPaint.setAlpha(Math.round(255f * Math.max(0f, Math.min(1f, mCutoutAlpha))));
                canvas.drawBitmap(mBlendedCutout != null ? mBlendedCutout : mCutout,
                        null, mDst, mPaint);
                mPaint.setAlpha(255);
            }

            drawNotificationGuide(canvas);

            if (mAuroraOutline != null && (mBrushMode == 1 || mBrushMode == 2)) {
                if (mAurora == null || mAuroraWidth != getWidth()) {
                    mAuroraWidth = getWidth();
                    mAurora = new android.graphics.SweepGradient(getWidth() / 2f, getHeight() / 2f,
                            new int[]{0xFF92E9DE, 0xFF9CBFFF, 0xFFC5A2EE, 0xFFEDA9CF,
                                    0xFFE9D6AA, 0xFF92E9DE}, null);
                }
                boolean animate = android.animation.ValueAnimator.areAnimatorsEnabled();
                long time = android.os.SystemClock.uptimeMillis();
                float phase = animate ? (time % 20000L) / 20000f : 0f;
                float pulse = animate ? 0.7f + 0.3f * (float) Math.sin(
                        2 * Math.PI * (time % 8000L) / 8000f) : 0.7f;
                mAuroraMatrix.setRotate(phase * 360f, getWidth() / 2f, getHeight() / 2f);
                mAurora.setLocalMatrix(mAuroraMatrix);
                mAuroraPaint.setShader(mAurora);
                mAuroraPaint.setAlpha(Math.round(230 * pulse));
                int layer = canvas.saveLayer(mDst, null);
                canvas.drawRect(mDst, mAuroraPaint);
                mAuroraMaskPaint.setXfermode(mAuroraMaskMode);
                canvas.drawBitmap(mAuroraOutline, null, mDst, mAuroraMaskPaint);
                canvas.restoreToCount(layer);
                mAuroraMaskPaint.setXfermode(null);
                if (isShown() && animate) postInvalidateDelayed(33L);
            }

            if (mBrushMode == 3 && mOutlinePoints.size() >= 2) {
                float tipX = mOutlinePoints.get(mOutlinePoints.size() - 2) * getWidth();
                float tipY = mOutlinePoints.get(mOutlinePoints.size() - 1) * getHeight();
                if (mOutlinePoints.size() >= 4) {
                    mOutlinePath.reset();
                    mOutlinePath.moveTo(mOutlinePoints.get(0) * getWidth(),
                            mOutlinePoints.get(1) * getHeight());
                    for (int i = 2; i < mOutlinePoints.size(); i += 2) {
                        mOutlinePath.lineTo(mOutlinePoints.get(i) * getWidth(),
                                mOutlinePoints.get(i + 1) * getHeight());
                    }
                    mOutlinePath.close();
                    mOutlinePaint.setStyle(Paint.Style.FILL);
                    mOutlinePaint.setColor(0x339BE8FF);
                    canvas.drawPath(mOutlinePath, mOutlinePaint);
                    mOutlinePaint.setStyle(Paint.Style.STROKE);
                    mOutlinePaint.setStrokeWidth(dp(2));
                    mOutlinePaint.setColor(0xFFBCEEFF);
                    canvas.drawPath(mOutlinePath, mOutlinePaint);
                }
                // pen tip dot
                mOutlinePaint.setStyle(Paint.Style.FILL);
                mOutlinePaint.setColor(0xFFBCEEFF);
                canvas.drawCircle(tipX, tipY, dp(5), mOutlinePaint);
                mOutlinePaint.setStyle(Paint.Style.STROKE);
                mOutlinePaint.setStrokeWidth(dp(2));
                canvas.drawCircle(tipX, tipY, dp(11), mOutlinePaint);
            }
            if (mDraggingClock) {
                mDragPaint.setStrokeWidth(dp(1));
                mDragPaint.setColor(0x30FFFFFF);
                for (int i = 1; i < 20; i++) {
                    float x = getWidth() * i / 20f;
                    float y = getHeight() * i / 20f;
                    canvas.drawLine(x, 0, x, getHeight(), mDragPaint);
                    canvas.drawLine(0, y, getWidth(), y, mDragPaint);
                }
                mDragPaint.setColor(COLOR_GOLD);
                canvas.drawLine(getWidth() * mClockAnchorX, 0,
                        getWidth() * mClockAnchorX, getHeight(), mDragPaint);
                canvas.drawLine(0, getHeight() * mClockAnchorY,
                        getWidth(), getHeight() * mClockAnchorY, mDragPaint);
            }
            boolean dropletsActive = false;
            long now = System.nanoTime();
            java.util.Iterator<Droplet> it = mDroplets.iterator();
            while (it.hasNext()) {
                Droplet d = it.next();
                float t = (now - d.startNanos) / (DROPLET_MS * 1_000_000f);
                if (t >= 1f) {
                    it.remove();
                    continue;
                }
                dropletsActive = true;
                float radius = dp(8) + dp(20) * t;
                int alpha = Math.round(0x55 * (1f - t));
                mDropletPaint.setColor(Color.argb(alpha, 0xD6, 0xF3, 0xF5));
                mDropletPaint.setStyle(Paint.Style.STROKE);
                mDropletPaint.setStrokeWidth(dp(2) * (1f - t) + dp(1));
                canvas.drawCircle(d.x, d.y, radius, mDropletPaint);
            }
            if (dropletsActive) {
                postInvalidateOnAnimation();
            }
        }
    }
}
