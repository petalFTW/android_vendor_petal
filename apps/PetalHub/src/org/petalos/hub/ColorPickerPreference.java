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

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.SeekBar;

import com.android.settingslib.CustomDialogPreferenceCompat;

/** tiny hsv picker, no external deps */
public class ColorPickerPreference extends CustomDialogPreferenceCompat {

    private static final int DEFAULT_COLOR = 0x1B1B1F;

    private int mColor = DEFAULT_COLOR;
    private final float[] mHsv = new float[3];

    private View mPreview;
    private SeekBar mHue;
    private SeekBar mSaturation;
    private SeekBar mValue;

    public ColorPickerPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setDialogLayoutResource(R.layout.color_picker_dialog);
        setPositiveButtonText(android.R.string.ok);
        setNegativeButtonText(android.R.string.cancel);
    }

    public int getColor() {
        return mColor;
    }

    public void setColor(int color) {
        if (mColor != color) {
            mColor = color;
            Color.colorToHSV(color, mHsv);
            notifyChanged();
        }
    }

    @Override
    protected void onBindDialogView(View view) {
        super.onBindDialogView(view);
        mPreview = view.findViewById(R.id.color_preview);
        mHue = view.findViewById(R.id.hue);
        mSaturation = view.findViewById(R.id.saturation);
        mValue = view.findViewById(R.id.value);

        Color.colorToHSV(mColor, mHsv);
        mHue.setProgress(Math.round(mHsv[0]));
        mSaturation.setProgress(Math.round(mHsv[1] * 100f));
        mValue.setProgress(Math.round(mHsv[2] * 100f));

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updatePreview();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
        mHue.setOnSeekBarChangeListener(listener);
        mSaturation.setOnSeekBarChangeListener(listener);
        mValue.setOnSeekBarChangeListener(listener);

        updatePreview();
    }

    private void updatePreview() {
        mHsv[0] = mHue.getProgress();
        mHsv[1] = mSaturation.getProgress() / 100f;
        mHsv[2] = mValue.getProgress() / 100f;
        GradientDrawable preview = new GradientDrawable();
        preview.setShape(GradientDrawable.RECTANGLE);
        preview.setCornerRadius(16f * mPreview.getResources().getDisplayMetrics().density);
        preview.setColor(Color.HSVToColor(mHsv));
        mPreview.setBackground(preview);
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        if (positiveResult) {
            int newColor = Color.HSVToColor(mHsv);
            if (callChangeListener(newColor)) {
                mColor = newColor;
            }
        }
    }
}
