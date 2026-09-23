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

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pro mode glass panel. Rows are created at runtime by the activity from the
 * device's real Camera2 ranges, so the controls always match the hardware.
 * Values are shown live next to each label.
 */
public class ProPanelView extends PetalGlassLayout {

    public interface OnProChangeListener {
        void onProChanged(String key, int value);
    }

    /** Formats a raw control value for display (e.g. nanoseconds to "1/250"). */
    public interface ValueFormatter {
        String format(int value);
    }

    private final LinearLayout mRows;
    private final Map<String, TextView> mValueLabels = new LinkedHashMap<>();
    private final Map<String, String> mSuffix = new LinkedHashMap<>();
    private final Map<String, ValueFormatter> mFormatters = new LinkedHashMap<>();
    private OnProChangeListener mListener;

    public ProPanelView(Context context) {
        this(context, null);
    }

    public ProPanelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        mRows = new LinearLayout(context);
        mRows.setOrientation(LinearLayout.VERTICAL);
        mRows.setPadding(pad, pad, pad, pad);
        addView(mRows, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    public void setOnProChangeListener(OnProChangeListener l) {
        mListener = l;
    }

    public void clearControls() {
        mRows.removeAllViews();
        mValueLabels.clear();
        mSuffix.clear();
        mFormatters.clear();
    }

    public void addControl(String key, String label, String suffix,
            int min, int max, int initial) {
        addControl(key, label, suffix, min, max, initial, null);
    }

    /**
     * Adds a manual control row.
     *
     * @param key       identifier echoed back through the listener.
     * @param label     human label (e.g. "ISO").
     * @param suffix    unit suffix appended to the live value (may be empty).
     * @param min       minimum value.
     * @param max       maximum value.
     * @param initial   starting value.
     * @param formatter optional custom value formatter.
     */
    public void addControl(String key, String label, String suffix,
            int min, int max, int initial, ValueFormatter formatter) {
        Context ctx = getContext();
        float density = getResources().getDisplayMetrics().density;
        if (formatter != null) {
            mFormatters.put(key, formatter);
        }

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = (int) (10 * density);
        row.setLayoutParams(rowLp);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(ctx);
        name.setText(label);
        name.setTextColor(0xCCFFFFFF);
        name.setTextSize(11f);
        name.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(name, nameLp);

        TextView value = new TextView(ctx);
        value.setTextColor(0xFFFFFFFF);
        value.setTextSize(11f);
        value.setGravity(Gravity.END);
        header.addView(value);
        mValueLabels.put(key, value);
        mSuffix.put(key, suffix == null ? "" : suffix);
        updateValue(key, initial);

        row.addView(header);

        SeekBar bar = new SeekBar(ctx);
        bar.setMax(Math.max(1, max - min));
        bar.setProgress(Math.max(0, Math.min(max - min, initial - min)));
        bar.getProgressDrawable().setColorFilter(0xFFFFC46B, PorterDuff.Mode.SRC_IN);
        bar.getThumb().setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (28 * density));
        row.addView(bar, barLp);

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = min + progress;
                updateValue(key, v);
                if (fromUser && mListener != null) {
                    mListener.onProChanged(key, v);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        mRows.addView(row);
    }

    private void updateValue(String key, int v) {
        TextView label = mValueLabels.get(key);
        if (label != null) {
            ValueFormatter formatter = mFormatters.get(key);
            if (formatter != null) {
                label.setText(formatter.format(v));
            } else {
                String suffix = mSuffix.get(key);
                label.setText(v + (suffix == null ? "" : suffix));
            }
        }
    }
}
