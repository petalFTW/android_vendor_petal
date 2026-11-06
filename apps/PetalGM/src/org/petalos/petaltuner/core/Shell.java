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

package org.petalos.petaltuner.core;

import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class Shell {

    private static final long TIMEOUT_SECONDS = 8;
    // A refused su (not yet granted in the root manager) is re-asked after this long.
    private static final long ROOT_RETRY_MS = 60 * 1000;
    private static volatile boolean sRootGranted;
    private static volatile long sRootDeniedAt = -ROOT_RETRY_MS;

    private Shell() {}

    public static final class Result {
        public final int code;
        public final List<String> output;

        Result(int code, List<String> output) {
            this.code = code;
            this.output = output;
        }

        public boolean ok() {
            return code == 0;
        }
    }

    public static Result sh(String command) {
        return run(new String[]{"sh", "-c", command});
    }

    public static Result su(String command) {
        return run(new String[]{"su", "-c", command});
    }

    public static Result run(String[] argv) {
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        Process process = null;
        try {
            process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            Process running = process;
            // Drain on a side thread so a child that never closes stdout can't outlive the
            // timeout by blocking readLine().
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(running.getInputStream()))) {
                    String line;
                    while ((line = in.readLine()) != null) output.add(line);
                } catch (Exception ignored) {
                }
            }, "petal-shell-reader");
            reader.start();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                Log.w(Sysfs.TAG, "shell timeout: " + argv[0]);
                return new Result(-1, new ArrayList<>(output));
            }
            reader.join(500);
            return new Result(process.exitValue(), new ArrayList<>(output));
        } catch (Exception e) {
            Log.w(Sysfs.TAG, "shell failed " + argv[0] + ": " + e.getMessage());
            return new Result(-1, new ArrayList<>(output));
        } finally {
            if (process != null) process.destroy();
        }
    }

    /** True once su has worked; a refusal is retried after a minute rather than forever. */
    public static boolean isRootAvailable() {
        if (sRootGranted) return true;
        if (SystemClock.elapsedRealtime() - sRootDeniedAt < ROOT_RETRY_MS) return false;
        Result result = su("id");
        boolean granted = result.ok() && String.join(" ", result.output).contains("uid=0");
        if (granted) {
            sRootGranted = true;
        } else {
            sRootDeniedAt = SystemClock.elapsedRealtime();
        }
        Log.i(Sysfs.TAG, "root available: " + granted);
        return granted;
    }

    public static boolean writeAsRoot(String path, String value) {
        return writeAllAsRoot(Collections.singletonMap(path, value));
    }

    /** All writes in one su process; a profile used to fork one su per node. */
    public static boolean writeAllAsRoot(Map<String, String> writes) {
        if (writes.isEmpty()) return true;
        if (!isRootAvailable()) return false;
        StringBuilder script = new StringBuilder("ok=0; ");
        for (Map.Entry<String, String> write : writes.entrySet()) {
            script.append("printf '%s' ").append(quote(write.getValue()))
                    .append(" > ").append(quote(write.getKey()))
                    .append(" 2>/dev/null || ok=1; ");
        }
        script.append("exit $ok");
        return su(script.toString()).ok();
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
