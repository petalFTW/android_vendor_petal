/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.petalos.hub;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.petalos.proton4a.ProtonRunner;
import org.petalos.proton4a.ProgramStore;
import org.petalos.proton4a.RuntimeManager;

import java.io.IOException;

/** Launcher entry point for one imported Windows program. */
public class Proton4ARunActivity extends Activity {
    private TextView status;
    private Proton4ADisplayView display;
    private LinearLayout controls;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        display = new Proton4ADisplayView(this);
        status = new TextView(this);
        status.setPadding(48, 48, 48, 48);
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.TOP);
        status.setTextSize(16);

        FrameLayout layout = new FrameLayout(this);
        layout.setBackgroundColor(Color.BLACK);
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            layout.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        layout.addView(display, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layout.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackgroundColor(0xCC222222);
        controls.setVisibility(View.GONE);
        Button keyboard = new Button(this);
        keyboard.setText("Keyboard");
        controls.addView(keyboard);
        EditText textInput = new EditText(this);
        textInput.setSingleLine(true);
        textInput.setHint("Type in Windows app");
        textInput.setVisibility(android.view.View.GONE);
        controls.addView(textInput);
        keyboard.setOnClickListener(view -> {
            textInput.setVisibility(android.view.View.VISIBLE);
            textInput.requestFocus();
            InputMethodManager keyboardManager =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            keyboardManager.showSoftInput(textInput, InputMethodManager.SHOW_IMPLICIT);
        });
        textInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) {
                if (text.length() == 0) return;
                String typed = text.toString();
                text.clear();
                ProtonRunner.input(Proton4ARunActivity.this, "text", typed);
            }
        });
        textInput.setOnEditorActionListener((view, actionId, event) -> {
            ProtonRunner.input(this, "key", "Return");
            return true;
        });
        FrameLayout.LayoutParams controlParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        layout.addView(controls, controlParams);
        setContentView(layout);

        String name = getIntent().getStringExtra(ProgramShortcuts.EXTRA_PROGRAM_NAME);
        String programId = getIntent().getStringExtra(ProgramShortcuts.EXTRA_PROGRAM_ID);
        String packageName = getIntent().getStringExtra(
                "org.petalos.proton4a.PROGRAM_PACKAGE");
        if (packageName != null) {
            status.setText(R.string.proton4a_importing);
            new Thread(() -> {
                try {
                    ProgramStore.ImportedProgram program = ProgramStore.installPackaged(
                            this, packageName);
                    String label = packageName;
                    for (ProgramStore.Program entry : ProgramStore.list(this)) {
                        if (entry.id.equals(program.id)) label = entry.name;
                    }
                    String programName = label;
                    runOnUiThread(() -> launchProgram(program.id, programName));
                } catch (IOException exception) {
                    runOnUiThread(() -> status.setText(getString(R.string.proton4a_run_failed,
                            packageName, exception.getMessage())));
                }
            }, "Proton4A-package").start();
            return;
        }
        RuntimeManager.Status runtime = RuntimeManager.status(this);
        if (!runtime.installed) {
            status.setText(R.string.proton4a_run_no_runtime);
            return;
        }
        if (programId == null) {
            status.setText(getString(R.string.proton4a_run_pending, name));
            return;
        }
        launchProgram(programId, name);
    }

    private void launchProgram(String programId, String name) {
        if (!RuntimeManager.status(this).installed) {
            status.setText(R.string.proton4a_run_no_runtime);
            return;
        }
        status.setText(getString(R.string.proton4a_run_starting, name));
        controls.setVisibility(View.VISIBLE);
        status.setBackgroundColor(0xCC222222);
        display.setOnFrameAvailable(() -> status.setVisibility(android.view.View.GONE));
        display.setSource(ProtonRunner.framebufferFile(this));
        new Thread(() -> {
            String result;
            try {
                ProtonRunner.initialize(this).waitFor();
                int code = ProtonRunner.launch(this, programId).waitFor();
                result = getString(R.string.proton4a_run_finished, name, code,
                        ProtonRunner.logFile(this, "run").getAbsolutePath());
            } catch (IOException exception) {
                result = getString(R.string.proton4a_run_failed, name, exception.getMessage());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
            String text = result;
            runOnUiThread(() -> {
                status.setVisibility(android.view.View.VISIBLE);
                status.setText(text);
            });
        }, "Proton4A-run").start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
