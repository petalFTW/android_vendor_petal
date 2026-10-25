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

package org.petalos.hub;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemProperties;
import android.os.SystemClock;
import android.os.UserManager;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import android.animation.ObjectAnimator;

import org.petalos.hub.garden.PocketGardenActivity;

/** the petalOS version page, opened from the settings version row */
public class PetalVersionActivity extends Activity {

    private int mGardenTaps;
    private long mLastGardenTap;
    private ObjectAnimator mLogoBob;

    private static final Map<String, String> PRETTY_DEVICES = new HashMap<>();
    static {
        PRETTY_DEVICES.put("macanc", "OnePlus Ace 6T");
        PRETTY_DEVICES.put("macan", "OnePlus 15R");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.petal_version_activity);

        ((TextView) findViewById(R.id.petal_version_value)).setText(
                prop("ro.petal.version", getString(R.string.petal_version_fallback)));

        String type = prop("ro.petal.build.type", "UNOFFICIAL");
        TextView typeValue = findViewById(R.id.petal_build_type_value);
        typeValue.setText(type);
        // teal if we're official, pink means you built it in the shed
        boolean official = "OFFICIAL".equalsIgnoreCase(type);
        typeValue.setTextColor(getColor(official
                ? R.color.petal_accent_teal : R.color.petal_accent_pink));

        ((TextView) findViewById(R.id.petal_maintainer_value)).setText(
                prop("ro.petal.maintainer", "unknown"));
        ((TextView) findViewById(R.id.petal_creator_value)).setText(
                getString(R.string.petal_creator));
        ((TextView) findViewById(R.id.petal_device_value)).setText(getDeviceName());
        ((TextView) findViewById(R.id.petal_base_value)).setText(
                getString(R.string.petal_version_base_value,
                        Build.VERSION.RELEASE,
                        prop("ro.lineage.build.version", "23.2")));
        ((TextView) findViewById(R.id.petal_build_date_value)).setText(getBuildStamp());
        ((TextView) findViewById(R.id.petal_kernel_value)).setText(
                System.getProperty("os.version", "unknown"));
        ((TextView) findViewById(R.id.petal_patch_value)).setText(
                Build.VERSION.SECURITY_PATCH);

        ImageView logo = findViewById(R.id.petal_logo);
        logo.setImageResource(R.drawable.petal_logo_dusk);
        logo.setOnClickListener(v -> spinTheFlower(logo));

        playIntro(logo);
    }

    // everything drifts in, flower gets a lazy float after
    private void playIntro(ImageView logo) {
        View hero = findViewById(R.id.petal_version_hero);
        View version = findViewById(R.id.petal_version_value);
        View card = findViewById(R.id.petal_version_card);

        hero.setAlpha(0f);
        hero.setTranslationY(-dp(18));
        version.setAlpha(0f);
        card.setAlpha(0f);
        card.setTranslationY(dp(26));
        logo.setScaleX(0.55f);
        logo.setScaleY(0.55f);

        hero.animate().alpha(1f).translationY(0f)
                .setDuration(420L).setInterpolator(new DecelerateInterpolator()).start();
        logo.animate().scaleX(1f).scaleY(1f).setStartDelay(120L)
                .setDuration(620L).setInterpolator(new OvershootInterpolator(0.9f)).start();
        version.animate().alpha(1f).setStartDelay(230L).setDuration(420L).start();
        card.animate().alpha(1f).translationY(0f).setStartDelay(260L)
                .setDuration(480L).setInterpolator(new DecelerateInterpolator()).start();

        int[] rows = {
                R.id.petal_build_type_value,
                R.id.petal_maintainer_value,
                R.id.petal_creator_value,
                R.id.petal_device_value,
                R.id.petal_base_value,
                R.id.petal_build_date_value,
                R.id.petal_kernel_value,
                R.id.petal_patch_value,
        };
        long delay = 320L;
        for (int id : rows) {
            View row = findViewById(id);
            row.setAlpha(0f);
            row.setTranslationX(dp(14));
            row.animate().alpha(1f).translationX(0f)
                    .setStartDelay(delay).setDuration(360L)
                    .setInterpolator(new DecelerateInterpolator()).start();
            delay += 45L;
        }

        mLogoBob = ObjectAnimator.ofFloat(logo, "translationY", 0f, -dp(6), 0f);
        mLogoBob.setDuration(3400L);
        mLogoBob.setStartDelay(780L);
        mLogoBob.setRepeatCount(ObjectAnimator.INFINITE);
        mLogoBob.setInterpolator(new AccelerateDecelerateInterpolator());
        mLogoBob.start();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // spin the flower, wee
    private void spinTheFlower(ImageView logo) {
        logo.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        logo.animate()
                .rotationBy(360f)
                .setStartDelay(0L)
                .setDuration(650L)
                .setInterpolator(new OvershootInterpolator(1.1f))
                .start();
        long now = SystemClock.uptimeMillis();
        mGardenTaps = now - mLastGardenTap > 1500L ? 1 : mGardenTaps + 1;
        mLastGardenTap = now;
        if (mGardenTaps < 5) return;
        mGardenTaps = 0;
        UserManager users = getSystemService(UserManager.class);
        if (ActivityManager.isUserAMonkey()
                || (users != null && users.hasUserRestriction(UserManager.DISALLOW_FUN))) return;
        startActivity(new Intent(this, PocketGardenActivity.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mLogoBob != null) mLogoBob.resume();
    }

    @Override
    protected void onPause() {
        mGardenTaps = 0;
        if (mLogoBob != null) mLogoBob.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (mLogoBob != null) mLogoBob.cancel();
        super.onDestroy();
    }

    // props win, otherwise fall back to the baked-in Build.TIME
    private String getBuildStamp() {
        String date = prop("ro.petal.build.date", "").trim();
        String time = prop("ro.petal.build.time", "").trim();
        if (!date.isEmpty()) {
            try {
                Date parsed = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                        .parse(date + " " + (time.isEmpty() ? "00:00" : time));
                if (parsed != null) {
                    return new SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault())
                            .format(parsed);
                }
            } catch (Exception ignored) {
                // props are junk, don't care
            }
            return time.isEmpty() ? date : date + " " + time;
        }
        return new SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault())
                .format(new Date(Build.TIME));
    }

    private String getDeviceName() {
        // oplus never ships the marketname prop so we keep our own little map
        String market = prop("ro.vendor.oplus.marketname", "").trim();
        if (!market.isEmpty()) return market;
        String codename = prop("ro.petal.auth.device", Build.DEVICE);
        String pretty = PRETTY_DEVICES.get(codename);
        return pretty != null ? pretty : Build.MANUFACTURER + " " + Build.MODEL;
    }

    private static String prop(String key, String def) {
        try {
            return SystemProperties.get(key, def);
        } catch (Exception e) {
            return def;
        }
    }
}
