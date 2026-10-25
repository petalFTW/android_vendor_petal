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

package org.petalos.advanced;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroupAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.CustomDialogPreferenceCompat;

import java.util.UUID;

/** base for the advanced screens, hand paints rows as glass cards like the hub */
public abstract class PetalPreferenceFragment extends PreferenceFragmentCompat {

    private static final float ROW_RADIUS_DP = 20f;

    @Override
    public void onDisplayPreferenceDialog(Preference preference) {
        if (preference instanceof CustomDialogPreferenceCompat) {
            if (preference.getKey() == null) {
                preference.setKey(UUID.randomUUID().toString());
            }
            CustomDialogPreferenceCompat.CustomPreferenceDialogFragment fragment =
                    CustomDialogPreferenceCompat.CustomPreferenceDialogFragment
                            .newInstance(preference.getKey());
            fragment.setTargetFragment(this, 0);
            fragment.show(getParentFragmentManager(), "dialog_preference");
            return;
        }
        super.onDisplayPreferenceDialog(preference);
    }

/** hand-paints each row as a glass card */
    @Nullable
    @Override
    public RecyclerView onCreateRecyclerView(@NonNull LayoutInflater inflater,
            @NonNull ViewGroup parent, @Nullable Bundle savedInstanceState) {
        RecyclerView list = super.onCreateRecyclerView(inflater, parent, savedInstanceState);
        if (list == null) {
            return null;
        }
        list.setClipToPadding(false);
        list.setBackground(null);
        final PreferenceGroupAdapter adapter = (PreferenceGroupAdapter) list.getAdapter();
        list.addOnChildAttachStateChangeListener(
                new RecyclerView.OnChildAttachStateChangeListener() {
                    @Override
                    public void onChildViewAttachedToWindow(View child) {
                        // position isn't valid until laid out, so wait a frame
                        child.post(() -> decorateRow(list, adapter, child));
                    }

                    @Override
                    public void onChildViewDetachedFromWindow(View child) {
                    }
                });
        list.post(() -> {
            for (int i = 0; i < list.getChildCount(); i++) {
                decorateRow(list, adapter, list.getChildAt(i));
            }
        });
        return list;
    }

    private void decorateRow(RecyclerView list, PreferenceGroupAdapter adapter, View child) {
        if (!(child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        final float density = getResources().getDisplayMetrics().density;
        final int side = Math.round(10f * density);
        final int gap = Math.round(4f * density);
        final float radiusPx = ROW_RADIUS_DP * density;

        Preference preference = null;
        int position = list.getChildAdapterPosition(child);
        if (adapter != null && position != RecyclerView.NO_POSITION) {
            preference = adapter.getItem(position);
        }
        // no preference info yet -> leave SettingsLib's own background alone.
        // headers get no card, rows keep theirs, nobody gets a guessed glass.
        if (preference == null) {
            return;
        }
        final boolean category = preference instanceof PreferenceCategory;

        ViewGroup.MarginLayoutParams params =
                (ViewGroup.MarginLayoutParams) child.getLayoutParams();
        params.leftMargin = category ? 0 : side;
        params.rightMargin = category ? 0 : side;
        params.topMargin = gap;
        params.bottomMargin = gap;
        child.setLayoutParams(params);

        if (category) {
            child.setBackground(null);
            return;
        }

        child.setBackground(buildRowBackground(requireContext(), radiusPx));
        child.setClipToOutline(true);
        child.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
    }

    private Drawable buildRowBackground(Context context, float radiusPx) {
        PetalGlassDrawable glass = PetalGlass.newCardDrawable(context, ROW_RADIUS_DP,
                context.getColor(R.color.petal_glass_tint),
                context.getColor(R.color.petal_glass_rim_top),
                context.getColor(R.color.petal_glass_rim_bottom), 0x10);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(radiusPx);
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(
                ColorStateList.valueOf(context.getColor(R.color.petal_ripple)), glass, mask);
    }

    protected ContentResolver getContentResolver() {
        return requireContext().getContentResolver();
    }

    protected int getInt(String key, int defaultValue) {
        return Settings.System.getInt(getContentResolver(), key, defaultValue);
    }

    protected void putInt(String key, int value) {
        Settings.System.putInt(getContentResolver(), key, value);
    }

    protected String getStringSetting(String key) {
        return Settings.System.getString(getContentResolver(), key);
    }

    protected void putString(String key, String value) {
        Settings.System.putString(getContentResolver(), key, value);
    }

    protected static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    protected static int percent(int value, int max) {
        return Math.round(value * 100f / Math.max(1, max));
    }

    protected static String colorHex(int color) {
        return String.format(java.util.Locale.US, "#%06X", color & 0xFFFFFF);
    }
}
