/* Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.petalos.hub;

import android.content.Context;
import android.provider.Settings;
import android.util.AttributeSet;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

/** keeps the bezel preview matching systemui */
public final class BezelPlacementPreference extends Preference {
    private String mEdgeKey;
    private String mAnchorKey;
    private int mDefaultEdge;
    private int mDefaultAnchor;
    private int mKind;

    public BezelPlacementPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.bezel_placement_preference);
        setPersistent(false);
        setSelectable(false);
    }

    public void configure(String edgeKey, String anchorKey, int defaultEdge,
            int defaultAnchor, int kind) {
        mEdgeKey = edgeKey;
        mAnchorKey = anchorKey;
        mDefaultEdge = defaultEdge;
        mDefaultAnchor = defaultAnchor;
        mKind = kind;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        if (mEdgeKey == null) return;
        BezelPlacementView preview = (BezelPlacementView) holder.findViewById(R.id.bezel_preview);
        RadioGroup edges = (RadioGroup) holder.findViewById(R.id.bezel_edges);
        SeekBar height = (SeekBar) holder.findViewById(R.id.bezel_height);
        TextView position = (TextView) holder.findViewById(R.id.bezel_position);
        int storedEdge = Settings.System.getInt(getContext().getContentResolver(),
                mEdgeKey, mDefaultEdge);
        int storedAnchor = Settings.System.getInt(getContext().getContentResolver(),
                mAnchorKey, mDefaultAnchor);
        final int[] current = {storedEdge == 0 ? 0 : 1,
                storedAnchor >= 0 && storedAnchor <= 100 ? storedAnchor : mDefaultAnchor};
        edges.setOnCheckedChangeListener(null);
        height.setOnSeekBarChangeListener(null);
        Runnable refresh = () -> {
            preview.setPosition(current[0], current[1], mKind);
            edges.check(current[0] == 0 ? R.id.bezel_left : R.id.bezel_right);
            height.setProgress(current[1]);
            position.setText(getContext().getString(R.string.bezel_position_summary,
                    getContext().getString(current[0] == 0 ? R.string.bezel_left : R.string.bezel_right),
                    current[1]));
        };
        BezelPlacementView.Listener update = (edge, percent) -> {
            if (edge != current[0]) {
                current[0] = edge;
                Settings.System.putInt(getContext().getContentResolver(), mEdgeKey, edge);
            }
            if (percent != current[1]) {
                current[1] = percent;
                Settings.System.putInt(getContext().getContentResolver(), mAnchorKey, percent);
            }
            refresh.run();
        };
        refresh.run();
        holder.findViewById(R.id.bezel_reset).setOnClickListener(
                view -> update.onPositionChanged(mDefaultEdge, mDefaultAnchor));
        preview.setListener(update);
        edges.setOnCheckedChangeListener((group, checkedId) -> {
            int edge = checkedId == R.id.bezel_left ? 0 : 1;
            if (edge != current[0]) update.onPositionChanged(edge, current[1]);
        });
        height.setContentDescription(getTitle() + ", " + getContext().getString(R.string.bezel_height));
        height.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) update.onPositionChanged(current[0], progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
    }
}
