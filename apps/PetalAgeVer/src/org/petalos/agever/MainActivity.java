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

package org.petalos.agever;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.petalos.agever.core.ControlMode;
import org.petalos.agever.output.ProviderFrameSink;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Configuration + live preview for the privacy camera. */
public final class MainActivity extends AppCompatActivity implements StageService.StageObserver {

    private static final String TAG = "AgeVerMain";
    private static final int REQ_OVERLAY = 42;
    private static final int REQ_IMPORT = 43;

    private SurfaceView mPreview;
    private TextView mPreviewStatus;
    private TextView mStatusInjection;
    private TextView mStatusDetail;
    private Spinner mAvatarSpinner;
    private RadioGroup mModeGroup;
    private SwitchCompat mSwitchMirror;
    private SwitchCompat mSwitchIdle;
    private SwitchCompat mSwitchJoystick;
    private Button mButtonToggle;

    private StageService mService;
    private boolean mBound;
    private boolean mBindingRequested;
    private StageService.LocalBinder mServiceBinder;

    private final List<String> mAvatarLabels = new ArrayList<>();
    private final List<String> mAvatarValues = new ArrayList<>();
    private boolean mSuppressSpinnerCallback;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mServiceBinder = (StageService.LocalBinder) binder;
            mService = mServiceBinder.service();
            mServiceBinder.addObserver(MainActivity.this);
            mBound = true;
            if (mPreview != null) {
                mService.attachPreview(mPreview);
            }
            onRunningChanged(StageService.isRunning());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
            mService = null;
            mServiceBinder = null;
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.init(this);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.agever_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
        findViewById(R.id.button_back).setOnClickListener(v -> finish());

        mPreview = findViewById(R.id.preview_surface);
        mPreviewStatus = findViewById(R.id.preview_status);
        mStatusInjection = findViewById(R.id.status_injection);
        mStatusDetail = findViewById(R.id.status_detail);
        mAvatarSpinner = findViewById(R.id.avatar_spinner);
        mModeGroup = findViewById(R.id.mode_group);
        mSwitchMirror = findViewById(R.id.switch_mirror);
        mSwitchIdle = findViewById(R.id.switch_idle);
        mSwitchJoystick = findViewById(R.id.switch_joystick);
        mButtonToggle = findViewById(R.id.button_toggle);

