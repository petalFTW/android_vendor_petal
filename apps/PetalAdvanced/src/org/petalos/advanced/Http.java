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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** painfully tiny http get, just enough to pull lists */
final class Http {

    private Http() {}

    static String get(String url) throws IOException {
        final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/vnd.github.raw");
            conn.setRequestProperty("User-Agent", "petalOS-advanced");
            final int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = conn.getInputStream()) {
                final ByteArrayOutputStream bos = new ByteArrayOutputStream();
                final byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }
}
