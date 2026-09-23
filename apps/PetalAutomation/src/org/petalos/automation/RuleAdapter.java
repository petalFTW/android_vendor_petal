/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.automation;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;

import org.petalos.automation.model.Automation;

import java.util.List;

/** grid of petal glass bubbles: one per rule, plus the add bubble. */
public final class RuleAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_RULE = 0;
    private static final int TYPE_ADD = 1;

    /** badges cycle through these, same as the hub */
    private static final int[] ACCENTS = {
            R.color.petal_accent_violet,
            R.color.petal_accent_pink,
            R.color.petal_accent_teal,
    };

    public interface Listener {
        void onToggle(Automation rule, boolean enabled);

        void onDelete(Automation rule);

        void onEdit(Automation rule);

        void onAdd();
    }

    private final List<Automation> mRules;
    private final Listener mListener;

    public RuleAdapter(List<Automation> rules, Listener listener) {
        mRules = rules;
        mListener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        return position == mRules.size() ? TYPE_ADD : TYPE_RULE;
    }

    @Override
    public int getItemCount() {
        return mRules.size() + 1;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_ADD) {
            return new AddHolder(inflater.inflate(R.layout.item_add, parent, false));
        }
        return new RuleHolder(inflater.inflate(R.layout.item_rule, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        final Context context = holder.itemView.getContext();
        if (holder instanceof AddHolder) {
            final int accent = context.getColor(ACCENTS[position % ACCENTS.length]);
            ((AddHolder) holder).icon.setImageTintList(ColorStateList.valueOf(accent));
            holder.itemView.setOnClickListener(v -> mListener.onAdd());
            return;
        }

        final RuleHolder ruleHolder = (RuleHolder) holder;
        final Automation rule = mRules.get(position);
        final int accent = context.getColor(ACCENTS[position % ACCENTS.length]);

        ruleHolder.icon.setImageTintList(ColorStateList.valueOf(accent));
        ruleHolder.name.setText(rule.name == null || rule.name.isEmpty() ? "(unnamed)" : rule.name);
        ruleHolder.trigger.setText(rule.trigger.describe());

        ruleHolder.enabled.setOnCheckedChangeListener(null);
        ruleHolder.enabled.setChecked(rule.enabled);
        ruleHolder.enabled.setOnCheckedChangeListener(
                (button, checked) -> mListener.onToggle(rule, checked));

        ruleHolder.delete.setOnClickListener(v -> mListener.onDelete(rule));
        holder.itemView.setOnClickListener(v -> mListener.onEdit(rule));
    }

    static final class RuleHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView trigger;
        final MaterialSwitch enabled;
        final ImageButton delete;

        RuleHolder(@NonNull View view) {
            super(view);
            icon = view.findViewById(R.id.row_icon);
            name = view.findViewById(R.id.row_name);
            trigger = view.findViewById(R.id.row_trigger);
            enabled = view.findViewById(R.id.row_enabled);
            delete = view.findViewById(R.id.row_delete);
        }
    }

    static final class AddHolder extends RecyclerView.ViewHolder {
        final ImageView icon;

        AddHolder(@NonNull View view) {
            super(view);
            icon = view.findViewById(R.id.add_icon);
        }
    }
}
