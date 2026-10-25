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

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class Shell {

    private static final long TIMEOUT_SECONDS = 8;
    private static Boolean sRootAvailable;

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
        List<String> output = new ArrayList<>();
        Process process = null;
        int code = -1;
        try {
            process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.add(line);
                }
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                Log.w(Sysfs.TAG, "shell timeout: " + String.join(" ", argv));
                return new Result(-1, output);
            }
            code = process.exitValue();
        } catch (Exception e) {
            Log.w(Sysfs.TAG, "shell failed " + String.join(" ", argv) + ": " + e.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return new Result(code, output);
    }

    public static boolean isRootAvailable() {
        if (sRootAvailable != null) {
            return sRootAvailable;
        }
        Result result = su("id");
        sRootAvailable = result.ok() && result.output.toString().contains("uid=0");
        Log.i(Sysfs.TAG, "root available: " + sRootAvailable);
        return sRootAvailable;
    }

    public static boolean writeAsRoot(String path, String value) {
        if (!isRootAvailable()) {
            return false;
        }
        String escapedPath = path.replace("'", "'\\''");
        String escapedValue = value.replace("'", "'\\''");
        return su("echo '" + escapedValue + "' > '" + escapedPath + "'").ok();
    }
}
