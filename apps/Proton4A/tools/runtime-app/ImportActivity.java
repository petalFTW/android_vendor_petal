package org.petalos.proton4a.runtime;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.database.Cursor;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/** Imports a picked EXE, builds its own APK, then asks Android to install it. */
public final class ImportActivity extends Activity {
    private static final int PICK_EXE = 1;
    private static final int ALLOW_INSTALL = 2;
    private TextView status;
    private ProgramApk.Result pending;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(40, 40, 40, 40);
        content.setBackgroundColor(Color.BLACK);
        content.setGravity(Gravity.CENTER);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        status.setText("Choose a Windows EXE to add to your app drawer");
        content.addView(status);
        Button select = new Button(this);
        select.setText("Choose EXE");
        select.setOnClickListener(view -> pick());
        content.addView(select);
        setContentView(content);
        if (state == null) {
            String staged = getIntent().getStringExtra("org.petalos.proton4a.TEST_EXE");
            Uri shared = getIntent().getData();
            if (staged != null) importStaged(staged);
            else if (shared != null) importShared(shared);
            else pick();
        }
    }

    /** Build from an EXE the caller already picked and shared read access to. */
    private void importShared(Uri uri) {
        String name = displayName(uri);
        if (!name.toLowerCase(Locale.ROOT).endsWith(".exe")) {
            status.setText("Choose an .exe file");
            return;
        }
        build(() -> getContentResolver().openInputStream(uri), name);
    }

    private void pick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_EXE);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_EXE && result == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            String name = displayName(uri);
            if (!name.toLowerCase(Locale.ROOT).endsWith(".exe")) {
                status.setText("Choose an .exe file");
                return;
            }
            build(() -> getContentResolver().openInputStream(uri), name);
        } else if (request == ALLOW_INSTALL && pending != null) {
            install();
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null) return name;
            }
        } catch (RuntimeException ignored) {
            // Fallback for providers without display-name metadata.
        }
        return uri.getLastPathSegment() == null ? "program.exe" : uri.getLastPathSegment();
    }

    private void importStaged(String path) {
        try {
            File directory = getExternalFilesDir(null).getCanonicalFile();
            File file = new File(path).getCanonicalFile();
            if (!file.getPath().startsWith(directory.getPath() + File.separator)
                    || !file.getName().toLowerCase(Locale.ROOT).endsWith(".exe")) {
                throw new IOException("Choose an EXE from the document picker");
            }
            build(() -> new FileInputStream(file), file.getName());
        } catch (IOException exception) {
            status.setText(exception.getMessage());
        }
    }

    private interface Source { InputStream open() throws IOException; }

    private void build(Source source, String name) {
        status.setText("Building " + name + " APK…");
        new Thread(() -> {
            try (InputStream input = source.open()) {
                if (input == null) throw new IOException("Cannot open selected EXE");
                ProgramApk.Result result = ProgramApk.build(this, input, name);
                android.util.Log.i("Proton4A", "Built " + result.packageName
                        + " at " + result.apk.getAbsolutePath());
                runOnUiThread(() -> { pending = result; install(); });
            } catch (Exception exception) {
                android.util.Log.e("Proton4A", "EXE packaging failed", exception);
                runOnUiThread(() -> status.setText("Could not build APK: "
                        + exception.getMessage()));
            }
        }, "Proton4A-package").start();
    }

    private void install() {
        if (pending == null) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            status.setText("Allow Proton4A to install apps, then return here");
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(settings, ALLOW_INSTALL);
            return;
        }
        ProgramApk.Result result = pending;
        pending = null;
        status.setText("Waiting for Android to install " + result.packageName + "…");
        new Thread(() -> {
            try {
                PackageInstaller installer = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(result.packageName);
                params.setSize(result.apk.length());
                int id = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(id)) {
                    try (InputStream input = new FileInputStream(result.apk);
                            OutputStream output = session.openWrite("base.apk", 0,
                                    result.apk.length())) {
                        byte[] buffer = new byte[65536];
                        int count;
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                        session.fsync(output);
                    }
                    Intent callback = new Intent(this, InstallResultActivity.class);
                    callback.setAction("org.petalos.proton4a.INSTALL_RESULT");
                    callback.setData(Uri.parse("package:" + result.packageName));
                    callback.putExtra("program_name", result.packageName);
                    PendingIntent pendingIntent = PendingIntent.getActivity(this, id, callback,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                    session.commit(pendingIntent.getIntentSender());
                }
                runOnUiThread(() -> status.setText("Confirm installation to add the app icon"));
            } catch (Exception exception) {
                android.util.Log.e("Proton4A", "APK install failed", exception);
                runOnUiThread(() -> status.setText("Could not install APK: "
                        + exception.getMessage()));
            }
        }, "Proton4A-install-apk").start();
    }
}
