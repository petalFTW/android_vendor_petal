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
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** does the actual list fetching and hosts merging */
final class BlocklistManager {

    interface Callback {
        void onDone(Result result);
    }

    static final class Result {
        final boolean ok;
        final int count;
        final int sources;
        final String message;

        Result(boolean ok, int count, int sources, String message) {
            this.ok = ok;
            this.count = count;
            this.sources = sources;
            this.message = message;
        }
    }

    private static final String CACHE = "hosts.cache";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private BlocklistManager() {}

    static void run(Runnable r) {
        IO.execute(r);
    }

    static void post(Runnable r) {
        MAIN.post(r);
    }

    /** normal update: pull enabled lists, merge with custom domains, write out */
    static Result update(Context c) {
        final List<Source> catalog = Catalog.load(c);
        final Set<String> enabled = Prefs.enabledSources(c);
        final Set<String> domains = new HashSet<>();
        int used = 0;

        for (Source s : catalog) {
            if (!enabled.contains(s.id)) {
                continue;
            }
            try {
                domains.addAll(parseHosts(Http.get(s.url)));
                used++;
            } catch (Exception e) {
                // one dead list shouldn't kill the whole update
            }
        }

        domains.addAll(Prefs.customDomains(c));
        final String content = buildHosts(domains);
        writeCache(c, content);
        final boolean helper = HostsWriter.write(c, content);

        Prefs.setLastCount(c, domains.size());
        Prefs.setLastUpdate(c, System.currentTimeMillis());
        return new Result(true, domains.size(), used, helper ? null : "fallback");
    }

    /** re-apply the cached merge, used on boot and when toggling on */
    static boolean applyCache(Context c) {
        final String cached = readCache(c);
        if (cached == null) {
            return false;
        }
        HostsWriter.write(c, cached);
        return true;
    }

    /** the real toggle: refresh content then flip the daemon's mount flag */
    static void setEnabled(Context c, boolean on) {
        if (on && !applyCache(c)) {
            update(c);
        }
        HostsWriter.setState(c, on);
    }

    /** fetch the source catalog json and stash it */
    static boolean refreshCatalog(Context c) {
        try {
            final String body = Http.get(c.getString(R.string.catalog_url));
            final String json = Catalog.unwrapGitHub(body);
            if (Catalog.parse(json).isEmpty()) {
                return false;
            }
            Prefs.setCatalog(c, json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String buildHosts(Set<String> domains) {
        final List<String> sorted = new ArrayList<>(domains);
        Collections.sort(sorted);
        final String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(new Date());
        final StringBuilder sb = new StringBuilder(sorted.size() * 20 + 128);
        sb.append("# petalOS content blocker\n");
        sb.append("# generated ").append(stamp).append('\n');
        sb.append("127.0.0.1 localhost\n");
        sb.append("::1 localhost\n");
        for (String d : sorted) {
            sb.append("0.0.0.0 ").append(d).append('\n');
        }
        return sb.toString();
    }

    /** hosts format only, adblock syntax gets skipped */
    static Set<String> parseHosts(String text) {
        final Set<String> out = new HashSet<>();
        if (text == null) {
            return out;
        }
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            final int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash).trim();
            }
            if (line.isEmpty()) {
                continue;
            }
            final String[] parts = line.split("\\s+");
            final String domain;
            if (parts.length >= 2 && isSink(parts[0])) {
                domain = parts[1];
            } else if (parts.length == 1) {
                final String solo = parts[0];
                if (solo.contains("||") || solo.contains("^") || solo.contains("$")
                        || solo.startsWith("!")) {
                    continue;
                }
                domain = solo;
            } else {
                continue;
            }
            final String d = domain.toLowerCase(Locale.US);
            if (!d.contains(".") || d.equals("localhost") || d.endsWith(".localhost")) {
                continue;
            }
            out.add(d);
        }
        return out;
    }

    private static boolean isSink(String token) {
        return "0.0.0.0".equals(token) || "127.0.0.1".equals(token)
                || "::".equals(token) || "::1".equals(token);
    }

    private static void writeCache(Context c, String content) {
        try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), CACHE))) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // cache is best effort
        }
    }

    private static String readCache(Context c) {
        final File f = new File(c.getFilesDir(), CACHE);
        if (!f.exists()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
