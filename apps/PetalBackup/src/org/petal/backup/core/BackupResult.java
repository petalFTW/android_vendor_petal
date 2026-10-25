/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petal.backup.core;

public record BackupResult(String id, OperationJournal.Kind kind, OperationJournal.State state,
        long time, String name, String archiveUri, String outputUri, String locationUri,
        int files, long bytes, Problem problem, boolean cleanupFailed, String detail) {
    public enum Problem { NONE, AUTHENTICATION, SPACE, ACCESS, FORMAT, IO, HISTORY, INTERRUPTED, START }

    public BackupResult {
        java.util.UUID.fromString(id);
        if (kind == null || state == null || !state.terminal() || problem == null
                || time < 0 || files < -1 || bytes < -1 || name == null || detail == null) {
            throw new IllegalArgumentException("Invalid result metadata");
        }
    }

    public String backupUri() {
        return state != OperationJournal.State.COMPLETE || kind == OperationJournal.Kind.SELF_TEST
                ? null : kind == OperationJournal.Kind.BACKUP ? outputUri : archiveUri;
    }
}
