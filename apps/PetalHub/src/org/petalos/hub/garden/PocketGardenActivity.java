/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.hub.garden;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.petalos.hub.R;

public class PocketGardenActivity extends Activity {
    private PocketGarden garden;
    private PocketGardenStore store;
    private PocketGardenView gardenView;
    private TextView dailyTitle;
    private TextView dailyMessage;
    private TextView status;
    private final ExecutorService persistence = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean pending;
    private boolean receiverRegistered;
    private final Runnable newDay = () -> {
        refreshGarden();
        scheduleNewDay();
    };
    private final BroadcastReceiver dateChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refreshGarden();
            scheduleNewDay();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences preferences = getSharedPreferences("pocket_garden", MODE_PRIVATE);
        store = new PocketGardenStore(new PocketGardenStore.Storage() {
            @Override public String read() { return preferences.getString("patch", null); }
            @Override public boolean write(String value) {
                SharedPreferences.Editor editor = preferences.edit();
                if (value == null) editor.remove("patch"); else editor.putString("patch", value);
                return editor.commit();
            }
        });
        garden = store.read();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundResource(R.drawable.petal_window_background);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(8), dp(24), dp(28));
        scroll.addView(page);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button(getString(R.string.garden_back));
        back.setText("\u2190");
        back.setTextSize(23);
        back.setContentDescription(getString(R.string.garden_back));
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(52), -2));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.petal_logo_dusk);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(28), dp(28));
        logoParams.setMarginStart(dp(12));
        header.addView(logo, logoParams);
        TextView brand = text(R.string.garden_eyebrow, 14);
        brand.setPadding(dp(8), 0, 0, 0);
        header.addView(brand);
        page.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView title = text(R.string.garden_title, 34);
        title.setTypeface(Typeface.create(getResources().getFont(R.font.petal_rubik), Typeface.BOLD));
        title.setTextColor(getColor(R.color.petal_settings_text));
        title.setPadding(0, dp(22), 0, dp(8));
        title.setAccessibilityHeading(true);
        page.addView(title);
        TextView hint = text(R.string.garden_hint, 15);
        hint.setPadding(0, 0, 0, dp(18));
        page.addView(hint);

        LinearLayout daily = new LinearLayout(this);
        daily.setOrientation(LinearLayout.VERTICAL);
        daily.setPadding(dp(18), dp(14), dp(18), dp(14));
        daily.setBackground(surface(getColor(R.color.petal_glass_tint_strong), getColor(R.color.petal_glass_rim_top)));
        dailyTitle = text(R.string.garden_daily_ready, 17);
        dailyTitle.setTextColor(getColor(R.color.petal_settings_text));
        dailyTitle.setAccessibilityHeading(true);
        daily.addView(dailyTitle);
        dailyMessage = text(R.string.garden_daily_hint, 14);
        dailyMessage.setPadding(0, dp(5), 0, 0);
        dailyMessage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        daily.addView(dailyMessage);
        page.addView(daily, new LinearLayout.LayoutParams(-1, -2));

        gardenView = new PocketGardenView(this, garden, this::tend);
        LinearLayout.LayoutParams gardenParams = new LinearLayout.LayoutParams(-1, -2);
        gardenParams.topMargin = dp(18);
        page.addView(gardenView, gardenParams);
        status = text(R.string.garden_status, 14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(16), 0, dp(12));
        page.addView(status);
        Button book = button(getString(R.string.garden_seed_book));
        book.setOnClickListener(view -> showSeedBook());
        page.addView(book, new LinearLayout.LayoutParams(-1, -2));
        TextView footer = text(R.string.garden_footer, 13);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(14), 0, 0);
        page.addView(footer);
        setContentView(scroll);
        updateDailyState();
    }

    private void tend(int plot) {
        if (pending || !garden.canTend(LocalDate.now())) return;
        pending = true;
        updateDailyState();
        persistence.execute(() -> {
            PocketGardenStore.Change change;
            try { change = store.tend(plot, LocalDate.now()); }
            catch (RuntimeException failed) { change = null; }
            PocketGardenStore.Change result = change;
            main.post(() -> {
                if (isDestroyed()) return;
                pending = false;
                if (result != null) garden = result.garden();
                updateDailyState();
                if (result == null || !result.saved()) {
                    dailyMessage.setText(R.string.garden_save_failed);
                } else if (result.action() != PocketGarden.Action.ALREADY_TENDED) {
                    dailyTitle.setText(switch (result.action()) {
                        case PLANTED -> R.string.garden_planted;
                        case WATERED -> R.string.garden_watered;
                        case BLOOMED -> R.string.garden_bloomed;
                        case PICKED -> R.string.garden_picked;
                        case ALREADY_TENDED -> R.string.garden_daily_resting;
                    });
                    gardenView.performHapticFeedback(result.action() == PocketGarden.Action.BLOOMED
                            ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.CLOCK_TICK);
                    gardenView.pulse(plot);
                }
            });
        });
    }

    private void refreshGarden() {
        if (!pending) garden = store.read();
        updateDailyState();
    }

    private void updateDailyState() {
        LocalDate today = LocalDate.now();
        boolean ready = garden.canTend(today);
        gardenView.showGarden(garden, today, pending);
        dailyTitle.setText(pending ? R.string.garden_saving : ready
                ? R.string.garden_daily_ready : R.string.garden_daily_resting);
        if (pending) dailyMessage.setText(R.string.garden_saving_hint);
        else if (ready) dailyMessage.setText(R.string.garden_daily_hint);
        else if (today.equals(garden.lastTendedDate())) {
            dailyMessage.setText(getString(R.string.garden_return_tomorrow, garden.lastTendedPlot() + 1));
        } else {
            dailyMessage.setText(getString(R.string.garden_return_date,
                    garden.lastTendedDate().plusDays(1).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))));
        }
        status.setText(getString(R.string.garden_status, garden.bloomCount(), garden.discoveryCount()));
    }

    private void showSeedBook() {
        String[] names = getResources().getStringArray(R.array.garden_flowers);
        String[] entries = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            entries[i] = !garden.discovered(i) ? getString(R.string.garden_undiscovered)
                    : garden.discoveredGolden(i) ? getString(R.string.garden_golden_found, names[i]) : names[i];
        }
        new AlertDialog.Builder(this).setTitle(R.string.garden_seed_book)
                .setItems(entries, null).setPositiveButton(android.R.string.ok, null).show();
    }

    private TextView text(int resource, int size) {
        TextView view = new TextView(this);
        if (resource != R.string.garden_status) view.setText(resource);
        view.setTextSize(size);
        view.setTextColor(getColor(R.color.petal_settings_secondary));
        return view;
    }

    private GradientDrawable surface(int fill, int stroke) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(24));
        background.setColor(fill);
        background.setStroke(dp(1), stroke);
        return background;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setPadding(dp(16), dp(12), dp(16), dp(12));
        button.setTextColor(getColor(R.color.petal_accent_text));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(getColor(R.color.petal_ripple)),
                surface(getColor(R.color.petal_badge_fill), getColor(R.color.petal_badge_stroke)), null));
        return button;
    }

    private void scheduleNewDay() {
        main.removeCallbacks(newDay);
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime tomorrow = now.toLocalDate().plusDays(1).atStartOfDay(ZoneId.systemDefault());
        main.postDelayed(newDay, Math.max(1000, Duration.between(now.toInstant(), tomorrow.toInstant()).toMillis()));
    }

    @Override protected void onResume() {
        super.onResume();
        gardenView.setActive(true);
        refreshGarden();
        IntentFilter dates = new IntentFilter(Intent.ACTION_DATE_CHANGED);
        dates.addAction(Intent.ACTION_TIME_CHANGED);
        dates.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(dateChanged, dates, Context.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
        scheduleNewDay();
    }

    @Override protected void onPause() {
        gardenView.setActive(false);
        main.removeCallbacks(newDay);
        if (receiverRegistered) unregisterReceiver(dateChanged);
        receiverRegistered = false;
        super.onPause();
    }

    @Override protected void onDestroy() {
        persistence.shutdown();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
