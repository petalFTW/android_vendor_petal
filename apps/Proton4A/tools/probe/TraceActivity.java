package org.petalos.proton4a.trace;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

/**
 * Rootless feasibility probe. Runs the bundled Box64 through a copied glibc
 * loader (patched to skip Android-trapped startup syscalls) and then runs a
 * static x86-64 guest, all from an ordinary app process with no root.
 */
public final class TraceActivity extends Activity {
    private void report(String line) {
        Log.i("Proton4ATrace", line);
        try {
            Files.write(new File(getFilesDir(), "probe-results.txt").toPath(),
                    (line + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception exception) {
            Log.e("Proton4ATrace", "Cannot save diagnostic", exception);
        }
    }

    private void run(String label, File log, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true).redirectOutput(log).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                report(label + " timed out");
            } else {
                report(label + " exit=" + process.exitValue() + " output="
                        + new String(Files.readAllBytes(log.toPath())));
            }
        } catch (Exception exception) {
            report(label + " failed: " + exception);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            try {
                Files.deleteIfExists(new File(getFilesDir(), "probe-results.txt").toPath());
                File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
                File loader = new File(nativeDir, "libglibcprobe.so");
                File box64 = new File(nativeDir, "libbox64probe.so");

                // glibc shared libraries live in app storage and are mapped
                // executable; the symlinks use their real sonames.
                File libs = new File(getCodeCacheDir(), "glibc-libs");
                libs.mkdirs();
                String[] names = {"libc.so.6", "libm.so.6", "libresolv.so.2"};
                String[] installed = {"libp4alibc.so", "libp4alibm.so", "libp4aresolv.so"};
                for (int i = 0; i < names.length; i++) {
                    java.nio.file.Path link = new File(libs, names[i]).toPath();
                    Files.deleteIfExists(link);
                    Files.createSymbolicLink(link, new File(nativeDir, installed[i]).toPath());
                }

                run("box64 -v", new File(getCodeCacheDir(), "box64-v.log"),
                        loader.getAbsolutePath(), "--library-path", libs.getAbsolutePath(),
                        box64.getAbsolutePath(), "-v");

                // Fixed app-storage interpreter path, as declared by the
                // bundled Box64 (see build-probe.sh), then execve Box64
                // directly from the native library directory.
                File interpDir = new File("/data/user/0/" + getPackageName() + "/f");
                interpDir.mkdirs();
                File interp = new File(interpDir, "ld.so");
                Files.copy(loader.toPath(), interp.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                interp.setExecutable(true, false);
                File directLog = new File(getCodeCacheDir(), "box64-direct.log");
                ProcessBuilder directBuilder = new ProcessBuilder(
                        box64.getAbsolutePath(), "-v")
                        .redirectErrorStream(true).redirectOutput(directLog);
                directBuilder.environment().put("LD_LIBRARY_PATH", libs.getAbsolutePath());
                Process directProcess = directBuilder.start();
                if (!directProcess.waitFor(15, TimeUnit.SECONDS)) {
                    directProcess.destroyForcibly();
                    report("box64 direct execve timed out");
                } else {
                    report("box64 direct execve exit=" + directProcess.exitValue() + " output="
                            + new String(Files.readAllBytes(directLog.toPath())));
                }

                File guest = new File(getFilesDir(), "x64-hello");
                try (java.io.InputStream input = getAssets().open("x64-hello")) {
                    Files.copy(input, guest.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                guest.setExecutable(true, false);
                File guestLog = new File(getCodeCacheDir(), "x64-hello.log");
                ProcessBuilder guestBuilder = new ProcessBuilder(
                        loader.getAbsolutePath(), "--library-path", libs.getAbsolutePath(),
                        box64.getAbsolutePath(), guest.getAbsolutePath())
                        .redirectErrorStream(true).redirectOutput(guestLog);
                guestBuilder.environment().put("BOX64_LOG", "1");
                guestBuilder.environment().put("BOX64_SHOWSEGV", "1");
                guestBuilder.environment().put("BOX64_SHOWBT", "1");
                guestBuilder.environment().put("BOX64_DYNAREC", "1");
                Process guestProcess = guestBuilder.start();
                if (!guestProcess.waitFor(15, TimeUnit.SECONDS)) {
                    guestProcess.destroyForcibly();
                    report("guest static x86-64 timed out");
                } else {
                    report("guest static x86-64 exit=" + guestProcess.exitValue() + " output="
                            + new String(Files.readAllBytes(guestLog.toPath())));
                }

                // Same guest under the in-process tracer to capture the exact
                // signal and faulting mapping if Box64's own report is silent.
                File tracedLog = new File(getCodeCacheDir(), "x64-hello-traced.log");
                ProcessBuilder tracedBuilder = new ProcessBuilder(
                        new File(nativeDir, "libtraceprobe.so").getAbsolutePath(),
                        loader.getAbsolutePath(), "--library-path", libs.getAbsolutePath(),
                        box64.getAbsolutePath(), guest.getAbsolutePath())
                        .redirectErrorStream(true).redirectOutput(tracedLog);
                tracedBuilder.environment().put("BOX64_DYNAREC", "0");
                Process traced = tracedBuilder.start();
                if (!traced.waitFor(15, TimeUnit.SECONDS)) {
                    traced.destroyForcibly();
                    report("guest trace timed out");
                } else {
                    report("guest trace exit=" + traced.exitValue() + " output="
                            + new String(Files.readAllBytes(tracedLog.toPath())));
                }
            } catch (Exception exception) {
                report("Probe failed: " + exception);
            }
        }, "Proton4A-trace").start();
    }
}
