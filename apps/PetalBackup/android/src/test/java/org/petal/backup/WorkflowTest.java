/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petal.backup;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import org.petal.backup.core.BackupDraft;
import org.petal.backup.core.BackupNames;
import org.petal.backup.core.BackupResult;
import org.petal.backup.core.BackupResultStore;
import org.petal.backup.core.OperationJournal;

public final class WorkflowTest {
    @Test public void pickerChoicesSurviveRecreationButReviewRequiresRecoveryAgain() {
        BackupDraft draft = new BackupDraft();
        draft.addFiles(List.of("content://files/first", "content://files/second"));
        draft.destination = "content://files/new-backup";
        draft.step = BackupDraft.Step.REVIEW;
        BackupDraft restored = BackupDraft.decode(draft.encode());
        assertEquals(draft.sources, restored.sources);
        assertEquals(draft.destination, restored.destination);
        assertEquals(BackupDraft.Step.RECOVERY, restored.step);
    }

    @Test public void restoreArchiveAndFolderSurvivePickerRecreation() {
        BackupDraft draft = new BackupDraft();
        draft.restoring = true;
        draft.legacy = true;
        draft.archive = "content://files/older-backup";
        draft.destination = "content://files/restore-folder";
        draft.step = BackupDraft.Step.LOCATION;
        BackupDraft restored = BackupDraft.decode(draft.encode());
        assertTrue(restored.restoring);
        assertTrue(restored.legacy);
        assertEquals(draft.archive, restored.archive);
        assertEquals(draft.destination, restored.destination);
        assertEquals(BackupDraft.Step.LOCATION, restored.step);
    }

    @Test public void addingFilesIsAdditiveAndDuplicateSafe() {
        BackupDraft draft = new BackupDraft();
        assertTrue(draft.addFiles(List.of("content://files/a", "content://files/b")));
        assertTrue(draft.addFiles(List.of("content://files/b", "content://files/c")));
        assertEquals(List.of("content://files/a", "content://files/b", "content://files/c"), draft.sources);
    }

    @Test public void overLimitDoesNotDiscardPreviousSelection() {
        BackupDraft draft = new BackupDraft();
        for (int i = 0; i < 256; i++) draft.sources.add("content://files/" + i);
        List<String> original = List.copyOf(draft.sources);
        assertFalse(draft.addFiles(List.of("content://files/extra")));
        assertEquals(original, draft.sources);
    }

    @Test public void corruptDraftCannotIntroduceFilePaths() {
        assertEquals(BackupDraft.Step.HOME, BackupDraft.decode("junk").step);
        BackupDraft draft = new BackupDraft();
        draft.sources.add("file:///private/secret");
        assertTrue(BackupDraft.decode(draft.encode()).sources.isEmpty());
    }

    @Test public void duplicateNamesKeepTheirExtensions() {
        HashSet<String> names = new HashSet<>();
        assertEquals("Photo.jpg", BackupNames.unique("Photo.jpg", names));
        assertEquals("photo (2).jpg", BackupNames.unique("photo.jpg", names));
        assertEquals("Photo (3).jpg", BackupNames.unique("Photo.jpg", names));
        assertEquals("notes", BackupNames.unique("notes", names));
        assertEquals("notes (2)", BackupNames.unique("notes", names));
    }

    @Test public void resultsRoundTripWithLocationsAndFailureContext() throws Exception {
        Path root = Files.createTempDirectory("backup-results-");
        try {
            BackupResultStore store = new BackupResultStore(root);
            BackupResult result = result(OperationJournal.Kind.BACKUP, OperationJournal.State.FAILED, 10,
                    BackupResult.Problem.ACCESS, true);
            store.save(result);
            assertEquals(List.of(result), store.list());
        } finally { remove(root); }
    }

    @Test public void catalogueIsNewestFirstAndIgnoresBrokenEntries() throws Exception {
        Path root = Files.createTempDirectory("backup-catalogue-");
        try {
            BackupResultStore store = new BackupResultStore(root);
            BackupResult older = result(OperationJournal.Kind.BACKUP, OperationJournal.State.COMPLETE, 10,
                    BackupResult.Problem.NONE, false);
            BackupResult newer = result(OperationJournal.Kind.RESTORE, OperationJournal.State.COMPLETE, 20,
                    BackupResult.Problem.NONE, false);
            store.save(older);
            store.save(newer);
            Files.write(root.resolve(UUID.randomUUID() + ".properties"), "invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(List.of(newer, older), store.list());
            assertEquals(newer.archiveUri(), newer.backupUri());
            assertEquals(older.outputUri(), older.backupUri());
        } finally { remove(root); }
    }

    @Test public void incompleteOperationsNeverAppearAsVerifiedBackups() {
        for (OperationJournal.State state : List.of(OperationJournal.State.FAILED,
                OperationJournal.State.CANCELLED, OperationJournal.State.INTERRUPTED)) {
            assertNull(result(OperationJournal.Kind.BACKUP, state, 10, BackupResult.Problem.IO, false).backupUri());
        }
        assertNull(result(OperationJournal.Kind.SELF_TEST, OperationJournal.State.COMPLETE, 10,
                BackupResult.Problem.NONE, false).backupUri());
    }

    @Test public void savingSameResultDoesNotDuplicateHistory() throws Exception {
        Path root = Files.createTempDirectory("backup-history-");
        try {
            BackupResultStore store = new BackupResultStore(root);
            BackupResult result = result(OperationJournal.Kind.BACKUP, OperationJournal.State.COMPLETE, 10,
                    BackupResult.Problem.NONE, false);
            store.save(result);
            store.save(result);
            assertEquals(1, store.list().size());
            try (var files = Files.list(root)) { assertEquals(1, files.count()); }
        } finally { remove(root); }
    }

    private static BackupResult result(OperationJournal.Kind kind, OperationJournal.State state,
            long time, BackupResult.Problem problem, boolean cleanup) {
        return new BackupResult(UUID.randomUUID().toString(), kind, state, time, "Family photos.petalbak",
                "content://storage/archive", "content://storage/output", "content://storage/folder",
                12, 4096, problem, cleanup, "Readable operation details");
    }

    private static void remove(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