        bindControls();
        refreshAvatarList();
        updateInjectionStatus();
        onRunningChanged(StageService.isRunning());
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, StageService.class);
        mBindingRequested = bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (mServiceBinder != null) {
            mServiceBinder.removeObserver(this);
            mServiceBinder = null;
        }
        if (mBound && mService != null) {
            mService.detachPreview();
        }
        if (mBindingRequested) {
            unbindService(mConnection);
            mBindingRequested = false;
        }
        mBound = false;
        mService = null;
        super.onStop();
    }

    // ------------------------------------------------------------------- UI

    private void bindControls() {
        mModeGroup.check(modeToId(Prefs.get().getMode()));
        mModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            ControlMode mode = idToMode(checkedId);
            Prefs.get().setMode(mode);
            if (mBound && mService != null) {
                mService.onConfigChanged();
            }
        });

        mSwitchMirror.setChecked(Prefs.get().getMirror());
        mSwitchMirror.setOnCheckedChangeListener((v, checked) -> {
            Prefs.get().setMirror(checked);
            if (mBound && mService != null) {
                mService.onConfigChanged();
            }
        });

        mSwitchIdle.setChecked(Prefs.get().getIdleMotion());
        mSwitchIdle.setOnCheckedChangeListener((v, checked) -> {
            Prefs.get().setIdleMotion(checked);
            if (mBound && mService != null) {
                mService.onConfigChanged();
            }
        });

        mSwitchJoystick.setChecked(Prefs.get().getJoystickOverlay());
        mSwitchJoystick.setOnCheckedChangeListener((v, checked) -> {
            if (checked && !Settings.canDrawOverlays(this)) {
                requestOverlayPermission();
                return;
            }
            Prefs.get().setJoystickOverlay(checked);
            if (mBound && mService != null) {
                mService.onConfigChanged();
            }
        });

        mButtonToggle.setOnClickListener(v -> {
            Intent intent = new Intent(this, StageService.class);
            if (StageService.isRunning()) {
                intent.setAction(StageService.ACTION_STOP);
                startService(intent);
            } else {
                intent.setAction(StageService.ACTION_START);
                startForegroundService(intent);
            }
        });

        findViewById(R.id.button_import).setOnClickListener(v -> importAvatar());

        mAvatarSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (mSuppressSpinnerCallback || position >= mAvatarValues.size()) {
                    return;
                }
                Prefs.get().setAvatar(mAvatarValues.get(position));
                if (mBound && mService != null) {
                    mService.onConfigChanged();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void refreshAvatarList() {
        mAvatarLabels.clear();
        mAvatarValues.clear();

        mAvatarLabels.add(getString(R.string.default_avatar));
        mAvatarValues.add(Prefs.DEFAULT_AVATAR);
        try {
            String[] assets = getAssets().list("avatars");
            if (assets != null) {
                for (String name : assets) {
                    if (name.endsWith(".glb") && !("avatars/" + name).equals(Prefs.DEFAULT_AVATAR)) {
                        mAvatarLabels.add(name);
                        mAvatarValues.add("avatars/" + name);
                    }
                }
            }
        } catch (IOException ignored) {
            // no bundled avatars
        }
        File dir = new File(getFilesDir(), "avatars");
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().endsWith(".glb")) {
                    mAvatarLabels.add(f.getName());
                    mAvatarValues.add(f.getAbsolutePath());
                }
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, R.layout.avatar_item, mAvatarLabels);
        adapter.setDropDownViewResource(R.layout.avatar_dropdown_item);
        mSuppressSpinnerCallback = true;
        mAvatarSpinner.setAdapter(adapter);
        String current = Prefs.get().getAvatar();
        int index = mAvatarValues.indexOf(current);
        mAvatarSpinner.setSelection(index < 0 ? 0 : index);
        mSuppressSpinnerCallback = false;
    }

    // ------------------------------------------------------------- imports

    private void importAvatar() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        try {
            File dir = new File(getFilesDir(), "avatars");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("mkdir failed");
            }
            String name = "imported-" + System.currentTimeMillis() + ".glb";
            File out = new File(dir, name);
            try (InputStream in = getContentResolver().openInputStream(uri);
                    FileOutputStream os = new FileOutputStream(out)) {
                if (in == null) {
                    throw new IOException("no stream");
                }
                byte[] buf = new byte[64 * 1024];
                int read;
                while ((read = in.read(buf)) != -1) {
                    os.write(buf, 0, read);
                }
            }
            Prefs.get().setAvatar(out.getAbsolutePath());
            refreshAvatarList();
            if (mBound && mService != null) {
                mService.onConfigChanged();
            }
            Toast.makeText(this,
                    getString(R.string.status_import_ok, name), Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e(TAG, "avatar import failed", e);
            Toast.makeText(this, R.string.status_import_failed, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------- perms

    private void requestOverlayPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, REQ_OVERLAY);
    }

    // ------------------------------------------------------------- service

    @Override
    public void onRunningChanged(boolean running) {
        runOnUiThread(() -> {
            mButtonToggle.setText(running ? R.string.button_stop : R.string.button_start);
            mPreviewStatus.setText(running ? R.string.status_running : R.string.status_stopped);
        });
    }

    @Override
    public void onStatus(@NonNull String status) {
        runOnUiThread(() -> mStatusDetail.setText(status));
    }

    private void updateInjectionStatus() {
        boolean available = ProviderFrameSink.isAvailable(this);
        mStatusInjection.setText(available
                ? R.string.status_injection_ready
                : R.string.status_injection_missing);
    }

    private static int modeToId(ControlMode mode) {
        return mode == ControlMode.IDLE ? R.id.mode_idle : R.id.mode_joystick;
    }

    private static ControlMode idToMode(int id) {
        return id == R.id.mode_idle ? ControlMode.IDLE : ControlMode.JOYSTICK;
    }
}
