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

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import org.petalos.config.PetalConfig;

/** matches the systemui clock preview */
public final class ClockStylePickerDialog {

    private static final String SAMPLE_TIME = "09:41";

    private ClockStylePickerDialog() {}

    public static void show(Context context) {
        int current = PetalConfig.getLockClockStyle(context);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * context.getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, 0);

        TextView preview = new TextView(context);
        preview.setTextColor(0xFFFFFFFF);
        preview.setTextSize(64);
        preview.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        preview.setIncludeFontPadding(false);
        LinearLayout.LayoutParams previewLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(preview, previewLp);

        RadioGroup group = new RadioGroup(context);
        String[] styleNames = context.getResources().getStringArray(R.array.clock_style_entries);
        for (int i = 0; i < styleNames.length; i++) {
            RadioButton button = new RadioButton(context);
            button.setText(styleNames[i]);
            button.setId(i);
            group.addView(button);
        }
        group.check(current);
        group.setOnCheckedChangeListener(
                (g, checkedId) -> applyPreview(preview, checkedId));
        root.addView(
                group,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        applyPreview(preview, current);

        new AlertDialog.Builder(context)
                .setTitle(R.string.lockscreen_clock_style_title)
                .setView(root)
                .setPositiveButton(
                        android.R.string.ok,
                        (dialog, which) -> {
                            int style = group.getCheckedRadioButtonId();
                            PetalConfig.setLockClockStyle(context, style);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void applyPreview(TextView preview, int style) {
        Typeface typeface = typefaceFor(style);
        boolean singleLine = singleLineFor(style);
        boolean accent = style == 11;

        preview.setTypeface(typeface);
        preview.setText(singleLine ? SAMPLE_TIME : SAMPLE_TIME.replace(':', '\n'));
        preview.setTextColor(accent
                ? preview.getContext().getColor(R.color.petal_accent_primary)
                : 0xFFFFFFFF);
    }

    private static Typeface typefaceFor(int style) {
        switch (style) {
            case 1: return Typeface.create("sans-serif-thin", Typeface.NORMAL);
            case 2: return Typeface.create("sans-serif-black", Typeface.NORMAL);
            case 3: return Typeface.create("sans-serif-medium", Typeface.NORMAL);
            case 4: return Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 700, false);
            case 5: return Typeface.create("sans-serif-light", Typeface.NORMAL);
            case 6: return Typeface.create("sans-serif-condensed", Typeface.BOLD);
            case 7: return Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 100, false);
            case 8: return Typeface.create("monospace", Typeface.NORMAL);
            case 9: return Typeface.create("serif", Typeface.NORMAL);
            case 10: return Typeface.create("cursive", Typeface.NORMAL);
            case 11: return Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 600, false);
            default: return Typeface.create("sans-serif", Typeface.NORMAL);
        }
    }

    private static boolean singleLineFor(int style) {
        return style == 3 || style == 5 || style == 6;
    }
}
