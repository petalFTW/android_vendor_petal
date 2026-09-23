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

package org.petalos.advanced;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** dumps the merged hosts where the boot helper picks it up */
final class HostsWriter {

    private static final String TAG = "PetalAdvanced";

    /** bind mounted over /system/etc/hosts by the init helper */
    static final File HELPER = new File("/data/petal/blocklist/hosts");

    /** the daemon mounts when this says 1, unmounts otherwise */
    static final File STATE = new File("/data/petal/blocklist/enabled");

    private HostsWriter() {}

    static File fallback(Context c) {
        return new File(c.getFilesDir(), "hosts");
    }

    static boolean helperUsable() {
        final File dir = HELPER.getParentFile();
        return dir != null && dir.isDirectory();
    }

    /** real mount/unmount switch, read by the root daemon */
    static void setState(Context c, boolean on) {
        tryWrite(STATE, on ? "1\n" : "0\n");
    }

    /** true when it landed in the real helper path */
    static boolean write(Context c, String content) {
        if (tryWrite(HELPER, content)) {
            return true;
        }
        Log.w(TAG, "helper path not writable, using app storage");
        return tryWrite(fallback(c), content);
    }

    private static boolean tryWrite(File f, String content) {
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }
}
