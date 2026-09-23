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

import org.json.JSONException;
import org.json.JSONObject;

/** one filter list entry from the catalog */
public final class Source {

    public final String id;
    public final String name;
    public final String url;
    public final String group;
    public final boolean defaultEnabled;

    public Source(String id, String name, String url, String group, boolean defaultEnabled) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.group = group;
        this.defaultEnabled = defaultEnabled;
    }

    static Source fromJson(JSONObject o) throws JSONException {
        return new Source(
                o.getString("id"),
                o.optString("name", o.getString("id")),
                o.getString("url"),
                o.optString("group", "Sources"),
                o.optBoolean("default", false));
    }
}
