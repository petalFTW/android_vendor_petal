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
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Sysfs {

    public static final String TAG = "PetalTuner";

    // A node the kernel refuses stays refused for a while. Remember it so the
    // engine does not log the same permission denial twice a second.
    private static final long DENIED_TTL_MS = 5 * 60 * 1000;
    private static final Map<String, Long> sDenied = new ConcurrentHashMap<>();

    private Sysfs() {}

    public static boolean exists(String path) {
        if (path == null) {
            return false;
        }
        try {
            return new File(path).exists();
        } catch (Exception e) {
            return false;
        }
    }

    public static String read(String path) {
        if (!exists(path)) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line = reader.readLine();
            sDenied.remove(path);
            return line == null ? null : line.trim();
        } catch (Exception e) {
            Long deniedAt = sDenied.get(path);
            if (deniedAt == null || System.currentTimeMillis() - deniedAt >= DENIED_TTL_MS) {
                Log.w(TAG, "read failed " + path + ": " + e.getMessage());
                sDenied.put(path, System.currentTimeMillis());
            }
            return null;
        }
    }

    public static int readInt(String path, int def) {
        String value = read(path);
        if (value == null) {
            return def;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static boolean write(String path, String value) {
        return write(path, value, false);
    }

    public static boolean write(String path, String value, boolean appendNewline) {
        if (path == null || value == null) {
            return false;
        }
        Long deniedAt = sDenied.get(path);
        if (deniedAt != null && System.currentTimeMillis() - deniedAt < DENIED_TTL_MS) {
            return false;
        }
        if (directWrite(path, value, appendNewline)) {
            sDenied.remove(path);
            return true;
        }
        if (!new File(path).canWrite()) {
            sDenied.put(path, System.currentTimeMillis());
            return false;
        }
        if (Shell.writeAsRoot(path, value)) {
            sDenied.remove(path);
            return true;
        }
        return false;
    }

    private static boolean directWrite(String path, String value, boolean appendNewline) {
        FileOutputStream stream = null;
        try {
            stream = new FileOutputStream(path);
            stream.write((appendNewline ? value + "\n" : value).getBytes());
            stream.flush();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "direct write failed " + path + "=" + value + ": " + e.getMessage());
            return false;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static boolean writeInt(String path, int value) {
        return write(path, Integer.toString(value));
    }

    public static String firstExisting(String... paths) {
        if (paths == null) {
            return null;
        }
        for (String path : paths) {
            if (exists(path)) {
                return path;
            }
        }
        return null;
    }

    public static List<String> list(String dir, String prefix) {
        List<String> result = new ArrayList<>();
        File file = new File(dir);
        File[] children = file.listFiles();
        if (children == null) {
            return result;
        }
        for (File child : children) {
            if (prefix == null || child.getName().startsWith(prefix)) {
                result.add(child.getAbsolutePath());
            }
        }
        Collections.sort(result);
        return result;
    }

    public static List<String> listDirs(String dir, String prefix) {
        List<String> result = new ArrayList<>();
        File file = new File(dir);
        File[] children = file.listFiles();
        if (children == null) {
            return result;
        }
        for (File child : children) {
            if (child.isDirectory() && (prefix == null || child.getName().startsWith(prefix))) {
                result.add(child.getAbsolutePath());
            }
        }
        Collections.sort(result);
        return result;
    }

    public static int[] parseFrequencies(String spaceSeparated) {
        if (spaceSeparated == null) {
            return new int[0];
        }
        String[] parts = spaceSeparated.trim().split("\\s+");
        int[] values = new int[parts.length];
        int count = 0;
        for (String part : parts) {
            try {
                values[count++] = Integer.parseInt(part);
            } catch (NumberFormatException ignored) {
            }
        }
        int[] result = new int[count];
        System.arraycopy(values, 0, result, 0, count);
        return result;
    }

    public static String mhz(int khz) {
        return Integer.toString(Math.round(khz / 1000f));
    }
}
