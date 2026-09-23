/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.automation;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.petalos.automation.data.AutomationStore;
import org.petalos.automation.engine.AutomationEngine;
import org.petalos.automation.engine.PowerState;
import org.petalos.automation.model.Automation;

import java.util.List;

/** grid of rule bubbles under a glass master bubble. */
public final class MainActivity extends AppCompatActivity implements RuleAdapter.Listener {
    private List<Automation> mRules;
    private RuleAdapter mAdapter;
    private MaterialSwitch mMaster;
    private TextView mStatus;
    private TextView mEmpty;
    private boolean mUpdating;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        mMaster = findViewById(R.id.master_switch);
        mStatus = findViewById(R.id.master_status);
        mEmpty = findViewById(R.id.empty_view);

        mRules = AutomationStore.load(this);
        mAdapter = new RuleAdapter(mRules, this);
        final RecyclerView list = findViewById(R.id.rule_list);
        list.setLayoutManager(new GridLayoutManager(this, 2));
        list.setAdapter(mAdapter);

        mMaster.setOnCheckedChangeListener((button, checked) -> {
            if (!mUpdating) {
                PowerState.setEnabled(this, checked);
                refreshStatus();
            }
        });
        findViewById(R.id.master_card).setOnClickListener(v -> mMaster.toggle());
        findViewById(R.id.diagnostics).setOnClickListener(v ->
                startActivity(new Intent(this, DiagnosticsActivity.class)));

        refreshStatus();
        updateEmpty();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
        refreshStatus();
    }

    private void reload() {
        mRules.clear();
        mRules.addAll(AutomationStore.load(this));
        mAdapter.notifyDataSetChanged();
        updateEmpty();
    }

    private void updateEmpty() {
        mEmpty.setVisibility(mRules.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void refreshStatus() {
        final boolean on = PowerState.isEnabled(this);
        mUpdating = true;
        mMaster.setChecked(on);
        mUpdating = false;
        mStatus.setText(on ? R.string.master_on : R.string.master_off);
    }

    @Override
    public void onToggle(Automation rule, boolean enabled) {
        rule.enabled = enabled;
        AutomationStore.save(this, mRules);
        AutomationEngine.reconcile(this);
    }

    @Override
    public void onDelete(Automation rule) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(rule.name)
                .setMessage(R.string.delete_title)
                .setPositiveButton(R.string.delete_rule, (dialog, which) -> {
                    AutomationStore.delete(this, rule.id);
                    reload();
                    AutomationEngine.reconcile(this);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onEdit(Automation rule) {
        final Intent intent = new Intent(this, AutomationEditorActivity.class);
        intent.putExtra("rule_id", rule.id);
        startActivity(intent);
    }

    @Override
    public void onAdd() {
        startActivity(new Intent(this, AutomationEditorActivity.class));
    }
}
