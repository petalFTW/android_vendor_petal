/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.automation;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import org.petalos.automation.data.AutomationStore;
import org.petalos.automation.engine.AutomationEngine;
import org.petalos.automation.model.Action;
import org.petalos.automation.model.Automation;
import org.petalos.automation.model.Types;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** builder for one rule. dialogs stacked on dialogs, welcome to android. */
public final class AutomationEditorActivity extends AppCompatActivity {
    private static final String[] TRIGGER_TYPES = {
            Types.T_TIME, Types.T_APP_OPEN, Types.T_APP_CLOSE, Types.T_BATTERY, Types.T_POWER,
            Types.T_WIFI, Types.T_BLUETOOTH, Types.T_AIRPLANE, Types.T_SCREEN, Types.T_UNLOCK,
            Types.T_NOTIFICATION, Types.T_BOOT, Types.T_SHUTDOWN, Types.T_BUTTON,
    };
    private static final String[] ACTION_TYPES = {
            Types.A_LAUNCH, Types.A_CLOSE, Types.A_SETTING, Types.A_BRIGHTNESS, Types.A_VOLUME,
            Types.A_RINGER, Types.A_TAP, Types.A_SWIPE, Types.A_TEXT, Types.A_INTENT,
            Types.A_NOTIFY, Types.A_TOAST, Types.A_WAIT,
    };

