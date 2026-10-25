/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.petalos.proton4a;

import android.content.Context;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Single source of truth for Proton4A on-device paths and environment. */
final class RuntimeLayout {
    final File runtime;
    final File rootfs;
    final File proton;
    final File prefix;
    final File home;
    final File tmp;
    final File box64;
    final File wine;
    final File loader;
    final File xvfb;
    final File framebufferDir;

    /** Virtual display the Wine X11 driver connects to. */
    static final String DISPLAY = "127.0.0.1:0";

    private RuntimeLayout(Context context) {
        runtime = RuntimeManager.runtimeDir(context);
        rootfs = new File(runtime, "rootfs");
        proton = new File(runtime, "proton");
        prefix = new File(runtime.getParentFile(), "prefix");
        home = new File(runtime.getParentFile(), "home");
        tmp = new File(context.getCodeCacheDir(), "proton4a-tmp");
        box64 = new File(runtime, "box64");
        wine = new File(proton, "files/bin/wine");
        loader = new File(rootfs, "lib/ld-linux-aarch64.so.1");
        xvfb = new File(rootfs, "usr/bin/Xvfb");
        framebufferDir = new File(context.getCodeCacheDir(), "xvfb");
    }

    static RuntimeLayout of(Context context) {
        return new RuntimeLayout(context);
    }

    void createWritableDirs() throws java.io.IOException {
        for (File dir : new File[]{prefix, home, tmp, framebufferDir}) {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new java.io.IOException("Cannot create " + dir);
            }
        }
    }

    /**
     * Command that starts a headless X server listening on TCP only, so no
     * /tmp/.X11-unix socket (unwritable on Android) is required. The server
     * mirrors its framebuffer into {@link #framebufferDir} for the app to show.
     */
    java.util.List<String> xvfbCommand() {
        java.util.List<String> command = loaderFor(xvfb);
        command.add(":0");
        command.add("-screen");
        command.add("0");
        command.add("1280x720x24");
        command.add("-xkbdir");
        command.add(new File(rootfs, "usr/share/X11/xkb").getAbsolutePath());
        command.add("-ac");
        command.add("-nolisten");
        command.add("unix");
        command.add("-listen");
        command.add("tcp");
        command.add("-fbdir");
        command.add(framebufferDir.getAbsolutePath());
        return command;
    }

    File framebuffer() {
        return new File(framebufferDir, "Xvfb_screen0");
    }

    String libraryPath() {
        return join(
                new File(rootfs, "lib/aarch64-linux-gnu").getAbsolutePath(),
                new File(rootfs, "usr/lib/aarch64-linux-gnu").getAbsolutePath(),
                new File(rootfs, "lib").getAbsolutePath(),
                new File(rootfs, "usr/lib").getAbsolutePath());
    }

    /** Command prefix that runs an ARM64 glibc binary through the bundled loader. */
    java.util.List<String> loaderFor(File binary) {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(loader.getAbsolutePath());
        command.add("--library-path");
        command.add(libraryPath());
        command.add(binary.getAbsolutePath());
        return command;
    }

    Map<String, String> environment() {
        Map<String, String> env = new HashMap<>();
        String protonLib = new File(proton, "files/lib").getAbsolutePath();
        env.put("DISPLAY", DISPLAY);
        File wineLib = new File(proton, "files/lib");
        env.put("WINEPREFIX", prefix.getAbsolutePath());
        // Under Box64 the loader cannot derive its own libdir, so ntdll.so and
        // the PE dlls must be reachable directly through WINEDLLPATH.
        env.put("WINEDLLPATH", join(
                new File(wineLib, "wine/x86_64-unix").getAbsolutePath(),
                new File(wineLib, "wine/x86_64-windows").getAbsolutePath(),
                new File(wineLib, "vkd3d").getAbsolutePath(),
                new File(wineLib, "wine").getAbsolutePath()));
        env.put("WINESERVER", new File(proton, "files/bin/wineserver").getAbsolutePath());
        env.put("WINELOADER", new File(proton, "files/bin/wine").getAbsolutePath());
        env.put("WINELOADERNOEXEC", "1");
        env.put("WINEDEBUG", "-all");
        env.put("HOME", home.getAbsolutePath());
        env.put("TMPDIR", tmp.getAbsolutePath());
        env.put("PATH", join(
                new File(rootfs, "usr/bin").getAbsolutePath(),
                new File(rootfs, "bin").getAbsolutePath(),
                new File(proton, "files/bin").getAbsolutePath(),
                System.getenv("PATH") == null ? "/system/bin" : System.getenv("PATH")));
        env.put("LD_LIBRARY_PATH", libraryPath());
        env.put("FONTCONFIG_FILE", new File(rootfs, "etc/fonts/fonts.conf").getAbsolutePath());
        env.put("FONTCONFIG_PATH", new File(rootfs, "etc/fonts").getAbsolutePath());
        env.put("BOX64_LD_LIBRARY_PATH", join(protonLib,
                new File(protonLib, "x86_64-linux-gnu").getAbsolutePath(),
                new File(proton, "files/lib/wine/x86_64-unix").getAbsolutePath()));
        env.put("BOX64_NOBANNER", "1");
        env.put("BOX64_DYNAREC", "1");
        // Turnip is the vulkan ICD; point the loader at it explicitly so a
        // missing /etc/vulkan layout cannot hide it.
        env.put("VK_DRIVER_FILES",
                new File(rootfs, "usr/share/vulkan/icd.d/freedreno_icd.aarch64.json")
                        .getAbsolutePath());
        env.put("VK_ICD_FILENAMES", env.get("VK_DRIVER_FILES"));
        return env;
    }

    private static String join(String... parts) {
        return String.join(File.pathSeparator, parts);
    }
}
