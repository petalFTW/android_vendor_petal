/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.automation;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.petalos.automation.data.AutomationStore;
import org.petalos.automation.engine.PowerState;
import org.petalos.automation.trigger.EventReceiver;
import org.petalos.automation.widget.GlassCardView;

/** tells you whether the thing is actually awake or just pretending. */
public final class DiagnosticsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        final int margin = Math.round(20 * getResources().getDisplayMetrics().density);
        final int gap = Math.round(14 * getResources().getDisplayMetrics().density);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(margin, margin, margin, margin);

        final LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        final ImageButton back = new ImageButton(this);
        back.setBackgroundResource(R.drawable.petal_icon_badge);
        back.setImageResource(R.drawable.ic_back);
        back.setImageTintList(android.content.res.ColorStateList.valueOf(
                getColor(R.color.petal_accent_primary)));
        back.setContentDescription(getString(R.string.back));
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(
                Math.round(44 * getResources().getDisplayMetrics().density),
                Math.round(44 * getResources().getDisplayMetrics().density)));

        final TextView title = new TextView(this);
        title.setText(R.string.diagnostics);
        title.setTextSize(24);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        final LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMarginStart(gap);
        header.addView(title, titleParams);
        root.addView(header);

        final TextView text = new TextView(this);
        final int pad = Math.round(18 * getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, pad);
        text.setTextSize(14);
        text.setText(buildReport());

        final GlassCardView card = new GlassCardView(this);
        card.addView(text, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = gap;
        root.addView(card, cardParams);

        setContentView(root);
    }

    private String buildReport() {
        final StringBuilder out = new StringBuilder();
        out.append("petalOS automation\n\n");
        out.append("master: ").append(PowerState.isEnabled(this) ? "on" : "off").append('\n');
        out.append("event receiver: ").append(componentState()).append('\n');
        out.append("accessibility: ").append(enabledSetting(
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)).append('\n');
        out.append("notification listener: ").append(enabledSetting(
                Settings.Secure.ENABLED_NOTIFICATION_LISTENERS)).append("\n\n");
        out.append("last run\n").append(AutomationStore.stats(this)).append('\n');
        return out.toString();
    }

    private String componentState() {
        final int state = getPackageManager().getComponentEnabledSetting(
                new ComponentName(this, EventReceiver.class));
        switch (state) {
            case PackageManager.COMPONENT_ENABLED_STATE_ENABLED:
                return "enabled";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
                return "disabled";
            default:
                return "default";
        }
    }

    private String enabledSetting(String key) {
        final String value = Settings.Secure.getString(getContentResolver(), key);
        return value != null && value.contains(getPackageName()) ? "on" : "off";
    }
}
