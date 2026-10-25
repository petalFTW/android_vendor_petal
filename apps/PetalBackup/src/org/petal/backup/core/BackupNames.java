/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petal.backup.core;

import java.util.Locale;
import java.util.Set;

public final class BackupNames {
    private BackupNames() {}
    public static String unique(String original, Set<String> used) {
        String candidate = original;
        int dot = original.lastIndexOf('.');
        String base = dot > 0 ? original.substring(0, dot) : original;
        String extension = dot > 0 ? original.substring(dot) : "";
        int suffix = 2;
        while (!used.add(candidate.toLowerCase(Locale.ROOT))) candidate = base + " (" + suffix++ + ")" + extension;
        return candidate;
    }
}
