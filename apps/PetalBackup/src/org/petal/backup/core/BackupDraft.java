/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petal.backup.core;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;

public final class BackupDraft {
    public enum Step { HOME, BACKUPS, SETTINGS, FILES, LOCATION, RECOVERY, REVIEW, PROGRESS, RESULT }
    public boolean restoring;
    public Step step = Step.HOME;
    public final List<String> sources = new ArrayList<>();
    public String archive;
    public String destination;
    public boolean legacy;
    public String operationId;

    public boolean addFiles(List<String> added) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(sources);
        merged.addAll(added);
        if (merged.size() > 256) return false;
        sources.clear();
        sources.addAll(merged);
        return true;
    }

    public boolean isWizard() {
        return step == Step.FILES || step == Step.LOCATION || step == Step.RECOVERY || step == Step.REVIEW;
    }

    public void requireSecretAfterRecreation() {
        if (step == Step.REVIEW) step = Step.RECOVERY;
    }

    public String encode() {
        Properties p = new Properties();
        p.setProperty("version", "1");
        p.setProperty("step", step.name());
        p.setProperty("restore", Boolean.toString(restoring));
        p.setProperty("legacy", Boolean.toString(legacy));
        p.setProperty("count", Integer.toString(sources.size()));
        for (int i = 0; i < sources.size(); i++) p.setProperty("source." + i, sources.get(i));
        if (archive != null) p.setProperty("archive", archive);
        if (destination != null) p.setProperty("destination", destination);
        if (operationId != null) p.setProperty("operation", operationId);
        try {
            StringWriter output = new StringWriter();
            p.store(output, null);
            return output.toString();
        } catch (java.io.IOException impossible) { throw new AssertionError(impossible); }
    }

    public static BackupDraft decode(String encoded) {
        BackupDraft draft = new BackupDraft();
        if (encoded == null || encoded.length() > 512 * 1024) return draft;
        try {
            Properties p = new Properties();
            p.load(new StringReader(encoded));
            if (!"1".equals(p.getProperty("version"))) return draft;
            int count = Integer.parseInt(p.getProperty("count", "0"));
            if (count < 0 || count > 256) return draft;
            draft.step = Step.valueOf(p.getProperty("step", "HOME"));
            draft.restoring = Boolean.parseBoolean(p.getProperty("restore"));
            draft.legacy = Boolean.parseBoolean(p.getProperty("legacy"));
            draft.archive = p.getProperty("archive");
            draft.destination = p.getProperty("destination");
            draft.operationId = p.getProperty("operation");
            for (int i = 0; i < count; i++) {
                String uri = p.getProperty("source." + i);
                if (uri == null || !uri.startsWith("content://")) return new BackupDraft();
                draft.sources.add(uri);
            }
            if ((draft.archive != null && !draft.archive.startsWith("content://"))
                    || (draft.destination != null && !draft.destination.startsWith("content://"))) return new BackupDraft();
            draft.requireSecretAfterRecreation();
            return draft;
        } catch (java.io.IOException | IllegalArgumentException invalid) { return new BackupDraft(); }
    }
}
