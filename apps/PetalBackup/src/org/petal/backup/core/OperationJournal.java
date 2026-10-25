/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petal.backup.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class OperationJournal {
    public enum Kind { BACKUP, RESTORE, SELF_TEST }
    public enum State {
        QUEUED, RUNNING, COMPLETE, FAILED, CANCELLED, INTERRUPTED;
        public boolean terminal() { return this != QUEUED && this != RUNNING; }
    }
    public enum Phase { QUEUED, READING, ENCRYPTING, SAVING, IMPORTING, VERIFYING, EXPORTING }

    public static final class Record {
        public final String id;
        public final Kind kind;
        public final State state;
        public final Phase phase;
        public final long createdAt;
        public final long updatedAt;
        public final List<String> sources;
        public final String archiveUri;
        public final String destinationUri;
        public final String outputUri;
        public final String snapshotId;

        private Record(String id, Kind kind, State state, Phase phase, long createdAt, long updatedAt,
                List<String> sources, String archiveUri, String destinationUri, String outputUri, String snapshotId) {
            this.id = id;
            this.kind = kind;
            this.state = state;
            this.phase = phase;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.sources = List.copyOf(sources);
            this.archiveUri = archiveUri;
            this.destinationUri = destinationUri;
            this.outputUri = outputUri;
            this.snapshotId = snapshotId;
        }
    }

    private static final Object WRITER = new Object();
    private static final int MAGIC = 0x50424a31;
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_TEXT = 8192;
    private final Path directory;

    public OperationJournal(Path directory) throws IOException {
        Files.createDirectories(directory);
        this.directory = directory.toRealPath();
    }

    public Record create(String id, Kind kind, List<String> sources, String archiveUri,
            String destinationUri) throws IOException {
        return mutate(() -> {
            Path target = path(id);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Operation already exists");
            if (kind == null || sources == null || sources.size() > 256) throw new IOException("Invalid operation plan");
            if (kind == Kind.BACKUP && (sources.isEmpty() || destinationUri == null)
                    || kind == Kind.RESTORE && (archiveUri == null || destinationUri == null)) {
                throw new IOException("Missing operation sources or destination");
            }
            long now = System.currentTimeMillis();
            Record record = new Record(id, java.util.Objects.requireNonNull(kind), State.QUEUED, Phase.QUEUED,
                    now, now, sources, archiveUri, destinationUri, kind == Kind.BACKUP ? destinationUri : null, null);
            write(record);
            return record;
        });
    }

    public Record checkpoint(String id, Phase phase, String outputUri, String snapshotId) throws IOException {
        return mutate(() -> {
            Record previous = read(id);
            if (previous.state.terminal()) throw new IOException("Operation is already finished");
            if (phase == null || phase == Phase.QUEUED) throw new IllegalArgumentException("Expected an active phase");
            if (!canAdvance(previous.kind, previous.phase, phase)) throw new IOException("Invalid operation phase transition");
            if (snapshotId != null) requireId(snapshotId);
            Record next = copy(previous, State.RUNNING, phase,
                    outputUri == null ? previous.outputUri : outputUri,
                    snapshotId == null ? previous.snapshotId : snapshotId);
            write(next);
            return next;
        });
    }

    public Record finish(String id, State state) throws IOException {
        if (state == null || !state.terminal()) throw new IllegalArgumentException("Expected a terminal state");
        return mutate(() -> {
            Record previous = read(id);
            if (previous.state == state) return previous;
            if (previous.state.terminal()) throw new IOException("Cannot rewrite a finished operation");
            if (state == State.COMPLETE && (previous.state != State.RUNNING || !canComplete(previous))) {
                throw new IOException("Operation has not reached its commit phase");
            }
            Record next = copy(previous, state, previous.phase, previous.outputUri, previous.snapshotId);
            write(next);
            return next;
        });
    }

    public List<Record> recoverInterrupted() throws IOException {
        return mutate(() -> {
            List<Record> interrupted = new ArrayList<>();
            for (Record previous : list()) {
                if (!previous.state.terminal()) {
                    Record next = copy(previous, State.INTERRUPTED, previous.phase, previous.outputUri, previous.snapshotId);
                    write(next);
                    interrupted.add(next);
                }
            }
            return List.copyOf(interrupted);
        });
    }

    public List<Record> list() throws IOException {
        List<Record> records = new ArrayList<>();
        try (var paths = Files.newDirectoryStream(directory, "*.job")) {
            for (Path entry : paths) {
                String name = entry.getFileName().toString();
                String id = name.substring(0, name.length() - 4);
                try { requireId(id); } catch (IllegalArgumentException ignored) { continue; }
                if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) records.add(read(id));
            }
        }
        records.sort(Comparator.comparingLong((Record record) -> record.updatedAt).reversed()
                .thenComparing(record -> record.id));
        return List.copyOf(records);
    }

    public Record read(String id) throws IOException {
        byte[] encoded;
        try (var input = Files.newInputStream(path(id), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            encoded = input.readNBytes(MAX_BYTES + 1);
        }
        if (encoded.length < 36 || encoded.length > MAX_BYTES) throw new IOException("Invalid operation record size");
        byte[] payload = Arrays.copyOf(encoded, encoded.length - 32);
        byte[] checksum = Arrays.copyOfRange(encoded, payload.length, encoded.length);
        if (!MessageDigest.isEqual(hash(payload), checksum)) throw new IOException("Operation record is damaged");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC || input.readInt() != 1) throw new IOException("Unsupported operation record");
            String recordedId = text(input);
            Kind kind = Kind.valueOf(text(input));
            State state = State.valueOf(text(input));
            Phase phase = Phase.valueOf(text(input));
            long created = input.readLong();
            long updated = input.readLong();
            int count = input.readInt();
            if (!id.equals(recordedId) || created < 0 || updated < created || count < 0 || count > 256) {
                throw new IOException("Invalid operation metadata");
            }
            List<String> sources = new ArrayList<>();
            for (int i = 0; i < count; i++) sources.add(text(input));
            Record record = new Record(id, kind, state, phase, created, updated, sources,
                    optional(input), optional(input), optional(input), optional(input));
            if (record.snapshotId != null) requireId(record.snapshotId);
            if (input.read() != -1) throw new IOException("Unexpected operation metadata");
            return record;
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid operation metadata", exception);
        }
    }

    private Record copy(Record previous, State state, Phase phase, String outputUri, String snapshotId) {
        return new Record(previous.id, previous.kind, state, phase, previous.createdAt,
                Math.max(previous.updatedAt, System.currentTimeMillis()), previous.sources,
                previous.archiveUri, previous.destinationUri, outputUri, snapshotId);
    }

    private void write(Record record) throws IOException {
        if (record.sources.size() > 256) throw new IOException("Too many selected source documents");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(1);
            text(output, record.id);
            text(output, record.kind.name());
            text(output, record.state.name());
            text(output, record.phase.name());
            output.writeLong(record.createdAt);
            output.writeLong(record.updatedAt);
            output.writeInt(record.sources.size());
            for (String source : record.sources) text(output, source);
            optional(output, record.archiveUri);
            optional(output, record.destinationUri);
            optional(output, record.outputUri);
            optional(output, record.snapshotId);
        }
        byte[] payload = bytes.toByteArray();
        if (payload.length > MAX_BYTES - 32) throw new IOException("Operation record is too large");
        Path temporary = Files.createTempFile(directory, ".job-", ".partial");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                writeAll(channel, ByteBuffer.wrap(payload));
                writeAll(channel, ByteBuffer.wrap(hash(payload)));
                channel.force(true);
            }
            Files.move(temporary, path(record.id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private interface Action<T> { T run() throws IOException; }

    private <T> T mutate(Action<T> action) throws IOException {
        synchronized (WRITER) {
            try (FileChannel channel = FileChannel.open(directory.resolve(".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    FileLock lock = channel.tryLock()) {
                if (lock == null) throw new IOException("Operation journal has another writer");
                return action.run();
            }
        }
    }

    private static void writeAll(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) channel.write(bytes);
    }

    private Path path(String id) { requireId(id); return directory.resolve(id + ".job"); }

    private static void requireId(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid operation identity");
    }

    private static void text(DataOutputStream output, String value) throws IOException {
        if (value == null) throw new IOException("Missing operation text");
        if (value.length() > MAX_TEXT || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw new IOException("Invalid operation text");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT) throw new IOException("Operation text is too long");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String text(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_TEXT) throw new IOException("Invalid operation text length");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static void optional(DataOutputStream output, String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) text(output, value);
    }

    private static String optional(DataInputStream input) throws IOException {
        int present = input.readUnsignedByte();
        if (present > 1) throw new IOException("Invalid optional operation field");
        return present == 0 ? null : text(input);
    }

    private static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException exception) { throw new AssertionError(exception); }
    }

    private static boolean canAdvance(Kind kind, Phase from, Phase to) {
        if (from == to) return true;
        return switch (kind) {
            case BACKUP -> from == Phase.QUEUED && to == Phase.READING
                    || from == Phase.READING && to == Phase.ENCRYPTING
                    || from == Phase.ENCRYPTING && to == Phase.SAVING;
            case RESTORE -> from == Phase.QUEUED && to == Phase.IMPORTING
                    || from == Phase.IMPORTING && to == Phase.VERIFYING
                    || from == Phase.VERIFYING && to == Phase.EXPORTING;
            case SELF_TEST -> from == Phase.QUEUED && to == Phase.ENCRYPTING
                    || from == Phase.ENCRYPTING && to == Phase.VERIFYING;
        };
    }

    private static boolean canComplete(Record record) {
        return switch (record.kind) {
            case BACKUP -> record.phase == Phase.SAVING;
            case RESTORE -> record.phase == Phase.EXPORTING;
            case SELF_TEST -> record.phase == Phase.VERIFYING;
        };
    }
}
