package org.petalos.proton4a.runtime;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

/**
 * Rootless Proton4A runtime host. A normal app: it runs Box64 and Wine from
 * its own native library directory and private storage, with no root, no su
 * and no mounts.
 *
 * Launch with {@link #EXTRA_PROGRAM_PACKAGE} set to a per-program APK's
 * package name; its bundled assets/program.exe is imported and run. Without a
 * program it runs a self-test. The runtime payload is taken from a staged
 * file, the ROM path, or a file the user picks.
 */
public final class RuntimeActivity extends Activity {
    private static final String TAG = "Proton4A";
    static final String EXTRA_PROGRAM_PACKAGE = "org.petalos.proton4a.PROGRAM_PACKAGE";
    /** Runtime payload shipped by the ROM, if present. */
    private static final String ROM_PAYLOAD = "/product/proton4a/proton4a-runtime.tar.gz";
    private static final String INTERP = "@INTERP@";
    private static final int REQUEST_PAYLOAD = 1;

    private TextView status;
    private Process running;
    private Process displayProcess;
    private DisplayView display;
    private FrameLayout layout;
    private FrameLayout loadingLayer;
    private Button keyboardButton;
    private final ExecutorService inputWorker = Executors.newSingleThreadExecutor();
    private File nativeDir;
    private File box64;
    private File base;
    private File proton;
    private File prefix;
    private String programPackage;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        programPackage = getIntent().getStringExtra(EXTRA_PROGRAM_PACKAGE);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(15);
        status.setPadding(32, 32, 32, 32);
        layout = new FrameLayout(this);
        layout.setBackgroundColor(Color.BLACK);
        layout.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));
        if (programPackage != null) showLoading();
        setContentView(layout);

        new Thread(this::start, "Proton4A-runtime").start();
    }

    private void showLoading() {
        loadingLayer = new FrameLayout(this);
        loadingLayer.setBackgroundColor(Color.BLACK);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        if (Build.VERSION.SDK_INT >= 28) {
            try (InputStream stream = getAssets().open("loading.gif")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
                ImageView image = new ImageView(this);
                android.graphics.drawable.Drawable animation = ImageDecoder.decodeDrawable(
                        ImageDecoder.createSource(ByteBuffer.wrap(bytes.toByteArray())));
                image.setImageDrawable(animation);
                int size = (int) (112 * getResources().getDisplayMetrics().density);
                content.addView(image, new LinearLayout.LayoutParams(size, size));
                if (animation instanceof AnimatedImageDrawable) {
                    ((AnimatedImageDrawable) animation).start();
                }
            } catch (IOException exception) {
                content.addView(new ProgressBar(this));
            }
        } else {
            content.addView(new ProgressBar(this));
        }
        TextView caption = new TextView(this);
        caption.setText("Loading Windows EXE…");
        caption.setTextColor(Color.WHITE);
        caption.setTextSize(18);
        caption.setGravity(Gravity.CENTER);
        caption.setSingleLine(true);
        caption.setPadding(24, 20, 24, 0);
        int captionWidth = (int) (360 * getResources().getDisplayMetrics().density);
        content.addView(caption, new LinearLayout.LayoutParams(
                captionWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        loadingLayer.addView(content, new FrameLayout.LayoutParams(
                captionWidth, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        layout.addView(loadingLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Android forbids execve from writable app storage. Run the ARM64 ELF
     * helpers from the package manager's extracted native-library directory. */
    private File ensureNativeLibraries() throws IOException {
        File dir = new File(getApplicationInfo().nativeLibraryDir);
        if (!new File(dir, "libp4aldr.so").isFile()
                || !new File(dir, "libbox64.so").isFile()) {
            throw new IOException("Native libraries were not extracted into " + dir);
        }
        return dir;
    }

    private void setStatus(String text) {
        Log.i(TAG, text);
        runOnUiThread(() -> {
            status.setText(text);
            if (text.startsWith("Finished") || text.startsWith("Runtime failed")) {
                if (loadingLayer != null) loadingLayer.setVisibility(View.GONE);
                status.setVisibility(View.VISIBLE);
            }
        });
    }

    private void start() {
        try {
            nativeDir = ensureNativeLibraries();
            box64 = new File(nativeDir, "libbox64.so");
            base = new File(getFilesDir(), "proton4a");

            File interp = new File(INTERP);
            interp.getParentFile().mkdirs();
            Files.copy(new File(nativeDir, "libp4aldr.so").toPath(), interp.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            interp.setExecutable(true, false);

            File runtime = new File(base, "runtime");
            if (!new File(runtime, "manifest.json").isFile() && !installPayload()) return;

            proton = new File(runtime, "proton");
            prefix = new File(base, "prefix");
            File home = new File(base, "home");
            File tmp = new File(base, "tmp");
            for (File dir : new File[]{prefix, home, tmp}) dir.mkdirs();

            if (programPackage == null) {
                setStatus("Box64: " + run(nativeDir, box64.getAbsolutePath(), 20, "-v"));
                setStatus("socktest: " + run(nativeDir,
                        new File(nativeDir, "libsocktest.so").getAbsolutePath(), 10,
                        new File(tmp, "socktest.sock").getAbsolutePath()));
                try {
                    java.net.ServerSocket probe = new java.net.ServerSocket(0, 1,
                            java.net.InetAddress.getLoopbackAddress());
                    setStatus("java tcp socket ok port=" + probe.getLocalPort());
                    probe.close();
                } catch (Exception exception) {
                    setStatus("java tcp socket failed: " + exception);
                }
                setStatus("Xvfb: " + startXvfb(nativeDir, home, tmp));
                setStatus("Wine: " + runProgram(nativeDir, proton, prefix, home, tmp,
                        "cmd", "/c",
                        "echo TEMP=[%TEMP%] & echo USERPROFILE=[%USERPROFILE%] &"
                                + " mkdir \"%TEMP%\\nsprobe.tmp\" & echo MKDIR_ERR=%errorlevel% &"
                                + " echo FILES & dir /b \"%TEMP%\""));
                return;
            }
            File exe = importProgram(programPackage);
            String displayStatus = startXvfb(nativeDir, home, tmp);
            if (!displayStatus.startsWith("Xvfb running")) {
                throw new IOException(displayStatus);
            }
            String connection = checkDisplay(nativeDir, home);
            setStatus("X11: " + connection);
            File framebuffer = new File(home, "fb/Xvfb_screen0");
            runOnUiThread(() -> showDisplay(framebuffer, home));
            // Start the server first so it is listening before the client connects.
            startServer(nativeDir, proton, prefix, home, tmp);
            setStatus("Running " + exe.getName());
            running = start(nativeDir, box64, proton, prefix, home, tmp, exe);
            startWindowFit(home);
            int code = running.waitFor();
            setStatus("Finished (exit " + code + ")");
        } catch (Exception exception) {
            setStatus("Runtime failed: " + exception);
        }
    }

    private String checkDisplay(File nativeDir, File home) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(
                new File(nativeDir, "libp4aldr.so").getAbsolutePath(), "--library-path",
                nativeDir.getAbsolutePath(), new File(nativeDir, "libxdotool.so").getAbsolutePath(),
                "getdisplaygeometry");
        builder.environment().put("DISPLAY", ":0");
        builder.directory(home);
        builder.redirectErrorStream(true);
        File log = new File(getCodeCacheDir(), "display-check.log");
        builder.redirectOutput(log);
        Process process = builder.start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("X11 client timed out");
        }
        String result = new String(Files.readAllBytes(log.toPath())).trim();
        if (process.exitValue() != 0) throw new IOException("X11 client: " + result);
        return result;
    }

    private void showDisplay(File framebuffer, File home) {
        if (isFinishing() || isDestroyed()) return;
        display = new DisplayView(this, framebuffer, (action, x, y) -> {
            if ("move".equals(action)) sendInput(home, "mousemove", "" + x, "" + y);
            else sendInput(home, "mousemove", "" + x, "" + y,
                    "down".equals(action) ? "mousedown" : "mouseup", "1");
        }, () -> {
            if (loadingLayer != null) loadingLayer.setVisibility(View.GONE);
            status.setVisibility(View.GONE);
            if (keyboardButton != null) keyboardButton.setVisibility(View.VISIBLE);
        });
        layout.addView(display, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Button keyboard = new Button(this);
        keyboardButton = keyboard;
        keyboard.setText("Keyboard");
        keyboard.setVisibility(View.GONE);
        EditText text = new EditText(this);
        text.setSingleLine(true);
        text.setVisibility(View.GONE);
        keyboard.setOnClickListener(v -> {
            text.setVisibility(View.VISIBLE);
            text.requestFocus();
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .showSoftInput(text, InputMethodManager.SHOW_IMPLICIT);
        });
        text.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable value) {
                if (value.length() == 0) return;
                String typed = value.toString();
                value.clear();
                sendInput(home, "type", "--clearmodifiers", "--", typed);
            }
        });
        text.setOnEditorActionListener((v, id, event) -> {
            sendInput(home, "key", "Return");
            return true;
        });
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        layout.addView(keyboard, buttonParams);
        FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        layout.addView(text, textParams);
    }

    /** Resize the first visible Windows application window to fill the X screen.
     * This X server has no window manager to provide a maximize button. */
    private void startWindowFit(File home) {
        Thread worker = new Thread(() -> {
            for (int attempt = 0; attempt < 20 && !isDestroyed(); attempt++) {
                try {
                    String ids = xdotool(home, "search", "--onlyvisible", "--class", ".");
                    for (String id : ids.split("\\s+")) {
                        if (!id.matches("[0-9]+")) continue;
                        String name;
                        try { name = xdotool(home, "getwindowname", id).trim(); }
                        catch (IOException exception) { name = ""; }
                        Log.i(TAG, "X11 window " + id + ": " + name);
                        if (name.equals("Program Manager") || name.equals("Default IME")) continue;
                        xdotool(home, "windowsize", id, "1280", "720");
                        xdotool(home, "windowmove", id, "0", "0");
                        xdotool(home, "windowfocus", id);
                        xdotool(home, "key", "alt+space");
                        Thread.sleep(200);
                        xdotool(home, "key", "x");
                        return;
                    }
                    Thread.sleep(1000);
                } catch (IOException | InterruptedException exception) {
                    Log.w(TAG, "Window fit failed", exception);
                    return;
                }
            }
        }, "Proton4A-window-fit");
        worker.setDaemon(true);
        worker.start();
    }

    private String xdotool(File home, String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 4];
        command[0] = new File(nativeDir, "libp4aldr.so").getAbsolutePath();
        command[1] = "--library-path";
        command[2] = nativeDir.getAbsolutePath();
        command[3] = new File(nativeDir, "libxdotool.so").getAbsolutePath();
        System.arraycopy(args, 0, command, 4, args.length);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("DISPLAY", ":0");
        builder.directory(home);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream stream = process.getInputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        int code = process.waitFor();
        if (code != 0 && !(code == 1 && args[0].equals("search"))) {
            throw new IOException("xdotool " + args[0] + ": " + output.toString());
        }
        return output.toString();
    }

    private void sendInput(File home, String... args) {
        inputWorker.execute(() -> {
            try {
                String[] command = new String[args.length + 4];
                command[0] = new File(nativeDir, "libp4aldr.so").getAbsolutePath();
                command[1] = "--library-path";
                command[2] = nativeDir.getAbsolutePath();
                command[3] = new File(nativeDir, "libxdotool.so").getAbsolutePath();
                System.arraycopy(args, 0, command, 4, args.length);
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.environment().put("DISPLAY", ":0");
                builder.directory(home);
                builder.redirectErrorStream(true);
                builder.redirectOutput(ProcessBuilder.Redirect.appendTo(
                        new File(getCodeCacheDir(), "input.log")));
                int code = builder.start().waitFor();
                if (code != 0) Log.w(TAG, "xdotool exit " + code);
            } catch (IOException exception) {
                Log.w(TAG, "Input failed", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Payload from the ROM, a staged file, or the user's picker. */
    private boolean installPayload() {
        File staged = new File(getExternalFilesDir(null), "proton4a-runtime.tar.gz");
        File rom = new File(ROM_PAYLOAD);
        if (staged.isFile()) return installFromFile(staged);
        if (rom.isFile()) return installFromFile(rom);
        setStatus("Select proton4a-runtime.tar.gz to install the runtime");
        runOnUiThread(() -> {
            Intent request = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            request.addCategory(Intent.CATEGORY_OPENABLE);
            request.setType("*/*");
            startActivityForResult(request, REQUEST_PAYLOAD);
        });
        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PAYLOAD || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        new Thread(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Cannot open selected payload");
                install(input);
                start();
            } catch (Exception exception) {
                setStatus("Runtime install failed: " + exception);
            }
        }, "Proton4A-install").start();
    }

    private boolean installFromFile(File staged) {
        setStatus("Installing runtime payload...");
        try (InputStream input = new FileInputStream(staged)) {
            install(input);
            return true;
        } catch (Exception exception) {
            setStatus("Runtime install failed: " + exception);
            return false;
        }
    }

    /** Copy assets/program.exe out of a per-program APK into private storage. */
    private File importProgram(String packageName) throws IOException {
        String id = packageName.substring(packageName.lastIndexOf('.') + 1);
        File directory = new File(base, "programs");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create program storage");
        }
        File exe = new File(directory, id + ".exe");
        if (exe.isFile()) return exe;
        try {
            Context source = createPackageContext(packageName, 0);
            try (InputStream input = source.getAssets().open("program.exe");
                    FileOutputStream output = new FileOutputStream(exe)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.getFD().sync();
            }
            exe.setExecutable(true, false);
            return exe;
        } catch (android.content.pm.PackageManager.NameNotFoundException exception) {
            throw new IOException("Program APK is not installed", exception);
        }
    }

    /** Start a persistent wineserver and give it a moment to listen. */
    private void startServer(File nativeDir, File proton, File prefix, File home, File tmp) {
        try {
            File server = new File(proton, "files/bin/wineserver");
            boolean trace = getIntent().getBooleanExtra("org.petalos.proton4a.TRACE", false);
            ProcessBuilder builder = trace ? new ProcessBuilder(
                    new File(nativeDir, "libsystrace.so").getAbsolutePath(),
                    box64.getAbsolutePath(), server.getAbsolutePath(), "-p")
                    : new ProcessBuilder(box64.getAbsolutePath(), server.getAbsolutePath(), "-p");
            if (trace) {
                builder.environment().put("SYSTRACE_LOG",
                        new File(getCodeCacheDir(), "systrace-server.log").getAbsolutePath());
                builder.environment().put("SYSTRACE_FILE_TIMES", "1");
            }
            builder.environment().put("LD_LIBRARY_PATH", nativeDir.getAbsolutePath());
            builder.environment().put("WINEPREFIX", prefix.getAbsolutePath());
            builder.environment().put("HOME", home.getAbsolutePath());
            builder.environment().put("TMPDIR", tmp.getAbsolutePath());
            if (getIntent().getBooleanExtra("org.petalos.proton4a.FILE_DEBUG", false)) {
                builder.environment().put("WINEDEBUG", "fixme-all,err-all,warn-all,+file");
                builder.environment().put("BOX64_LOG_TIME", "1");
                builder.environment().put("BOX64_LOG_SYSCALL32", "1");
            }
            for (File dir : new File[]{tmp, home, prefix}) new File(dir, "wine").mkdirs();
            builder.directory(home);
            builder.redirectErrorStream(true);
            builder.redirectOutput(new File(getCodeCacheDir(), "wineserver.log"));
            builder.start();
            Thread.sleep(2000);
        } catch (Exception ignored) {
            // The client will start its own server if this fails.
        }
    }

    /** Common Wine environment for the client (and the tracer). */
    private void wineEnvironment(ProcessBuilder builder, File nativeDir, File proton,
            File prefix, File home, File tmp) {
        File wine = new File(proton, "files/bin/wine");
        builder.environment().put("LD_LIBRARY_PATH", nativeDir.getAbsolutePath());
        builder.environment().put("BOX64_LD_LIBRARY_PATH", String.join(File.pathSeparator,
                new File(proton, "files/lib").getAbsolutePath(),
                new File(proton, "files/lib/x86_64-linux-gnu").getAbsolutePath(),
                new File(proton, "files/lib/wine/x86_64-unix").getAbsolutePath()));
        builder.environment().put("WINEPREFIX", prefix.getAbsolutePath());
        builder.environment().put("WINEDLLPATH", String.join(File.pathSeparator,
                new File(proton, "files/lib/wine/x86_64-unix").getAbsolutePath(),
                new File(proton, "files/lib/wine/x86_64-windows").getAbsolutePath(),
                new File(proton, "files/lib/vkd3d").getAbsolutePath(),
                new File(proton, "files/lib/wine").getAbsolutePath()));
        builder.environment().put("WINESERVER",
                new File(proton, "files/bin/wineserver").getAbsolutePath());
        builder.environment().put("WINELOADER", wine.getAbsolutePath());
        builder.environment().put("WINELOADERNOEXEC", "1");
        builder.environment().put("HOME", home.getAbsolutePath());
        builder.environment().put("TMPDIR", tmp.getAbsolutePath());
        builder.environment().put("DISPLAY", ":0");
        if (getIntent().getBooleanExtra("org.petalos.proton4a.NO_DYNAREC", false)) {
            builder.environment().put("BOX64_DYNAREC", "0");
        }
        if (getIntent().getBooleanExtra("org.petalos.proton4a.FILE_DEBUG", false)) {
            builder.environment().put("WINEDEBUG", "fixme-all,err-all,warn-all,+file");
            builder.environment().put("BOX64_LOG_TIME", "1");
            builder.environment().put("BOX64_LOG_SYSCALL32", "1");
        }
        for (File dir : new File[]{tmp, home, prefix}) new File(dir, "wine").mkdirs();
        builder.directory(home);
    }

    private Process start(File nativeDir, File box64, File proton, File prefix, File home,
            File tmp, File program) throws IOException {
        boolean trace = getIntent().getBooleanExtra("org.petalos.proton4a.TRACE", false);
        ProcessBuilder builder = new ProcessBuilder(
                new File(nativeDir, "libsystrace.so").getAbsolutePath(),
                box64.getAbsolutePath(), new File(proton, "files/bin/wine").getAbsolutePath(),
                program.getAbsolutePath());
        wineEnvironment(builder, nativeDir, proton, prefix, home, tmp);
        if (!trace) builder.environment().put("SYSTRACE_SIGNALS_ONLY", "1");
        if (trace) builder.environment().put("SYSTRACE_FILE_TIMES", "1");
        builder.environment().put("SYSTRACE_LOG",
                new File(getExternalFilesDir(null), "systrace.log").getAbsolutePath());
        builder.redirectErrorStream(true);
        builder.redirectOutput(new File(getExternalFilesDir(null), "engine.log"));
        return builder.start();
    }

    /** Run the program under the bundled syscall tracer for diagnostics. */
    private void trace(File nativeDir, File box64, File proton, File prefix, File home,
            File tmp, File program) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(
                new File(nativeDir, "libsystrace.so").getAbsolutePath(),
                new File(nativeDir, "libp4aldr.so").getAbsolutePath(),
                "--library-path", nativeDir.getAbsolutePath(),
                box64.getAbsolutePath(),
                new File(proton, "files/bin/wine").getAbsolutePath(),
                program.getAbsolutePath());
        wineEnvironment(builder, nativeDir, proton, prefix, home, tmp);
        builder.environment().put("SYSTRACE_LOG",
                new File(getCodeCacheDir(), "systrace.log").getAbsolutePath());
        builder.redirectErrorStream(true);
        builder.redirectOutput(new File(getCodeCacheDir(), "engine.log"));
        setStatus("Tracing...");
        builder.start().waitFor();
        setStatus("Trace done");
    }

    /** Run the guest through Wine with the shared environment (self-test). */
    private String runProgram(File nativeDir, File proton, File prefix, File home, File tmp,
            String... args) throws IOException, InterruptedException {
        String[] command = new String[2 + args.length];
        command[0] = box64.getAbsolutePath();
        command[1] = new File(proton, "files/bin/wine").getAbsolutePath();
        System.arraycopy(args, 0, command, 2, args.length);
        ProcessBuilder builder = new ProcessBuilder(command);
        wineEnvironment(builder, nativeDir, proton, prefix, home, tmp);
        builder.redirectErrorStream(true);
        File log = new File(getCodeCacheDir(), "engine.log");
        builder.redirectOutput(log);
        Process process = builder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return "timed out";
        }
        String output = new String(Files.readAllBytes(log.toPath())).trim();
        return "exit=" + process.exitValue() + (output.isEmpty() ? "" : " " + output);
    }

    private String run(File nativeDir, String program, long timeout, String... args)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(
                concat(new String[]{program}, args));
        builder.environment().put("LD_LIBRARY_PATH", nativeDir.getAbsolutePath());
        builder.redirectErrorStream(true);
        File log = new File(getCodeCacheDir(), "engine.log");
        builder.redirectOutput(log);
        Process process = builder.start();
        if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return "timed out";
        }
        String output = new String(Files.readAllBytes(log.toPath())).trim();
        return "exit=" + process.exitValue() + (output.isEmpty() ? "" : " " + output);
    }

    private static String[] concat(String[] first, String[] second) {
        String[] result = new String[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private void install(InputStream raw) throws IOException {
        File target = new File(base, "runtime");
        File staging = new File(base, "runtime.staging");
        deleteRecursively(staging);
        if (!staging.mkdirs()) throw new IOException("Cannot create staging directory");
        try (InputStream input = new GZIPInputStream(raw)) {
            TarArchive.read(input, entry -> extract(staging, entry));
        }
        if (!new File(staging, "manifest.json").isFile()) {
            deleteRecursively(staging);
            throw new IOException("Payload has no manifest");
        }
        deleteRecursively(target);
        if (!staging.renameTo(target)) throw new IOException("Cannot activate runtime");
    }

    private boolean extract(File root, TarArchive.TarEntry entry) throws IOException {
        String name = entry.name.replace('\\', '/');
        if (!name.startsWith("runtime/")) return false;
        name = name.substring("runtime/".length());
        if (name.isEmpty() || name.startsWith("/") || name.contains("../")) return false;
        File destination = new File(root, name);
        if (entry.directory) {
            destination.mkdirs();
            return false;
        }
        File parent = destination.getParentFile();
        if (parent != null) parent.mkdirs();
        if (entry.symlink) {
            destination.delete();
            try {
                android.system.Os.symlink(entry.linkName, destination.getAbsolutePath());
            } catch (android.system.ErrnoException exception) {
                throw new IOException("Cannot create symlink " + name, exception);
            }
            return false;
        }
        if (entry.hardlink) {
            File source = new File(root, entry.linkName.startsWith("runtime/")
                    ? entry.linkName.substring("runtime/".length()) : entry.linkName);
            try {
                Files.deleteIfExists(destination.toPath());
                Files.createLink(destination.toPath(), source.toPath());
            } catch (IOException exception) {
                Files.copy(source.toPath(), destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            return false;
        }
        TarArchive.copy(entry.content, destination);
        try {
            android.system.Os.chmod(destination.getAbsolutePath(), entry.mode & 0777);
        } catch (android.system.ErrnoException ignored) {
            // best effort
        }
        // Content already consumed; true makes TarArchive drain the empty entry
        // and skip only its padding.
        return true;
    }

    /** Start the bundled Xvfb through the loader and check its framebuffer. */
    private String startXvfb(File nativeDir, File home, File tmp)
            throws IOException, InterruptedException {
        File loader = new File(nativeDir, "libp4aldr.so");
        File xvfb = new File(nativeDir, "libXvfb.so");
        File xkb = new File(home, "xkb");
        if (!new File(xkb, "keycodes").isDirectory()) copyAssetTree("xkb", xkb);
        File fb = new File(home, "fb");
        fb.mkdirs();
        File framebuffer = new File(fb, "Xvfb_screen0");
        Files.deleteIfExists(framebuffer.toPath());
        File wrapper = new File(home, "xkbcomp");
        Files.deleteIfExists(wrapper.toPath());
        try {
            android.system.Os.symlink(
                    new File(nativeDir, "libxkb-launch.so").getAbsolutePath(),
                    wrapper.getAbsolutePath());
        } catch (android.system.ErrnoException exception) {
            throw new IOException("Cannot create xkbcomp launcher", exception);
        }

        ProcessBuilder builder = new ProcessBuilder(
                loader.getAbsolutePath(),
                "--library-path", nativeDir.getAbsolutePath(),
                "--preload", new File(nativeDir, "libuidshim.so").getAbsolutePath(),
                xvfb.getAbsolutePath(), ":0", "-screen", "0", "1280x720x24",
                "-xkbdir", xkb.getAbsolutePath(),
                "-nolock", "-ac", "-nolisten", "tcp", "-extension", "MIT-SHM",
                "-fbdir", fb.getAbsolutePath());
        builder.environment().put("PROTON4A_HOME", home.getAbsolutePath());
        builder.environment().put("LD_LIBRARY_PATH", nativeDir.getAbsolutePath());
        builder.environment().put("PROTON4A_XKB_LOG",
                new File(home, "xkb-launch.log").getAbsolutePath());
        builder.directory(home);
        builder.redirectErrorStream(true);
        File log = new File(getCodeCacheDir(), "xvfb.log");
        builder.redirectOutput(log);
        Process process = builder.start();
        displayProcess = process;
        long deadline = System.currentTimeMillis() + 10_000;
        while (!framebuffer.isFile() && System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) break;
            Thread.sleep(100);
        }
        if (framebuffer.isFile() && process.isAlive()) return "Xvfb running (framebuffer ok)";
        process.destroy();
        return "Xvfb failed: " + new String(Files.readAllBytes(log.toPath())).trim();
    }

    /** Recursively copy an asset directory tree to a real directory. */
    private void copyAssetTree(String assetPath, File target) throws IOException {
        AssetManager assets = getAssets();
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            try (InputStream input = assets.open(assetPath);
                    FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            return;
        }
        if (!target.isDirectory() && !target.mkdirs()) {
            throw new IOException("Cannot create " + target);
        }
        for (String child : children) copyAssetTree(assetPath + "/" + child, new File(target, child));
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }

    @Override
    protected void onDestroy() {
        // Stop the session: end the running guest and the shared wineserver.
        if (running != null) running.destroy();
        if (display != null) display.stop();
        if (displayProcess != null) displayProcess.destroy();
        inputWorker.shutdownNow();
        if (nativeDir != null) {
            try {
                ProcessBuilder stop = new ProcessBuilder(box64.getAbsolutePath(), "-k");
                stop.environment().put("LD_LIBRARY_PATH", nativeDir.getAbsolutePath());
                stop.redirectErrorStream(true);
                stop.redirectOutput(new File("/dev/null"));
                stop.start();
            } catch (IOException ignored) {
                // nothing running
            }
        }
        super.onDestroy();
    }
}
