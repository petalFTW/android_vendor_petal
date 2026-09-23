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

package org.petalos.hub;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** finds installed icon packs */
public final class IconPackHelper {

    /** intents the old launchers use for packs */
    private static final String[] ICON_PACK_ACTIONS = {
        "com.novalauncher.THEME",
        "org.adw.launcher.THEMES",
        "com.gau.go.launcherex.theme",
    };

    /** an installed pack */
    public static final class Entry {
        public final String packageName;
        public final String label;
        public final Drawable icon;

        Entry(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    private IconPackHelper() {}

    /** all installed packs, sorted */
    public static List<Entry> getInstalledIconPacks(Context context) {
        PackageManager pm = context.getPackageManager();
        Set<String> seen = new HashSet<>();
        List<Entry> result = new ArrayList<>();

        for (String action : ICON_PACK_ACTIONS) {
            Intent intent = new Intent(action);
            List<ResolveInfo> matches =
                    pm.queryIntentActivities(intent, PackageManager.MATCH_ALL);
            for (ResolveInfo info : matches) {
                if (info.activityInfo == null || info.activityInfo.applicationInfo == null) {
                    continue;
                }
                String pkg = info.activityInfo.packageName;
                if (!seen.add(pkg)) {
                    continue;
                }
                CharSequence label = info.loadLabel(pm);
                Drawable icon = info.loadIcon(pm);
                result.add(new Entry(
                        pkg,
                        label == null ? pkg : label.toString(),
                        icon));
            }
        }

        result.sort(Comparator.comparing(e -> e.label.toLowerCase()));
        return result;
    }

    /** display label for a pack, null if it's gone */
    public static String labelFor(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return null;
        }
        PackageManager pm = context.getPackageManager();
        for (Entry entry : getInstalledIconPacks(context)) {
            if (entry.packageName.equals(packageName)) {
                return entry.label;
            }
        }
        return null;
    }
}
