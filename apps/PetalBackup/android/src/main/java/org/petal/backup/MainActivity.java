/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petal.backup;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.provider.DocumentsContract;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.petal.backup.core.BackupDraft;
import org.petal.backup.core.BackupDraft.Step;
import org.petal.backup.core.BackupResult;
import org.petal.backup.core.OperationJournal;
import org.petal.backup.crypto.RecoveryKeyEnvelope;

public final class MainActivity extends Activity implements BackupJobs.Listener {
    private static final int FILES = 11, SAVE = 12, ARCHIVE = 13, DESTINATION = 14, BROWSE = 15, ALERTS = 21;
    private final ExecutorService documents = Executors.newSingleThreadExecutor();
    private final CancellationSignal queryCancellation = new CancellationSignal();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, DocumentFiles.Info> metadata = new HashMap<>();
    private final Set<String> loading = new HashSet<>();
    private final List<Button> operationControls = new ArrayList<>();
    private BackupDraft draft;
    private Step screen;
    private BackupJobs jobs;
    private LinearLayout page;
    private TextView errorView;
    private TextView progressText;
    private ProgressBar progressBar;
    private EditText recovery;
    private EditText confirmation;
    private char[] recoveryNumber;
    private String selectedResult;
    private String inlineError;
    private boolean numericRecovery;
    private boolean startAfterPermission;
    private ScrollView contentScroll;
    private Step renderedScreen;
    private final Runnable metadataRefresh = () -> {
        if (!isDestroyed() && screen != Step.RECOVERY && screen != Step.PROGRESS) render();
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        jobs = BackupJobs.get(this);
        String encoded = saved == null ? preferences().getString("draft", null) : saved.getString("draft");
        draft = BackupDraft.decode(encoded);
        try {
            screen = Step.valueOf(saved == null ? preferences().getString("screen", "HOME") : saved.getString("screen", "HOME"));
        } catch (IllegalArgumentException invalid) { screen = Step.HOME; }
        if (screen == Step.REVIEW) {
            screen = Step.RECOVERY;
            inlineError = getString(R.string.reenter_recovery);
        }
        selectedResult = saved == null ? preferences().getString("result", null) : saved.getString("result");
        acceptIntent(getIntent());
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::back);
        render();
    }

    private android.content.SharedPreferences preferences() { return getSharedPreferences("ui", MODE_PRIVATE); }

    private void acceptIntent(Intent intent) {
        if (intent.getStringExtra("result_id") != null) {
            selectedResult = intent.getStringExtra("result_id");
            screen = Step.RESULT;
        } else if (jobs.running()) screen = Step.PROGRESS;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptIntent(intent);
        render();
    }

    private void persist() {
        preferences().edit().putString("draft", draft.encode()).putString("screen", screen.name())
                .putString("result", selectedResult).apply();
    }

    private void go(Step step) {
        inlineError = null;
        screen = step;
        if (step == Step.FILES || step == Step.LOCATION || step == Step.RECOVERY || step == Step.REVIEW) draft.step = step;
        persist();
        render();
    }

    private void render() {
        int scrollPosition = contentScroll != null && renderedScreen == screen ? contentScroll.getScrollY() : 0;
        renderedScreen = screen;
        recovery = null;
        confirmation = null;
        progressText = null;
        progressBar = null;
        operationControls.clear();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.surface));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(4), dp(20), 0);
        if (screen != Step.HOME && screen != Step.BACKUPS && screen != Step.SETTINGS) {
            Button back = button(top, R.string.back, false, this::back);
            back.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        } else {
            ImageView logo = new ImageView(this);
            logo.setImageResource(R.drawable.petal_logo);
            logo.setImageTintList(ColorStateList.valueOf(getColor(R.color.accent)));
            logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            top.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }
        text(top, getString(R.string.title), 18, false);
        root.addView(top);
        ScrollView scroll = new ScrollView(this);
        contentScroll = scroll;
        scroll.setFillViewport(true);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(12), dp(24), dp(24));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        errorView = text(page, inlineError == null ? "" : inlineError, 15, false);
        errorView.setTextColor(getColor(R.color.error));
        errorView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        errorView.setVisibility(inlineError == null ? View.GONE : View.VISIBLE);
        switch (screen) {
            case HOME -> home();
            case BACKUPS -> backups();
            case SETTINGS -> settings();
            case FILES -> files();
            case LOCATION -> location();
            case RECOVERY -> recovery();
            case REVIEW -> review();
            case PROGRESS -> progress();
            case RESULT -> result();
        }
        if (screen == Step.HOME || screen == Step.BACKUPS || screen == Step.SETTINGS) {
            LinearLayout nav = new LinearLayout(this);
            nav.setPadding(dp(12), 0, dp(12), dp(8));
            int[] labels = {R.string.home, R.string.backups, R.string.settings};
            Step[] destinations = {Step.HOME, Step.BACKUPS, Step.SETTINGS};
            for (int i = 0; i < labels.length; i++) {
                Step destination = destinations[i];
                Button tab = button(nav, labels[i], screen == destination, () -> go(destination));
                tab.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
                tab.setSelected(screen == destination);
            }
            root.addView(nav);
        }
        setContentView(root);
        root.requestApplyInsets();
        if (scrollPosition > 0) scroll.post(() -> scroll.scrollTo(0, scrollPosition));
        updateControls();
    }

    private void home() {
        heading(R.string.home_title);
        text(page, R.string.home_subtitle);
        primary(R.string.back_up_files, () -> begin(false));
        action(R.string.restore_files, () -> begin(true));
        if (jobs.running()) {
            LinearLayout card = card(page);
            title(card, R.string.operation_running);
            text(card, jobs.message(), 15, false);
            button(card, R.string.view_progress, false, () -> go(Step.PROGRESS));
        } else if (draft.isWizard()) {
            LinearLayout card = card(page);
            title(card, R.string.unfinished_setup);
            text(card, R.string.continue_setup_note);
            button(card, R.string.continue_setup, false, () -> go(draft.step));
        }
        if (!jobs.ready()) text(page, jobs.message(), 15, false);
        if (jobs.catalogueUnavailable()) text(page, R.string.catalogue_unavailable);
        for (BackupResult item : jobs.results()) {
            if (item.backupUri() != null) {
                title(page, R.string.latest_backup);
                backupCard(item);
                break;
            }
        }
        if (jobs.results().isEmpty()) text(card(page), R.string.first_backup_hint);
        title(page, R.string.recent_activity);
        int count = 0;
        for (BackupResult item : jobs.results()) {
            if (count++ == 3) break;
            historyCard(item);
        }
        if (count == 0) text(page, R.string.history_empty);
    }

    private void backups() {
        heading(R.string.your_backups);
        text(page, R.string.backups_explanation);
        primary(R.string.find_backup, () -> {
            begin(true);
            chooseArchive();
        });
        button(page, R.string.refresh_locations, false, () -> { metadata.clear(); render(); });
        Set<String> shown = new HashSet<>();
        for (BackupResult item : jobs.results()) if (item.backupUri() != null && shown.add(item.backupUri())) backupCard(item);
        if (shown.isEmpty()) text(card(page), R.string.no_backups);
        if (jobs.catalogueUnavailable()) text(page, R.string.catalogue_unavailable);
        title(page, R.string.recent_activity);
        for (BackupResult item : jobs.results()) historyCard(item);
    }

    private void backupCard(BackupResult item) {
        String uri = item.backupUri();
        DocumentFiles.Info info = info(uri, false);
        LinearLayout card = card(page);
        text(card, item.name(), 18, true);
        text(card, date(item.time()) + " · " + fileCount(item.files()), 14, false);
        text(card, item.bytes() < 0 ? getString(R.string.size_unknown) : size(item.bytes()), 14, false);
        text(card, item.kind() == OperationJournal.Kind.BACKUP ? R.string.verified_saved : R.string.verified_restored);
        text(card, info == null ? getString(R.string.checking_availability)
                : !info.available() ? getString(R.string.backup_unavailable) : label(uri), 14, false);
        Button restore = button(card, R.string.restore_files, false, () -> {
            begin(true);
            draft.archive = uri;
            persist();
            go(Step.FILES);
        });
        operationControls.add(restore);
        if (info != null && !info.available()) restore.setTag("unavailable");
        button(card, R.string.open_location, false, () -> openLocation(uri));
    }

    private void historyCard(BackupResult item) {
        LinearLayout card = card(page);
        text(card, getString(item.kind() == OperationJournal.Kind.BACKUP ? R.string.file_backup
                : item.kind() == OperationJournal.Kind.RESTORE ? R.string.file_restore : R.string.diagnostic_test), 17, true);
        text(card, date(item.time()) + " · " + stateLabel(item), 14, false);
        button(card, R.string.view_details, false, () -> showResult(item.id()));
    }

    private void settings() {
        heading(R.string.settings);
        LinearLayout recoveryCard = card(page);
        title(recoveryCard, R.string.recovery_number);
        text(recoveryCard, R.string.recovery_help);
        LinearLayout alerts = card(page);
        title(alerts, R.string.notifications);
        CheckBox completion = new CheckBox(this);
        completion.setText(R.string.completion_alerts);
        completion.setChecked(preferences().getBoolean("completion_alerts", true));
        completion.setOnCheckedChangeListener((view, checked) -> preferences().edit().putBoolean("completion_alerts", checked).apply());
        alerts.addView(completion);
        text(alerts, R.string.notification_explanation);
        button(alerts, R.string.notification_settings, false, () -> startActivity(new Intent(
                android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())));
        LinearLayout help = card(page);
        title(help, R.string.about_this_build);
        text(help, R.string.scope);
        text(help, R.string.storage_explanation);
        title(page, R.string.diagnostics);
        action(R.string.self_test, () -> {
            abandonUnstartedOutput();
            clearSecret();
            draft = new BackupDraft();
            launchJob(BackupJobs.Kind.SELF_TEST, RecoveryKeyEnvelope.generateNumber());
        });
        text(page, R.string.self_test_explanation);
    }

    private void begin(boolean restoring) {
        abandonUnstartedOutput();
        clearSecret();
        draft = new BackupDraft();
        draft.restoring = restoring;
        go(Step.FILES);
    }

    private void files() {
        stepHeading(1, draft.restoring ? R.string.choose_backup : R.string.choose_files_title);
        text(page, draft.restoring ? R.string.choose_backup_hint : R.string.choose_files_hint);
        if (draft.restoring) {
            if (draft.archive != null) {
                DocumentFiles.Info archive = info(draft.archive, true);
                fileCard(draft.archive, archive, false);
                if (archive != null) {
                    draft.legacy = archive.format() == 1;
                    if (archive.available() && archive.format() == 0) text(page, R.string.unsupported_backup);
                    else if (archive.available()) text(page, draft.legacy ? R.string.legacy_archive : R.string.numeric_archive);
                }
                Button next = primary(R.string.next, () -> go(Step.RECOVERY));
                next.setTag(archive == null || !archive.available() || archive.format() == 0 ? "unavailable" : null);
            }
            action(draft.archive == null ? R.string.find_backup : R.string.choose_another_backup, this::chooseArchive);
        } else {
            action(R.string.add_files, this::chooseFiles);
            if (draft.sources.isEmpty()) text(card(page), R.string.empty_selection);
            long known = 0;
            int unknown = 0;
            boolean readable = !draft.sources.isEmpty();
            for (String uri : new ArrayList<>(draft.sources)) {
                DocumentFiles.Info file = info(uri, false);
                fileCard(uri, file, true);
                readable &= file != null && file.available();
                if (file == null || file.size() < 0) unknown++;
                else known = Long.MAX_VALUE - known < file.size() ? Long.MAX_VALUE : known + file.size();
            }
            text(page, getResources().getQuantityString(R.plurals.selected_files, draft.sources.size(), draft.sources.size()), 16, true);
            if (!draft.sources.isEmpty()) text(page, unknown == 0 ? getString(R.string.selection_size, size(known))
                    : getResources().getQuantityString(R.plurals.selection_size_unknown, unknown, size(known), unknown), 14, false);
            Button next = primary(R.string.next, () -> go(Step.LOCATION));
            if (!readable) next.setTag("unavailable");
        }
    }

    private void fileCard(String uri, DocumentFiles.Info info, boolean removable) {
        LinearLayout card = card(page);
        text(card, info == null ? getString(R.string.loading_file) : info.name(), 16, true);
        text(card, info == null ? getString(R.string.loading_file) : !info.available()
                ? getString(R.string.file_unavailable) : info.size() < 0 ? getString(R.string.size_unknown) : size(info.size()), 14, false);
        if (removable) {
            Button remove = button(card, R.string.remove_file, false, () -> {
                draft.sources.remove(uri);
                persist();
                render();
            });
            if (info != null) remove.setContentDescription(getString(R.string.remove_named_file, info.name()));
        }
    }

    private void location() {
        stepHeading(draft.restoring ? 3 : 2, R.string.choose_location);
        text(page, draft.restoring ? R.string.restore_location_hint : R.string.backup_location_hint);
        if (draft.destination != null) {
            text(card(page), label(draft.destination), 17, true);
            primary(R.string.next, () -> go(recoveryNumber != null ? Step.REVIEW : Step.RECOVERY));
        }
        action(draft.destination == null ? R.string.choose_location : R.string.change_location, this::chooseDestination);
    }

    private void recovery() {
        numericRecovery = !draft.restoring || !draft.legacy;
        stepHeading(draft.restoring ? 2 : 3, numericRecovery ? R.string.recovery_number : R.string.legacy_password);
        text(page, draft.restoring ? R.string.unlock_hint : R.string.recovery_create_hint);
        text(page, numericRecovery ? R.string.number_grouping_hint : R.string.legacy_hint);
        TextView recoveryLabel = text(page, numericRecovery ? R.string.recovery_number : R.string.legacy_password);
        recovery = secretField(numericRecovery, numericRecovery ? R.string.number_example : R.string.password_required);
        recoveryLabel.setLabelFor(recovery.getId());
        if (!draft.restoring) {
            TextView confirmationLabel = text(page, R.string.confirm_number);
            confirmation = secretField(true, R.string.number_example);
            confirmationLabel.setLabelFor(confirmation.getId());
        }
        CheckBox show = new CheckBox(this);
        show.setText(numericRecovery ? R.string.show_number : R.string.show_password);
        show.setOnCheckedChangeListener((view, checked) -> {
            recovery.setTransformationMethod(checked ? null : PasswordTransformationMethod.getInstance());
            if (confirmation != null) confirmation.setTransformationMethod(checked ? null : PasswordTransformationMethod.getInstance());
        });
        page.addView(show);
        if (!draft.restoring) {
            button(page, R.string.generate_number, false, () -> {
                char[] number = RecoveryKeyEnvelope.generateNumber();
                String grouped = group(number);
                recovery.setText(grouped);
                confirmation.setText(grouped);
                show.setChecked(true);
                Arrays.fill(number, '\0');
            });
            button(page, R.string.copy_number, false, this::copyNumber);
        }
        text(page, R.string.keep_recovery);
        primary(R.string.next, () -> {
            if (!captureSecret()) return;
            hideKeyboard();
            go(draft.destination == null ? Step.LOCATION : Step.REVIEW);
        });
    }

    private EditText secretField(boolean numeric, int hint) {
        EditText field = new EditText(this);
        field.setId(View.generateViewId());
        field.setHint(hint);
        field.setInputType(numeric ? InputType.TYPE_CLASS_PHONE
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(numeric ? 24 : 1024)});
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setMinHeight(dp(56));
        field.setTextSize(20);
        if (numeric) field.addTextChangedListener(new TextWatcher() {
            private boolean formatting;
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) {
                if (formatting) return;
                char[] digits = readSecret(field, true);
                try {
                    for (char digit : digits) if (digit < '0' || digit > '9') return;
                    if (digits.length > 16) return;
                    String grouped = group(digits);
                    if (grouped.contentEquals(text)) return;
                    int oldCursor = field.getSelectionStart();
                    int beforeCursor = 0;
                    for (int i = 0; i < oldCursor && i < text.length(); i++) {
                        if (text.charAt(i) >= '0' && text.charAt(i) <= '9') beforeCursor++;
                    }
                    formatting = true;
                    text.replace(0, text.length(), grouped);
                    field.setSelection(Math.min(text.length(), beforeCursor + Math.max(0, beforeCursor - 1) / 4));
                } finally {
                    formatting = false;
                    Arrays.fill(digits, '\0');
                }
            }
        });
        page.addView(field, new LinearLayout.LayoutParams(-1, -2));
        return field;
    }

    private char[] readSecret(EditText field, boolean numeric) {
        char[] raw = new char[field.length()];
        field.getText().getChars(0, raw.length, raw, 0);
        if (!numeric) return raw;
        char[] stripped = new char[raw.length];
        int count = 0;
        for (char c : raw) if (c != ' ' && c != '-') stripped[count++] = c;
        char[] result = Arrays.copyOf(stripped, count);
        Arrays.fill(raw, '\0');
        Arrays.fill(stripped, '\0');
        return result;
    }

    private boolean captureSecret() {
        char[] first = readSecret(recovery, numericRecovery);
        char[] second = confirmation == null ? null : readSecret(confirmation, true);
        try {
            if (numericRecovery ? !RecoveryKeyEnvelope.isValidNumber(first) : first.length == 0) {
                recovery.setError(getString(numericRecovery ? R.string.password_short : R.string.password_required));
                recovery.requestFocus();
                return false;
            }
            if (second != null && !Arrays.equals(first, second)) {
                confirmation.setError(getString(R.string.password_mismatch));
                confirmation.requestFocus();
                return false;
            }
            clearSecret();
            recoveryNumber = first.clone();
            return true;
        } finally {
            Arrays.fill(first, '\0');
            if (second != null) Arrays.fill(second, '\0');
        }
    }

    private void copyNumber() {
        char[] number = readSecret(recovery, true);
        try {
            if (!RecoveryKeyEnvelope.isValidNumber(number)) {
                recovery.setError(getString(R.string.password_short));
                return;
            }
            ClipData clip = ClipData.newPlainText(getString(R.string.recovery_number), group(number));
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
            getSystemService(ClipboardManager.class).setPrimaryClip(clip);
        } finally { Arrays.fill(number, '\0'); }
    }

    private static String group(char[] number) {
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < number.length; i++) {
            if (i > 0 && i % 4 == 0) grouped.append(' ');
            grouped.append(number[i]);
        }
        return grouped.toString();
    }

    private void review() {
        stepHeading(4, R.string.review_title);
        LinearLayout summary = card(page);
        title(summary, draft.restoring ? R.string.file_restore : R.string.file_backup);
        if (draft.restoring) text(summary, label(draft.archive), 17, true);
        else text(summary, getResources().getQuantityString(R.plurals.selected_files, draft.sources.size(), draft.sources.size()), 17, true);
        text(summary, R.string.destination_label);
        text(summary, label(draft.destination), 16, false);
        text(summary, R.string.recovery_ready);
        if (draft.restoring) text(summary, R.string.new_folder_note);
        text(page, getString(R.string.free_space, size(getNoBackupFilesDir().getUsableSpace())), 14, false);
        text(page, R.string.storage_explanation);
        text(page, R.string.background_note);
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) text(page, R.string.notification_explanation);
        primary(draft.restoring ? R.string.start_restore : R.string.start_backup, this::requestStart);
        button(page, R.string.change_location, false, () -> go(Step.LOCATION));
        button(page, R.string.change_recovery, false, () -> { clearSecret(); go(Step.RECOVERY); });
    }

    private void requestStart() {
        if (recoveryNumber == null) { go(Step.RECOVERY); error(R.string.reenter_recovery); return; }
        if (!draft.restoring) {
            long remaining = Math.max(0, getNoBackupFilesDir().getUsableSpace() - 32L * 1024 * 1024) / 2;
            for (String uri : draft.sources) {
                DocumentFiles.Info file = metadata.get("document:" + uri);
                if (file != null && !file.available()) { go(Step.FILES); error(R.string.selection_unreadable); return; }
                if (file != null && file.size() >= 0) {
                    if (file.size() > remaining) { error(R.string.space_failed_help); return; }
                    remaining -= file.size();
                }
            }
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !preferences().getBoolean("asked_alerts", false)) {
            preferences().edit().putBoolean("asked_alerts", true).apply();
            startAfterPermission = true;
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, ALERTS);
            return;
        }
        char[] secret = recoveryNumber;
        recoveryNumber = null;
        launchJob(draft.restoring ? BackupJobs.Kind.RESTORE : BackupJobs.Kind.BACKUP, secret);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == ALERTS && startAfterPermission) {
            startAfterPermission = false;
            if (screen == Step.REVIEW) requestStart();
        }
    }

    private void launchJob(BackupJobs.Kind kind, char[] secret) {
        String id = null;
        try {
            if (!jobs.ready() || jobs.running()) { error(R.string.operation_unavailable); return; }
            List<Uri> sources = new ArrayList<>();
            for (String uri : draft.sources) sources.add(Uri.parse(uri));
            screen = Step.PROGRESS;
            id = jobs.prepare(kind, sources, uri(draft.archive), uri(draft.destination), secret);
            draft.operationId = id;
            draft.step = Step.PROGRESS;
            selectedResult = null;
            persist();
            startForegroundService(new Intent(this, BackupService.class).putExtra(BackupService.EXTRA_JOB, id));
            render();
        } catch (RuntimeException failed) {
            if (id != null) jobs.rejectStart(id);
            if (kind == BackupJobs.Kind.BACKUP && draft.destination != null) {
                deleteUnstarted(draft.destination);
                draft.destination = null;
            }
            draft.operationId = null;
            go(kind == BackupJobs.Kind.SELF_TEST ? Step.SETTINGS : Step.RECOVERY);
            error(R.string.start_failed);
        } finally { Arrays.fill(secret, '\0'); }
    }

    private void progress() {
        heading(R.string.working_title);
        text(page, R.string.background_note);
        LinearLayout card = card(page);
        progressText = text(card, jobs.message(), 18, true);
        progressText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        card.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(12)));
        button(page, R.string.cancel, false, () -> { jobs.cancel(); updateProgress(); });
        button(page, R.string.return_home, false, () -> go(Step.HOME));
        updateProgress();
    }

    private void updateProgress() {
        if (progressText == null) return;
        progressText.setText(jobs.message());
        BackupJobs.ProgressInfo progress = jobs.progressInfo();
        boolean count = progress != null && progress.total() > 0 && progress.completed() >= 0;
        progressBar.setIndeterminate(!count);
        if (count) {
            progressBar.setMax(progress.total());
            progressBar.setProgress(progress.completed());
            progressBar.setContentDescription(progress.text());
        }
    }

    private void showResult(String id) { selectedResult = id; go(Step.RESULT); }

    private BackupResult selectedResult() {
        for (BackupResult item : jobs.results()) if (item.id().equals(selectedResult)) return item;
        BackupResult latest = jobs.result();
        return latest != null && (selectedResult == null || latest.id().equals(selectedResult)) ? latest : null;
    }

    private void result() {
        BackupResult item = selectedResult();
        if (item == null) {
            heading(R.string.recent_activity);
            text(page, jobs.ready() ? R.string.result_missing : R.string.opening_history);
            button(page, R.string.return_home, false, () -> go(Step.HOME));
            return;
        }
        heading(item.state() == OperationJournal.State.COMPLETE ? R.string.operation_complete
                : item.state() == OperationJournal.State.CANCELLED ? R.string.cancelled_title : R.string.needs_attention);
        text(page, stateLabel(item), 18, true);
        LinearLayout card = card(page);
        text(card, item.name(), 18, true);
        text(card, date(item.time()), 14, false);
        if (item.state() == OperationJournal.State.COMPLETE) {
            text(card, item.kind() == OperationJournal.Kind.SELF_TEST ? R.string.self_test_done
                    : item.kind() == OperationJournal.Kind.RESTORE ? R.string.restore_success_hint : R.string.backup_success_hint);
            if (item.kind() != OperationJournal.Kind.SELF_TEST) text(card, fileCount(item.files()), 16, false);
            if (item.bytes() >= 0) text(card, size(item.bytes()), 14, false);
        } else text(card, problemText(item));
        if (item.cleanupFailed()) text(card, R.string.cleanup_attention);
        String output = item.outputUri() != null ? item.outputUri() : item.locationUri();
        if (output != null) {
            text(card, label(output), 15, false);
            button(page, R.string.open_location, false, () -> openLocation(output));
        }
        if (item.state() != OperationJournal.State.COMPLETE && item.kind() != OperationJournal.Kind.SELF_TEST) {
            primary(R.string.try_again, () -> retry(item));
            if (item.problem() == BackupResult.Problem.SPACE) button(page, R.string.manage_storage, false,
                    () -> startActivity(new Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS)));
        }
        button(page, R.string.technical_details, false, () -> new AlertDialog.Builder(this)
                .setTitle(R.string.technical_details).setMessage(item.detail().isEmpty() ? getString(R.string.no_extra_details) : item.detail())
                .setPositiveButton(android.R.string.ok, null).show());
        button(page, R.string.done, false, () -> { jobs.dismissResult(); go(Step.HOME); });
    }

    private int problemText(BackupResult item) {
        if (item.state() == OperationJournal.State.CANCELLED) return R.string.cancelled_help;
        return switch (item.problem()) {
            case AUTHENTICATION -> R.string.unlock_failed_help;
            case SPACE -> R.string.space_failed_help;
            case ACCESS -> R.string.access_failed_help;
            case FORMAT -> R.string.unsupported_backup;
            case HISTORY -> R.string.journal_failed;
            case INTERRUPTED -> R.string.interrupted_help;
            case START -> R.string.start_failed;
            case NONE, IO -> R.string.io_failed_help;
        };
    }

    private void retry(BackupResult item) {
        OperationJournal.Record plan = jobs.plan(item.id());
        clearSecret();
        draft = new BackupDraft();
        draft.restoring = item.kind() == OperationJournal.Kind.RESTORE;
        if (draft.restoring) {
            draft.archive = item.archiveUri();
            draft.destination = item.locationUri();
        } else if (plan != null) draft.sources.addAll(plan.sources);
        metadata.clear();
        go(Step.FILES);
    }

    private void chooseFiles() {
        picker(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), FILES);
    }

    private void chooseArchive() {
        picker(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), ARCHIVE);
    }

    private void chooseDestination() {
        if (draft.restoring) picker(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), DESTINATION);
        else picker(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,
                        "Petal Files " + java.time.LocalDate.now() + ".petalbak"), SAVE);
    }

    private void picker(Intent intent, int request) {
        hideKeyboard();
        persist();
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (request == SAVE || request == DESTINATION) intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { startActivityForResult(intent, request); }
        catch (RuntimeException failed) { error(R.string.picker_unavailable); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == BROWSE || result != RESULT_OK || data == null || jobs.running()) return;
        Uri uri = data.getData();
        if (request == FILES) {
            List<String> added = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    Uri item = data.getClipData().getItemAt(i).getUri();
                    if (item != null && "content".equals(item.getScheme())) added.add(item.toString());
                }
            } else if (uri != null && "content".equals(uri.getScheme())) added.add(uri.toString());
            if (!draft.addFiles(added)) { error(R.string.too_many_files); return; }
            for (String source : added) persistGrant(Uri.parse(source), data);
            go(Step.FILES);
        } else if (uri != null && "content".equals(uri.getScheme())) {
            persistGrant(uri, data);
            if (request == ARCHIVE) {
                clearSecret();
                draft.archive = uri.toString();
                draft.legacy = false;
                metadata.remove("archive:" + draft.archive);
                go(Step.FILES);
            } else if (request == SAVE || request == DESTINATION) {
                String previous = draft.destination;
                draft.destination = uri.toString();
                if (request == SAVE && previous != null && !previous.equals(draft.destination)) deleteUnstarted(previous);
                go(Step.LOCATION);
            }
        }
    }

    private void persistGrant(Uri uri, Intent result) {
        int flags = result.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            if (flags == (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } else if (flags == Intent.FLAG_GRANT_READ_URI_PERMISSION) {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else if (flags == Intent.FLAG_GRANT_WRITE_URI_PERMISSION) {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
        }
        catch (SecurityException notSupported) { /* the current picker grant still works */ }
    }

    private DocumentFiles.Info info(String uri, boolean archive) {
        if (uri == null) return null;
        String key = (archive ? "archive:" : "document:") + uri;
        if (metadata.containsKey(key)) return metadata.get(key);
        if (loading.add(key)) documents.execute(() -> {
            DocumentFiles.Info found = DocumentFiles.info(getApplicationContext(), Uri.parse(uri), archive, queryCancellation);
            main.post(() -> {
                if (isDestroyed()) return;
                loading.remove(key);
                metadata.put(key, found);
                main.removeCallbacks(metadataRefresh);
                main.postDelayed(metadataRefresh, 80);
            });
        });
        return null;
    }

    private String label(String uri) {
        if (uri == null) return getString(R.string.choose_location);
        DocumentFiles.Info info = info(uri, false);
        String provider = Uri.parse(uri).getAuthority();
        android.content.pm.ProviderInfo owner = provider == null ? null : getPackageManager().resolveContentProvider(provider, 0);
        if (owner != null) provider = owner.loadLabel(getPackageManager()).toString();
        return (info == null ? getString(R.string.loading_file) : info.name()) + " · " + provider;
    }

    private void openLocation(String uri) {
        try {
            Intent browse = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(uri));
            startActivityForResult(browse, BROWSE);
        } catch (RuntimeException unavailable) { error(R.string.location_unavailable); }
    }

    private void abandonUnstartedOutput() {
        if (draft != null && !draft.restoring && draft.operationId == null && draft.destination != null) deleteUnstarted(draft.destination);
    }

    private void deleteUnstarted(String uri) {
        documents.execute(() -> {
            boolean deleted = DocumentFiles.delete(getApplicationContext(), Uri.parse(uri));
            if (!deleted) main.post(() -> {
                if (!isDestroyed()) error(R.string.unused_output_cleanup);
            });
        });
    }

    private void clearSecret() {
        if (recoveryNumber != null) Arrays.fill(recoveryNumber, '\0');
        recoveryNumber = null;
    }

    private void hideKeyboard() {
        if (getCurrentFocus() != null) getSystemService(InputMethodManager.class)
                .hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
    }

    private void back() {
        clearSecret();
        switch (screen) {
            case HOME -> finish();
            case FILES, PROGRESS, RESULT, BACKUPS, SETTINGS -> go(Step.HOME);
            case RECOVERY -> go(draft.restoring ? Step.FILES : Step.LOCATION);
            case LOCATION -> go(draft.restoring ? Step.RECOVERY : Step.FILES);
            case REVIEW -> go(Step.RECOVERY);
        }
    }

    @Override protected void onStart() { super.onStart(); jobs.observe(this); changed(); }
    @Override protected void onResume() {
        super.onResume();
        metadata.clear();
        if (screen != Step.RECOVERY) render();
    }
    @Override protected void onStop() {
        persist();
        jobs.remove(this);
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("draft", draft.encode());
        state.putString("screen", screen.name());
        state.putString("result", selectedResult);
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        clearSecret();
        queryCancellation.cancel();
        documents.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void changed() {
        if (screen == Step.PROGRESS && !jobs.busy()) {
            selectedResult = draft.operationId;
            screen = Step.RESULT;
            draft.step = Step.HOME;
            persist();
            render();
        } else if (screen == Step.PROGRESS) updateProgress();
        else if (!draft.isWizard() || screen == Step.HOME || screen == Step.BACKUPS || screen == Step.RESULT) render();
        else updateControls();
    }

    private void updateControls() {
        for (Button button : operationControls) {
            boolean enabled = jobs.ready() && !jobs.running() && !"unavailable".equals(button.getTag());
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : 0.45f);
        }
    }

    private void error(int resource) {
        inlineError = getString(resource);
        if (errorView != null) {
            errorView.setText(inlineError);
            errorView.setVisibility(View.VISIBLE);
            errorView.requestFocus();
        }
    }

    private void heading(int title) { text(page, getString(title), 30, true); }
    private void stepHeading(int number, int title) {
        text(page, getString(R.string.step_of, number), 13, false);
        heading(title);
    }
    private void title(LinearLayout parent, int resource) { text(parent, getString(resource), 19, true); }
    private TextView text(LinearLayout parent, int resource) { return text(parent, getString(resource), 15, false); }
    private TextView text(LinearLayout parent, String value, int size, boolean heading) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(getColor(heading ? R.color.text : R.color.secondary));
        view.setPadding(0, dp(7), 0, dp(7));
        if (heading) {
            view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            view.setAccessibilityHeading(true);
        }
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(12), dp(18), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(R.color.card));
        background.setCornerRadius(dp(24));
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(14);
        parent.addView(card, params);
        return card;
    }
    private Button primary(int label, Runnable action) {
        Button button = button(page, label, true, action);
        operationControls.add(button);
        return button;
    }
    private Button action(int label, Runnable action) {
        Button button = button(page, label, false, action);
        operationControls.add(button);
        return button;
    }
    private Button button(LinearLayout parent, int label, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setMinHeight(dp(52));
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setPadding(dp(16), dp(12), dp(16), dp(12));
        button.setTextColor(getColor(primary ? R.color.on_accent : R.color.accent));
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(primary ? R.color.accent : R.color.soft_accent));
        background.setCornerRadius(dp(26));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(getColor(R.color.ripple)), background, null));
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        parent.addView(button, params);
        return button;
    }
    private String date(long time) {
        return DateFormat.getMediumDateFormat(this).format(new java.util.Date(time)) + " · "
                + DateFormat.getTimeFormat(this).format(new java.util.Date(time));
    }
    private String stateLabel(BackupResult result) {
        return getString(switch (result.state()) {
            case COMPLETE -> R.string.status_complete;
            case CANCELLED -> R.string.cancelled_title;
            case INTERRUPTED -> R.string.status_interrupted;
            case FAILED -> R.string.needs_attention;
            case QUEUED, RUNNING -> R.string.operation_running;
        });
    }
    private String size(long bytes) { return Formatter.formatShortFileSize(this, bytes); }
    private String fileCount(int count) {
        return count < 0 ? getString(R.string.file_count_unknown)
                : getResources().getQuantityString(R.plurals.selected_files, count, count);
    }
    private static Uri uri(String value) { return value == null ? null : Uri.parse(value); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
