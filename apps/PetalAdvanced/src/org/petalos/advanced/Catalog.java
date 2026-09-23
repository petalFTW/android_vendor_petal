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
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** reads the bundled and remote source catalog */
final class Catalog {

    private static final String ASSET = "sources.json";

    private Catalog() {}

    static List<Source> load(Context ctx) {
        String json = Prefs.catalog(ctx);
        if (json == null) {
            json = readAsset(ctx);
        }
        return parse(json);
    }

    static List<Source> parse(String json) {
        final List<Source> out = new ArrayList<>();
        if (json == null) {
            return out;
        }
        try {
            final JSONObject root = new JSONObject(json);
            final JSONArray arr = root.optJSONArray("sources");
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                out.add(Source.fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException ignored) {
            // malformed catalog, empty list is a safe landing
        }
        return out;
    }

    /** github's contents api wraps the file in base64, ssh it out */
    static String unwrapGitHub(String body) {
        if (body == null) {
            return null;
        }
        final String trimmed = body.trim();
        if (!trimmed.startsWith("{")) {
            return body;
        }
        try {
            final JSONObject o = new JSONObject(trimmed);
            if (o.optString("encoding", "").equals("base64") && o.has("content")) {
                final String clean = o.getString("content").replaceAll("\\s", "");
                return new String(Base64.decode(clean, Base64.DEFAULT), StandardCharsets.UTF_8);
            }
        } catch (JSONException ignored) {
            // not a wrapper, fine
        }
        return body;
    }

    private static String readAsset(Context ctx) {
        try (InputStream in = ctx.getAssets().open(ASSET)) {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
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