    private Automation mRule;
    private final List<Action> mActions = new ArrayList<>();
    private EditText mName;
    private TextView mTriggerValue;
    private LinearLayout mActionContainer;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_editor);

        findViewById(R.id.back).setOnClickListener(v -> finish());

        mRule = loadRule();
        mActions.addAll(mRule.actions);

        mName = findViewById(R.id.rule_name);
        mTriggerValue = findViewById(R.id.trigger_value);
        mActionContainer = findViewById(R.id.action_container);
        mName.setText(mRule.name);

        final int accent = getColor(R.color.petal_accent_primary);
        ((ImageView) findViewById(R.id.trigger_icon)).setImageTintList(
                ColorStateList.valueOf(accent));

        findViewById(R.id.trigger_card).setOnClickListener(v -> configureTrigger());
        findViewById(R.id.add_action).setOnClickListener(v -> pickActionType());
        ((MaterialButton) findViewById(R.id.save)).setOnClickListener(v -> save());

        updateTrigger();
        renderActions();
    }

    private Automation loadRule() {
        final String id = getIntent().getStringExtra("rule_id");
        if (id != null) {
            for (Automation rule : AutomationStore.load(this)) {
                if (rule.id.equals(id)) {
                    return rule;
                }
            }
        }
        final Automation rule = new Automation();
        rule.trigger.params.put(Types.P_HOUR, "22");
        rule.trigger.params.put(Types.P_MINUTE, "0");
        return rule;
    }

    private void updateTrigger() {
        mTriggerValue.setText(mRule.trigger.describe());
    }

    private void renderActions() {
        mActionContainer.removeAllViews();
        final int accent = getColor(R.color.petal_accent_primary);
        for (int i = 0; i < mActions.size(); i++) {
            final int index = i;
            final Action action = mActions.get(i);
            final View row = getLayoutInflater().inflate(R.layout.item_action,
                    mActionContainer, false);
            ((TextView) row.findViewById(R.id.action_title)).setText(action.describe());
            ((ImageView) row.findViewById(R.id.action_icon)).setImageTintList(
                    ColorStateList.valueOf(accent));
            ((ImageButton) row.findViewById(R.id.action_edit))
                    .setOnClickListener(v -> configureAction(action));
            ((ImageButton) row.findViewById(R.id.action_remove)).setOnClickListener(v -> {
                mActions.remove(index);
                renderActions();
            });
            mActionContainer.addView(row);
        }
    }

    private void save() {
        mRule.name = mName.getText() == null ? "" : mName.getText().toString().trim();
        if (mRule.name.isEmpty()) {
            mRule.name = "Automation";
        }
        mRule.actions.clear();
        mRule.actions.addAll(mActions);

        final List<Automation> all = AutomationStore.load(this);
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(mRule.id)) {
                all.set(i, mRule);
                replaced = true;
            }
        }
        if (!replaced) {
            all.add(mRule);
        }
        AutomationStore.save(this, all);
        AutomationEngine.reconcile(this);
        finish();
    }

    // ---- trigger config ----

    private void configureTrigger() {
        switch (mRule.trigger.type) {
            case Types.T_TIME:
                showTimePicker();
                break;
            case Types.T_APP_OPEN:
            case Types.T_APP_CLOSE:
                pickApp(pkg -> {
                    mRule.trigger.params.put(Types.P_APP, pkg);
                    updateTrigger();
                });
                break;
            case Types.T_BATTERY:
                promptChoice("Battery direction", new String[]{"below", "above"}, choice -> {
                    mRule.trigger.params.put(Types.P_STATE, choice == 0 ? "below" : "above");
                    promptNumber("Battery level %", mRule.trigger.getInt(Types.P_LEVEL, 20),
                            level -> {
                                mRule.trigger.params.put(Types.P_LEVEL, String.valueOf(level));
                                updateTrigger();
                            });
                });
                break;
            case Types.T_POWER:
            case Types.T_SCREEN:
            case Types.T_WIFI:
            case Types.T_BLUETOOTH:
            case Types.T_AIRPLANE:
                promptChoice("When", new String[]{"turns on", "turns off"}, choice -> {
                    mRule.trigger.params.put(Types.P_ON, choice == 0 ? "1" : "0");
                    updateTrigger();
                });
                break;
            case Types.T_NOTIFICATION:
                pickApp(pkg -> {
                    mRule.trigger.params.put(Types.P_PACKAGE, pkg);
                    promptText("Text filter (optional)", mRule.trigger.get(Types.P_FILTER, ""),
                            filter -> {
                                mRule.trigger.params.put(Types.P_FILTER, filter);
                                updateTrigger();
                            });
                });
                break;
            case Types.T_BUTTON:
                promptChoice("Button", new String[]{"volume down x2", "volume up x2"}, choice -> {
                    mRule.trigger.params.put(Types.P_KEY,
                            choice == 0 ? "volume_down_double" : "volume_up_double");
                    updateTrigger();
                });
                break;
            default:
                Toast.makeText(this, "This trigger has no options", Toast.LENGTH_SHORT).show();
        }
    }

    private void showTimePicker() {
        final MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(DateFormat.is24HourFormat(this) ? TimeFormat.CLOCK_24H
                        : TimeFormat.CLOCK_12H)
                .setHour(mRule.trigger.getInt(Types.P_HOUR, 22))
                .setMinute(mRule.trigger.getInt(Types.P_MINUTE, 0))
                .setTitleText(R.string.trigger_title)
                .build();
        picker.addOnPositiveButtonClickListener(dialog -> {
            mRule.trigger.params.put(Types.P_HOUR, String.valueOf(picker.getHour()));
            mRule.trigger.params.put(Types.P_MINUTE, String.valueOf(picker.getMinute()));
            updateTrigger();
        });
        picker.show(getSupportFragmentManager(), "time");
    }

    // ---- action config ----

    private void pickActionType() {
        final String[] labels = new String[ACTION_TYPES.length];
        for (int i = 0; i < ACTION_TYPES.length; i++) {
            labels[i] = Types.actionLabel(ACTION_TYPES[i]);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.add_action)
                .setItems(labels, (dialog, which) -> {
                    final Action action = new Action(ACTION_TYPES[which]);
                    mActions.add(action);
                    configureAction(action);
                    renderActions();
                })
                .show();
    }

    private void configureAction(Action action) {
        switch (action.type) {
            case Types.A_LAUNCH:
            case Types.A_CLOSE:
                pickApp(pkg -> {
                    action.params.put(Types.P_APP, pkg);
                    renderActions();
                });
                break;
            case Types.A_SETTING:
                promptChoice("Setting", new String[]{"wifi", "bluetooth", "airplane", "rotation",
                        "flashlight", "dnd"}, choice -> {
                    final String[] keys = {"wifi", "bluetooth", "airplane", "rotation",
                            "flashlight", "dnd"};
                    action.params.put(Types.P_SETTING, keys[choice]);
                    promptChoice("Set to", new String[]{"on", "off"}, value -> {
                        action.params.put(Types.P_VALUE, value == 0 ? "1" : "0");
                        renderActions();
                    });
                });
                break;
            case Types.A_BRIGHTNESS:
            case Types.A_VOLUME:
                promptNumber("Percent (0-100)", action.getInt(Types.P_LEVEL, 50), level -> {
                    action.params.put(Types.P_LEVEL, String.valueOf(level));
                    renderActions();
                });
                break;
            case Types.A_RINGER:
                promptChoice("Mode", new String[]{"normal", "vibrate", "silent"}, choice -> {
                    final String[] modes = {"normal", "vibrate", "silent"};
                    action.params.put(Types.P_VALUE, modes[choice]);
                    renderActions();
                });
                break;
            case Types.A_TAP:
                promptNumber("X", action.getInt(Types.P_X, 540), x -> promptNumber("Y",
                        action.getInt(Types.P_Y, 1200), y -> {
                            action.params.put(Types.P_X, String.valueOf(x));
                            action.params.put(Types.P_Y, String.valueOf(y));
                            renderActions();
                        }));
                break;
            case Types.A_SWIPE:
                promptNumber("From X", action.getInt(Types.P_X, 540), x ->
                        promptNumber("From Y", action.getInt(Types.P_Y, 1600), y ->
                                promptNumber("To X", action.getInt(Types.P_X2, 540), x2 ->
                                        promptNumber("To Y", action.getInt(Types.P_Y2, 600), y2 -> {
                                            action.params.put(Types.P_X, String.valueOf(x));
                                            action.params.put(Types.P_Y, String.valueOf(y));
                                            action.params.put(Types.P_X2, String.valueOf(x2));
                                            action.params.put(Types.P_Y2, String.valueOf(y2));
                                            renderActions();
                                        }))));
                break;
            case Types.A_TEXT:
                promptText("Text to type", action.get(Types.P_TEXT, ""), text -> {
                    action.params.put(Types.P_TEXT, text);
                    renderActions();
                });
                break;
            case Types.A_INTENT:
                promptText("Intent action", action.get(Types.P_ACTION, Intent.ACTION_MAIN),
                        actionName -> promptText("Component (pkg/cls, optional)",
                                action.get(Types.P_COMPONENT, ""), component -> {
                                    action.params.put(Types.P_ACTION, actionName);
                                    action.params.put(Types.P_COMPONENT, component);
                                    renderActions();
                                }));
                break;
            case Types.A_NOTIFY:
                promptText("Title", action.get(Types.P_TITLE, "Automation"), title ->
                        promptText("Text", action.get(Types.P_TEXT, ""), text -> {
                            action.params.put(Types.P_TITLE, title);
                            action.params.put(Types.P_TEXT, text);
                            renderActions();
                        }));
                break;
            case Types.A_TOAST:
                promptText("Toast text", action.get(Types.P_TEXT, ""), text -> {
                    action.params.put(Types.P_TEXT, text);
                    renderActions();
                });
                break;
            case Types.A_WAIT:
                promptNumber("Seconds", action.getInt(Types.P_SECONDS, 1), seconds -> {
                    action.params.put(Types.P_SECONDS, String.valueOf(seconds));
                    renderActions();
                });
                break;
            default:
                renderActions();
        }
    }

    // ---- tiny material dialog helpers ----

    private void promptText(String title, String initial, Consumer<String> callback) {
        final EditText input = new EditText(this);
        input.setText(initial == null ? "" : initial);
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> callback.accept(input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void promptNumber(String title, int initial, IntConsumer callback) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(initial));
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    try {
                        callback.accept(Integer.parseInt(input.getText().toString().trim()));
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Numbers only", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void promptChoice(String title, String[] items, IntConsumer callback) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setItems(items, (dialog, which) -> callback.accept(which))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickApp(Consumer<String> callback) {
        final PackageManager pm = getPackageManager();
        final Intent launcher = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        final List<ResolveInfo> resolved = pm.queryIntentActivities(launcher, 0);
        Collections.sort(resolved, (a, b) -> a.loadLabel(pm).toString()
                .compareToIgnoreCase(b.loadLabel(pm).toString()));
        final String[] labels = new String[resolved.size() + 1];
        final String[] packages = new String[resolved.size() + 1];
        labels[0] = "Any app";
        packages[0] = "";
        for (int i = 0; i < resolved.size(); i++) {
            labels[i + 1] = resolved.get(i).loadLabel(pm).toString();
            packages[i + 1] = resolved.get(i).activityInfo.packageName;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Pick app")
                .setItems(labels, (dialog, which) -> callback.accept(packages[which]))
                .show();
    }
}
