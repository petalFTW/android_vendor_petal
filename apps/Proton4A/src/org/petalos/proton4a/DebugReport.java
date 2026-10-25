/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.petalos.proton4a;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Collects everything needed to debug Proton4A into a single file under the
 * app's external files directory (readable over adb/MTP).
 */
public final class DebugReport {
    private static final int LOG_TAIL_BYTES = 64 * 1024;

    private DebugReport() {}

    public static File write(Context context, boolean runSelfTest) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("Proton4A debug report\n");
        report.append("generated: ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append('\n');

        section(report, "device");
        report.append("model: ").append(Build.MODEL).append('\n');
        report.append("device: ").append(Build.DEVICE).append('\n');
        report.append("release: ").append(Build.VERSION.RELEASE)
                .append(" (sdk ").append(Build.VERSION.SDK_INT).append(")\n");
        report.append("abis: ").append(String.join(",", Build.SUPPORTED_ABIS)).append('\n');

        section(report, "runtime status");
        RuntimeManager.Status status = RuntimeManager.status(context);
        report.append("installed: ").append(status.installed).append('\n');
        report.append("proton: ").append(status.protonVersion).append('\n');
        report.append("box64: ").append(status.box64Revision).append('\n');
        File manifest = new File(RuntimeManager.runtimeDir(context), "manifest.json");
        if (manifest.isFile()) {
            report.append("manifest.json:\n").append(readText(manifest)).append('\n');
        }

        section(report, "inventory");
        RuntimeLayout layout = RuntimeLayout.of(context);
        inventory(report, "loader", layout.loader);
        inventory(report, "box64", layout.box64);
        inventory(report, "wine", layout.wine);
        inventory(report, "rootfs libc",
                new File(layout.rootfs, "lib/aarch64-linux-gnu/libc.so.6"));
        inventory(report, "vulkan loader", new File(layout.rootfs, "lib/aarch64-linux-gnu/libvulkan.so.1"));
        inventory(report, "turnip ICD",
                new File(layout.rootfs, "usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so"));
        inventory(report, "turnip ICD json",
                new File(layout.rootfs, "usr/share/vulkan/icd.d/freedreno_icd.aarch64.json"));
        inventory(report, "prefix", layout.prefix);
        report.append("libraryPath: ").append(layout.libraryPath()).append('\n');

        section(report, "environment");
        for (Map.Entry<String, String> entry : layout.environment().entrySet()) {
            report.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }

        if (runSelfTest && status.installed) {
            section(report, "self-test: box64 -v");
            run(report, context, ProtonRunner.box64VersionCommand(context));
            section(report, "self-test: wine --version");
            run(report, context, ProtonRunner.wineVersionCommand(context));
            section(report, "self-test: wineboot");
            try {
                File wineboot = new File(layout.proton, "files/bin/wine");
                java.util.List<String> command = layout.loaderFor(layout.box64);
                command.add(wineboot.getAbsolutePath());
                command.add("wineboot");
                run(report, context, command);
            } catch (RuntimeException exception) {
                report.append("wineboot setup failed: ").append(exception).append('\n');
            }
        }

        section(report, "wine run log (tail)");
        report.append(tail(ProtonRunner.logFile(context, "run"))).append('\n');

        File target = new File(context.getExternalFilesDir(null), "proton4a-debug.txt");
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(report.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        return target;
    }

    private static void run(StringBuilder report, Context context, java.util.List<String> command) {
        report.append("$ ").append(String.join(" ", command)).append('\n');
        try {
            report.append(ProtonRunner.capture(context, command, 60_000)).append('\n');
        } catch (IOException | InterruptedException exception) {
            report.append("failed: ").append(exception).append('\n');
        }
    }

    private static void inventory(StringBuilder report, String label, File file) {
        report.append(label).append(": ").append(file.getAbsolutePath());
        if (!file.exists()) {
            report.append(" (missing)\n");
            return;
        }
        report.append(" [").append(file.isDirectory() ? "dir" : file.length() + " bytes")
                .append(", ").append(file.canExecute() ? "exec" : "noexec").append("]\n");
    }

    private static void section(StringBuilder report, String title) {
        report.append("\n==== ").append(title).append(" ====\n");
    }

    private static String readText(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String tail(File file) {
        if (!file.isFile()) return "(no log yet)";
        try (java.io.RandomAccessFile reader = new java.io.RandomAccessFile(file, "r")) {
            long start = Math.max(0, reader.length() - LOG_TAIL_BYTES);
            reader.seek(start);
            byte[] data = new byte[(int) (reader.length() - start)];
            reader.readFully(data);
            return new String(data, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "(cannot read log: " + exception.getMessage() + ")";
        }
    }
}
