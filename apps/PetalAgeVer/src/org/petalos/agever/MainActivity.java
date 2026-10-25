/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.agever;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import org.petalos.agever.core.ControlMode;
import org.petalos.agever.output.ProviderFrameSink;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity implements StageService.StageObserver {
    private static final int IMPORT = 43;
    private static final int OVERLAY = 42;
    private static final int NOTIFICATIONS = 44;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private final List<String> mLabels = new ArrayList<>();
    private final List<String> mValues = new ArrayList<>();
    private StageService mService;
    private StageService.LocalBinder mBinder;
    private boolean mBinding;
    private boolean mSuppress;
    private boolean mPendingPanel;
    private boolean mPendingCamera;
    private SurfaceView mPreview;
    private Spinner mAvatars;
    private TextView mStatus;
    private TextView mDetail;
    private Button mToggle;
    private AvatarImportViewModel mImporter;
    private String mImportLabel = "";

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            mBinder = (StageService.LocalBinder) binder;
            mService = mBinder.service();
            mService.attachPreview(mPreview);
            mBinder.addObserver(MainActivity.this);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            mService = null;
            mBinder = null;
            mToggle.setEnabled(false);
            mDetail.setText(R.string.detail_service_lost);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Prefs.init(this);
        if (state != null) {
            mPendingPanel = state.getBoolean("pending_panel");
            mPendingCamera = state.getBoolean("pending_camera");
            mImportLabel = state.getString("import_label", "");
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.agever_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        findViewById(R.id.button_back).setOnClickListener(v -> finish());
        mPreview = findViewById(R.id.preview_surface);
        mStatus = findViewById(R.id.preview_status);
        mDetail = findViewById(R.id.status_detail);
        mToggle = findViewById(R.id.button_toggle);
        mAvatars = findViewById(R.id.avatar_spinner);
        mToggle.setEnabled(false);
        mToggle.setOnClickListener(v -> command(StageService.isCameraRequested()
                || StageService.state() == SessionState.CAMERA_BLOCKED
                || StageService.state() == SessionState.ERROR
                ? StageService.ACTION_STOP : StageService.ACTION_START));
        findViewById(R.id.button_preview).setOnClickListener(v -> command(
                StageService.isRunning() && !StageService.isCameraRequested()
                        ? StageService.ACTION_STOP : StageService.ACTION_PREVIEW));
        findViewById(R.id.button_library).setOnClickListener(v -> showLibrary());
        findViewById(R.id.button_import).setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,
                            new String[]{"model/gltf-binary", "application/octet-stream"});
            startActivityForResult(pick, IMPORT);
        });
        RadioGroup modes = findViewById(R.id.mode_group);
        modes.check(Prefs.get().getMode() == ControlMode.IDLE ? R.id.mode_idle : R.id.mode_joystick);
        modes.setOnCheckedChangeListener((group, id) -> {
            Prefs.get().setMode(id == R.id.mode_idle ? ControlMode.IDLE : ControlMode.JOYSTICK);
            configChanged();
        });
        bindSwitch(R.id.switch_mirror, Prefs.get().getMirror(), Prefs.get()::setMirror);
        bindSwitch(R.id.switch_idle, Prefs.get().getIdleMotion(), Prefs.get()::setIdleMotion);
        bindSwitch(R.id.switch_restore, Prefs.get().getRestoreOnBoot(), Prefs.get()::setRestoreOnBoot);
        SwitchCompat panel = findViewById(R.id.switch_joystick);
        panel.setChecked(Prefs.get().getJoystickOverlay() && Settings.canDrawOverlays(this));
        panel.setOnCheckedChangeListener((view, checked) -> {
            if (mSuppress) return;
            if (checked && !Settings.canDrawOverlays(this)) {
                mPendingPanel = true;
                mSuppress = true; panel.setChecked(false); mSuppress = false;
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())), OVERLAY);
            } else {
                Prefs.get().setJoystickOverlay(checked);
                configChanged();
            }
        });
        Spinner fps = findViewById(R.id.fps_spinner);
        fps.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{getString(R.string.fps_balanced), getString(R.string.fps_smooth)}));
        fps.setSelection(Prefs.get().getFrameRate() == 30 ? 1 : 0);
        fps.setOnItemSelectedListener(selection(position -> {
            Prefs.get().setFrameRate(position == 1 ? 30 : 24);
            configChanged();
        }));
        mAvatars.setOnItemSelectedListener(selection(position -> {
            if (mSuppress || position >= mValues.size()) return;
            String ref = mValues.get(position);
            if (!ref.equals(Prefs.get().getAvatar())) {
                Prefs.get().setAvatar(ref);
                configChanged();
            }
        }));
        refreshAvatars();
        mImporter = new ViewModelProvider(this).get(AvatarImportViewModel.class);
        findViewById(R.id.button_cancel_import).setOnClickListener(v -> mImporter.cancel());
        mImporter.result.observe(this, result -> {
            findViewById(R.id.import_progress).setVisibility(result.busy ? View.VISIBLE : View.GONE);
            findViewById(R.id.button_cancel_import).setVisibility(result.busy ? View.VISIBLE : View.GONE);
            findViewById(R.id.button_import).setEnabled(!result.busy);
            if (result.file != null) {
                String ref = result.file.getAbsolutePath();
                Prefs.get().setAvatarLabel(ref, result.label.isEmpty() ? getString(R.string.imported_avatar) : result.label);
                Prefs.get().setAvatar(ref);
                mImporter.consume();
                refreshAvatars();
                configChanged();
                toast(getString(R.string.status_import_ok, label(ref)));
            } else if (result.error != null) {
                toast(getString(R.string.detail_avatar_failed, result.error));
                mImporter.consume();
            }
        });
    }

    private interface BooleanSetter { void set(boolean value); }
    private void bindSwitch(int id, boolean checked, BooleanSetter setter) {
        SwitchCompat view = findViewById(id);
        view.setChecked(checked);
        view.setOnCheckedChangeListener((button, value) -> { setter.set(value); configChanged(); });
    }
    private android.widget.AdapterView.OnItemSelectedListener selection(java.util.function.IntConsumer selected) {
        return new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                selected.accept(position);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        };
    }
    @Override protected void onStart() {
        super.onStart();
        mBinding = bindService(new Intent(this, StageService.class), mConnection, Context.BIND_AUTO_CREATE);
    }
    @Override protected void onResume() {
        super.onResume();
        if (mPendingPanel) {
            mPendingPanel = false;
            boolean allowed = Settings.canDrawOverlays(this);
            Prefs.get().setJoystickOverlay(allowed);
            ((SwitchCompat) findViewById(R.id.switch_joystick)).setChecked(allowed);
            if (!allowed) toast(getString(R.string.status_permission_overlay));
            configChanged();
        }
    }
    @Override protected void onStop() {
        if (mBinder != null) mBinder.removeObserver(this);
        if (mService != null) mService.detachPreview();
        if (mBinding) unbindService(mConnection);
        mBinding = false; mBinder = null; mService = null;
        super.onStop();
    }
    @Override protected void onDestroy() {
        mMain.removeCallbacksAndMessages(null);
        mWorker.shutdownNow();
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("pending_panel", mPendingPanel);
        state.putBoolean("pending_camera", mPendingCamera);
        state.putString("import_label", mImportLabel);
        super.onSaveInstanceState(state);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != IMPORT || result != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        mImporter.start(this, uri);
    }

    private void command(String action) {
        if (StageService.ACTION_START.equals(action) && checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            mPendingCamera = true;
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
            return;
        }
        sendCommand(action);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == NOTIFICATIONS && mPendingCamera) {
            mPendingCamera = false;
            if (grants.length != 0) sendCommand(StageService.ACTION_START);
        }
    }
    private void sendCommand(String action) {
        try {
            Intent intent = new Intent(this, StageService.class).setAction(action);
            if (StageService.ACTION_START.equals(action)) startForegroundService(intent);
            else startService(intent);
        } catch (RuntimeException e) {
            mDetail.setText(getString(R.string.detail_action_failed, e.getMessage()));
        }
    }
    private void configChanged() { if (mService != null) mService.onConfigChanged(); }
    private String label(String ref) {
        return Prefs.get().getAvatarLabel(ref, ref.equals(Prefs.DEFAULT_AVATAR)
                ? getString(R.string.default_avatar) : new File(ref).getName());
    }
    private void refreshAvatars() {
        mLabels.clear(); mValues.clear();
        mValues.add(Prefs.DEFAULT_AVATAR);
        try {
            String[] bundled = getAssets().list("avatars");
            if (bundled != null) for (String name : bundled) {
                String ref = "avatars/" + name;
                if (name.endsWith(".glb") && !mValues.contains(ref)) mValues.add(ref);
            }
        } catch (java.io.IOException ignored) { }
        File[] files = new File(getFilesDir(), "avatars").listFiles((dir, name) -> name.endsWith(".glb"));
        long total = 0;
        if (files != null) {
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File file : files) { mValues.add(file.getAbsolutePath()); total += file.length(); }
        }
        for (String ref : mValues) mLabels.add(label(ref));
        mSuppress = true;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.avatar_item, mLabels);
        adapter.setDropDownViewResource(R.layout.avatar_dropdown_item);
        mAvatars.setAdapter(adapter);
        mAvatars.setSelection(Math.max(0, mValues.indexOf(Prefs.get().getAvatar())));
        mAvatars.post(() -> mSuppress = false);
        ((TextView) findViewById(R.id.library_storage)).setText(getString(R.string.library_storage,
                files == null ? 0 : files.length, android.text.format.Formatter.formatFileSize(this, total)));
    }

    private void showLibrary() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(list);
        for (int i = 0; i < mValues.size(); i++) {
            final String ref = mValues.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int spacing = Math.round(12 * getResources().getDisplayMetrics().density);
            row.setPadding(spacing, spacing, spacing, spacing);
            ImageView thumbnail = new ImageView(this);
            thumbnail.setImageResource(R.drawable.ic_agever);
            thumbnail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(thumbnail, new LinearLayout.LayoutParams(spacing * 5, spacing * 5));
            File preview = AvatarStore.thumbnail(this, ref);
            mWorker.submit(() -> {
                Bitmap bitmap = BitmapFactory.decodeFile(preview.getAbsolutePath());
                if (bitmap != null) mMain.post(() -> { if (!isDestroyed()) thumbnail.setImageBitmap(bitmap); });
            });
            TextView title = new TextView(this);
            title.setText(label(ref)); title.setPadding(spacing, 0, 0, 0);
            row.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            row.setOnClickListener(v -> avatarActions(ref));
            list.addView(row);
        }
        new AlertDialog.Builder(this).setTitle(R.string.library_title).setView(scroll)
                .setNegativeButton(android.R.string.cancel, null).show();
    }
    private void avatarActions(String ref) {
        boolean imported = ref.startsWith("/");
        String[] actions = imported ? new String[]{getString(R.string.avatar_select),
                getString(R.string.avatar_rename), getString(R.string.avatar_delete)}
                : new String[]{getString(R.string.avatar_select)};
        new AlertDialog.Builder(this).setTitle(label(ref)).setItems(actions, (dialog, which) -> {
            if (which == 0) { Prefs.get().setAvatar(ref); refreshAvatars(); configChanged(); }
            else if (which == 1) rename(ref);
            else new AlertDialog.Builder(this).setMessage(R.string.avatar_delete_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.avatar_delete, (d, w) -> deleteAvatar(ref)).show();
        }).show();
    }
    private void rename(String ref) {
        EditText name = new EditText(this);
        name.setSingleLine(true); name.setText(label(ref));
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(64)});
        new AlertDialog.Builder(this).setTitle(R.string.avatar_rename).setView(name)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String text = name.getText().toString().trim();
                    if (!text.isEmpty()) { Prefs.get().setAvatarLabel(ref, text); refreshAvatars(); }
                }).show();
    }
    private void deleteAvatar(String ref) {
        mWorker.submit(() -> {
            boolean deleted;
            try { deleted = AvatarStore.ownedFile(this, ref).delete(); }
            catch (java.io.IOException e) { deleted = false; }
            final boolean success = deleted;
            if (success) AvatarStore.thumbnail(this, ref).delete();
            mMain.post(() -> {
                if (isDestroyed()) return;
                if (!success) { toast(getString(R.string.avatar_delete_failed)); return; }
                Prefs.get().removeAvatarLabel(ref);
                if (ref.equals(Prefs.get().getLastGoodAvatar())) Prefs.get().setLastGoodAvatar(Prefs.DEFAULT_AVATAR);
                if (ref.equals(Prefs.get().getAvatar())) Prefs.get().setAvatar(Prefs.DEFAULT_AVATAR);
                refreshAvatars(); configChanged();
            });
        });
    }

    @Override public void onSessionChanged(SessionState state, String detail, boolean cameraRequested) {
        mStatus.setText(state.label);
        boolean notificationVisible = getSystemService(android.app.NotificationManager.class).areNotificationsEnabled();
        mDetail.setText(cameraRequested && !notificationVisible
                ? detail + "\n" + getString(R.string.detail_notifications_hidden) : detail);
        mToggle.setEnabled(mService != null && state != SessionState.STOPPING);
        mToggle.setText(cameraRequested || state == SessionState.CAMERA_BLOCKED || state == SessionState.ERROR
                ? R.string.button_stop : R.string.button_start);
        findViewById(R.id.button_preview).setEnabled(state != SessionState.STOPPING && !cameraRequested);
        ((Button) findViewById(R.id.button_preview)).setText(StageService.isRunning() && !cameraRequested
                ? R.string.close_preview : R.string.button_preview);
        if (!mValues.isEmpty() && !mValues.contains(Prefs.get().getAvatar())) refreshAvatars();
        else if (!mValues.isEmpty()) {
            mSuppress = true;
            mAvatars.setSelection(Math.max(0, mValues.indexOf(Prefs.get().getAvatar())));
            mAvatars.post(() -> mSuppress = false);
        }
        if (state == SessionState.PREVIEW_READY || state == SessionState.CAMERA_READY) {
            mMain.postDelayed(this::cacheThumbnail, 500);
        }
    }
    private void cacheThumbnail() {
        if (isDestroyed() || mService == null || !mService.selectedAvatarReady()
                || !mPreview.getHolder().getSurface().isValid()) return;
        String ref = Prefs.get().getAvatar();
        File destination = AvatarStore.thumbnail(this, ref);
        if (destination.exists()) return;
        Bitmap bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(mPreview, bitmap, result -> {
                if (result != PixelCopy.SUCCESS || isDestroyed() || mService == null
                        || !mService.selectedAvatarReady() || !ref.equals(Prefs.get().getAvatar())) {
                    bitmap.recycle(); return;
                }
                mWorker.submit(() -> {
                    try {
                        if (!destination.getParentFile().isDirectory()) destination.getParentFile().mkdirs();
                        try (FileOutputStream output = new FileOutputStream(destination)) {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                        }
                    } catch (java.io.IOException ignored) { destination.delete(); }
                    finally { bitmap.recycle(); }
                });
            }, mMain);
        } catch (IllegalArgumentException e) { bitmap.recycle(); }
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
