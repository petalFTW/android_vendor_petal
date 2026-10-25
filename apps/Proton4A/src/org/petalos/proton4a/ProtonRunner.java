/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.petalos.proton4a;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Starts Windows programs on the Proton4A runtime.
 *
 * Layout under credential-protected storage:
 *   runtime/box64                 ARM64 glibc translator
 *   runtime/rootfs/               aarch64 glibc + X11/Vulkan libraries
 *   runtime/proton/               upstream Proton redist (x86-64)
 *   programs/&lt;id&gt;.exe              imported programs
 *   runtime/prefix/               shared Wine prefix
 *
 * Box64 is a glibc ELF, so it is invoked through the bundled loader with an
 * explicit library path instead of relying on /lib, which Android lacks.
 */
public final class ProtonRunner {
    private static final ExecutorService INPUT = Executors.newSingleThreadExecutor();
    private ProtonRunner() {}

    public static File programsDir(Context context) {
        return ProgramStore.programsDir(context);
    }

    public static File logFile(Context context, String programId) {
        return new File(context.getCodeCacheDir(), "wine-" + programId + ".log");
    }

    /** File Xvfb mirrors its framebuffer to, for the in-app display view. */
    public static File framebufferFile(Context context) {
        return RuntimeLayout.of(context).framebuffer();
    }

    private static Process displayProcess;

    /** Starts the headless X server if it is not already running. */
    public static synchronized void startDisplay(Context context) throws IOException {
        if (displayProcess != null && displayProcess.isAlive()) return;
        RuntimeLayout layout = RuntimeLayout.of(context);
        layout.createWritableDirs();
        ProcessBuilder builder = new ProcessBuilder(layout.xvfbCommand());
        builder.directory(new File(layout.rootfs, "usr/bin"));
        builder.redirectErrorStream(true);
        builder.environment().putAll(layout.environment());
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(
                new File(context.getCodeCacheDir(), "xvfb.log")));
        displayProcess = builder.start();

        // Wait briefly for the framebuffer file Xvfb mirrors its screen to.
        File framebuffer = layout.framebuffer();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (framebuffer.isFile()) return;
            if (!displayProcess.isAlive()) {
                throw new IOException("Xvfb exited during startup");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public static synchronized boolean displayRunning() {
        return displayProcess != null && displayProcess.isAlive();
    }

    public static synchronized void stopDisplay() {
        if (displayProcess != null) {
            displayProcess.destroy();
            displayProcess = null;
        }
    }

    /** Runs wineboot once so the prefix exists and Wine is configured. */
    public static Process initialize(Context context) throws IOException {
        startDisplay(context);
        return start(context, "wineboot");
    }

    public static Process launch(Context context, String programId) throws IOException {
        File exe = new File(programsDir(context), programId + ".exe");
        if (!exe.isFile()) throw new IOException("Imported program not found");
        return start(context, exe.getAbsolutePath());
    }

    /** Send touch/keyboard input to Xvfb off the UI thread, in event order. */
    public static void input(Context context, String... args) {
        INPUT.execute(() -> {
            try {
                RuntimeLayout layout = RuntimeLayout.of(context);
                List<String> command = layout.loaderFor(new File(layout.rootfs, "usr/bin/xdotool"));
                switch (args[0]) {
                    case "down":
                    case "up":
                    case "move":
                        command.add("mousemove");
                        command.add(args[1]);
                        command.add(args[2]);
                        if ("down".equals(args[0]) || "up".equals(args[0])) {
                            command.add("down".equals(args[0]) ? "mousedown" : "mouseup");
                            command.add("1");
                        }
                        break;
                    case "key":
                        command.add("key");
                        command.add("--clearmodifiers");
                        command.add(args[1]);
                        break;
                    case "text":
                        command.add("type");
                        command.add("--clearmodifiers");
                        command.add("--");
                        command.add(args[1]);
                        break;
                    default: return;
                }
                new ProcessBuilder(command).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile(context, "input")))
                        .directory(layout.rootfs).start().waitFor();
            } catch (IOException exception) {
                android.util.Log.w("Proton4A", "Cannot inject input", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static Process start(Context context, String argument) throws IOException {
        RuntimeManager.Status status = RuntimeManager.status(context);
        if (!status.installed) throw new IOException("Runtime is not installed");

        RuntimeLayout layout = RuntimeLayout.of(context);
        layout.createWritableDirs();

        List<String> command = layout.loaderFor(layout.box64);
        command.add(layout.wine.getAbsolutePath());
        command.add(argument);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.environment().putAll(layout.environment());
        builder.directory(programsDir(context));
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile(context, "run")));
        return builder.start();
    }

    /**
     * Runs a diagnostic command and returns its combined output. Used by the
     * self-test and the debug report.
     */
    static String capture(Context context, List<String> command, long timeoutMs)
            throws IOException, InterruptedException {
        RuntimeLayout layout = RuntimeLayout.of(context);
        layout.createWritableDirs();
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.environment().putAll(layout.environment());
        builder.directory(programsDir(context));

        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // process ended
            }
        }, "Proton4A-capture");
        reader.setDaemon(true);
        reader.start();

        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            reader.join(2000);
            return output + "\n<timed out after " + timeoutMs + " ms>";
        }
        reader.join(2000);
        return output.toString();
    }

    static List<String> box64VersionCommand(Context context) {
        RuntimeLayout layout = RuntimeLayout.of(context);
        List<String> command = layout.loaderFor(layout.box64);
        command.add("-v");
        return command;
    }

    static List<String> wineVersionCommand(Context context) {
        RuntimeLayout layout = RuntimeLayout.of(context);
        List<String> command = layout.loaderFor(layout.box64);
        command.add(layout.wine.getAbsolutePath());
        command.add("--version");
        return command;
    }

    /** Cleans up any running Wine processes for this prefix. */
    public static void terminate(Context context) {
        try {
            RuntimeLayout layout = RuntimeLayout.of(context);
            List<String> command = layout.loaderFor(layout.box64);
            command.add("-k");
            new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(new File("/dev/null"))
                    .start();
        } catch (IOException ignored) {
            // Nothing to kill.
        }
    }

    private static List<String> copy(List<String> input) {
        return new ArrayList<>(input);
    }
}
