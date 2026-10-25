/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petal.backup.tests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.petal.backup.core.OperationJournal;
import org.petal.backup.core.OperationJournal.Kind;
import org.petal.backup.core.OperationJournal.Phase;
import org.petal.backup.core.OperationJournal.State;

public final class OperationJournalTest {
    private static int checks;
    private interface Action { void run() throws Exception; }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void fails(Class<? extends Throwable> type, Action action) throws Exception {
        try { action.run(); }
        catch (Throwable failure) {
            require(type.isInstance(failure), "Expected " + type + ", got " + failure);
            return;
        }
        throw new AssertionError("Expected " + type);
    }

    public static void run(Path root) throws Exception {
        Path directory = root.resolve("operation-journal");
        OperationJournal first = new OperationJournal(directory);
        String id = UUID.randomUUID().toString();
        first.create(id, Kind.BACKUP, List.of("content://fixtures/日本語"), null, "content://output/archive");
        fails(IOException.class, () -> first.finish(id, State.COMPLETE));
        fails(IOException.class, () -> first.checkpoint(id, Phase.SAVING, null, null));
        first.checkpoint(id, Phase.READING, null, null);
        first.checkpoint(id, Phase.ENCRYPTING, null, null);
        String snapshot = UUID.randomUUID().toString();
        first.checkpoint(id, Phase.SAVING, null, snapshot);
        OperationJournal fresh = new OperationJournal(directory);
        OperationJournal.Record reopened = fresh.read(id);
        require(reopened.state == State.RUNNING && reopened.phase == Phase.SAVING, "Persistent stage survives fresh instance");
        require(reopened.sources.equals(List.of("content://fixtures/日本語")), "Persist the original source plan");
        require(snapshot.equals(reopened.snapshotId), "Persist committed snapshot identity");
        fails(UnsupportedOperationException.class, () -> reopened.sources.add("content://unexpected"));
        fails(IOException.class, () -> fresh.checkpoint(id, Phase.READING, null, null));
        List<OperationJournal.Record> interrupted = fresh.recoverInterrupted();
        require(interrupted.size() == 1 && interrupted.get(0).state == State.INTERRUPTED, "Restart records interruption");
        require("content://output/archive".equals(interrupted.get(0).outputUri), "Do not lose partial output identity");
        require(new OperationJournal(directory).recoverInterrupted().isEmpty(), "Recovery is idempotent");
        fails(IOException.class, () -> fresh.finish(id, State.COMPLETE));
        fails(IOException.class, () -> fresh.create(id, Kind.BACKUP, List.of("source"), null, "destination"));
        fails(IllegalArgumentException.class, () -> fresh.read("../outside"));

        String restore = UUID.randomUUID().toString();
        fresh.create(restore, Kind.RESTORE, List.of(), "content://input/archive", "content://output/tree");
        fresh.checkpoint(restore, Phase.IMPORTING, null, null);
        fresh.checkpoint(restore, Phase.VERIFYING, null, snapshot);
        fresh.checkpoint(restore, Phase.EXPORTING, "content://output/new-directory", snapshot);
        fresh.finish(restore, State.COMPLETE);
        fresh.finish(restore, State.COMPLETE);
        require(fresh.read(restore).state == State.COMPLETE, "Completion is idempotent");
        require("content://output/new-directory".equals(fresh.read(restore).outputUri), "Track the actual restored directory");
        fails(IOException.class, () -> fresh.finish(restore, State.FAILED));
        fails(IOException.class, () -> fresh.checkpoint(restore, Phase.EXPORTING, null, null));
        require(fresh.recoverInterrupted().isEmpty(), "Restart never rewrites completed history");

        String cancelled = UUID.randomUUID().toString();
        fresh.create(cancelled, Kind.SELF_TEST, List.of(), null, null);
        fresh.finish(cancelled, State.CANCELLED);
        String failed = UUID.randomUUID().toString();
        fresh.create(failed, Kind.SELF_TEST, List.of(), null, null);
        fresh.finish(failed, State.FAILED);
        Files.write(directory.resolve(".abandoned.partial"), new byte[] {0, 1});
        require(fresh.list().size() == 4, "Incomplete filenames are not operation records");
        fails(IOException.class, () -> fresh.create(UUID.randomUUID().toString(), Kind.BACKUP, List.of(), null, "destination"));
        String invalid = UUID.randomUUID().toString();
        fails(IOException.class, () -> fresh.create(invalid, Kind.BACKUP, List.of("\ud800"), null, "destination"));
        require(!Files.exists(directory.resolve(invalid + ".job")), "Malformed text cannot publish a record");

        Path record = directory.resolve(cancelled + ".job");
        byte[] bytes = Files.readAllBytes(record);
        bytes[20] ^= 1;
        Files.write(record, bytes);
        fails(IOException.class, () -> fresh.read(cancelled));
        fails(IOException.class, fresh::list);
        System.out.println("PASS: " + checks + " journal checks — restart, phases, output tracking and corrupt-record rejection.");
    }
}
