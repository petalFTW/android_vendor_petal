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

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.ColorStateList;
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

/** base for all the hub preference screens */
public abstract class PetalPreferenceFragment extends PreferenceFragmentCompat {

    private static final float ROW_RADIUS_DP = 24f;

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

    // recycle-safe grouped surfaces, just like the Settings rows
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
        list.addOnChildAttachStateChangeListener(
                new RecyclerView.OnChildAttachStateChangeListener() {
                    @Override
                    public void onChildViewAttachedToWindow(View child) {
                        // position isn't valid until laid out, so wait a frame
                        child.post(() -> decorateRow(list, child));
                    }

                    @Override
                    public void onChildViewDetachedFromWindow(View child) {
                    }
                });
        list.post(() -> {
            for (int i = 0; i < list.getChildCount(); i++) {
                decorateRow(list, list.getChildAt(i));
            }
        });
        return list;
    }

    private void decorateRow(RecyclerView list, View child) {
        // posted callbacks can arrive after leaving this screen
        final Context context = getContext();
        if (context == null) {
            return;
        }
        if (!(child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        final float density = context.getResources().getDisplayMetrics().density;
        final int side = Math.round(16f * density);
        final int gap = Math.round(8f * density);
        final float radiusPx = ROW_RADIUS_DP * density;

        Preference preference = null;
        int position = list.getChildAdapterPosition(child);
        PreferenceGroupAdapter adapter = list.getAdapter() instanceof PreferenceGroupAdapter
                ? (PreferenceGroupAdapter) list.getAdapter() : null;
        if (adapter != null && position != RecyclerView.NO_POSITION) {
            preference = adapter.getItem(position);
        }
        // adapter isn't attached yet? leave the widget alone
        if (preference == null) {
            return;
        }
        final boolean category = preference instanceof PreferenceCategory;
        boolean first = position == 0 || !sameGroup(preference, adapter.getItem(position - 1));
        boolean last = position + 1 == adapter.getItemCount()
                || !sameGroup(preference, adapter.getItem(position + 1));

        ViewGroup.MarginLayoutParams params =
                (ViewGroup.MarginLayoutParams) child.getLayoutParams();
        params.leftMargin = category ? 0 : side;
        params.rightMargin = category ? 0 : side;
        params.topMargin = category || first ? gap : 0;
        params.bottomMargin = !category && last ? gap : 0;
        child.setLayoutParams(params);

        if (category) {
            child.setBackground(null);
            return;
        }

        child.setBackground(buildRowBackground(context, first ? radiusPx : 0f,
                last ? radiusPx : 0f));
        child.setMinimumHeight(Math.round(72f * density));
        child.setClipToOutline(false);
        child.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
    }

    private static boolean sameGroup(Preference item, Preference other) {
        return other != null && !(other instanceof PreferenceCategory)
                && item.getParent() == other.getParent();
    }

    private Drawable buildRowBackground(Context context, float top, float bottom) {
        float[] radii = {top, top, top, top, bottom, bottom, bottom, bottom};
        GradientDrawable surface = new GradientDrawable();
        surface.setCornerRadii(radii);
        surface.setColor(context.getColor(R.color.petal_settings_surface));
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadii(radii);
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(
                ColorStateList.valueOf(context.getColor(R.color.petal_settings_ripple)), surface, mask);
    }

    protected void toast(CharSequence message) {
        android.widget.Toast.makeText(requireContext(), message,
                android.widget.Toast.LENGTH_SHORT).show();
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
