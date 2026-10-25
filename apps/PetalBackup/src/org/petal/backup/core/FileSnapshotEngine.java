/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petal.backup.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.petal.backup.storage.SnapshotRepository;

/** Selected ordinary files only. Android package/data restoration needs its own adapter. */
public final class FileSnapshotEngine {
    private static final String MANIFEST = "petal-manifest-v1";
    private static final int MANIFEST_MAGIC = 0x50424d31;
    private static final int MAX_FILES = 100_000;
    private static final int MAX_PATH_BYTES = 4096;
    private static final long MAX_MANIFEST_BYTES = 64L * 1024 * 1024;
    private final SnapshotRepository repository;

    public FileSnapshotEngine(SnapshotRepository repository) {
        this.repository = java.util.Objects.requireNonNull(repository);
    }

    public static final class RestoreLimits {
        public final long maxArchiveBytes;
        public final long maxRestoredBytes;
        public final int maxFiles;

        public RestoreLimits(long maxArchiveBytes, long maxRestoredBytes, int maxFiles) {
            if (maxArchiveBytes <= 0 || maxRestoredBytes < 0 || maxFiles <= 0 || maxFiles > MAX_FILES) {
                throw new IllegalArgumentException("Invalid restore limits");
            }
            this.maxArchiveBytes = maxArchiveBytes;
            this.maxRestoredBytes = maxRestoredBytes;
            this.maxFiles = maxFiles;
        }
    }

    public String backup(Map<String, Path> selected, char[] password, OperationControl control)
            throws IOException {
        if (selected.isEmpty() || selected.size() > MAX_FILES) {
            throw new IllegalArgumentException("Select between 1 and " + MAX_FILES + " files");
        }
        Map<String, Path> sources = new java.util.HashMap<>(selected);
        List<String> names = new ArrayList<>(sources.keySet());
        names.sort(String::compareTo);
        long manifestBytes = 12;
        for (String name : names) {
            validatePath(name);
            manifestBytes += 52L + name.getBytes(StandardCharsets.UTF_8).length;
        }
        if (manifestBytes > MAX_MANIFEST_BYTES) throw new IOException("Snapshot manifest is too large");
        return repository.create(password, control, output -> {
            List<Entry> entries = new ArrayList<>();
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                byte[] buffer = new byte[64 * 1024];
                for (String name : names) {
                    control.check();
                    Path source = sources.get(name);
                    if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Backup source is not a regular file: " + name);
                    }
                    long expected = Files.size(source);
                    FileTime modified = Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS);
                    MessageDigest digest = sha256();
                    ZipEntry item = new ZipEntry("payload/" + entries.size());
                    item.setTime(0);
                    zip.putNextEntry(item);
                    long copied = 0;
                    try (InputStream input = Files.newInputStream(source,
                            StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            control.check();
                            if (count > expected - copied) throw new IOException("Source grew: " + name);
                            zip.write(buffer, 0, count);
                            digest.update(buffer, 0, count);
                            copied += count;
                        }
                    }
                    zip.closeEntry();
                    if (copied != expected || !modified.equals(
                            Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS))) {
                        throw new IOException("Source changed during backup: " + name);
                    }
                    entries.add(new Entry(name, copied, modified.toMillis(), digest.digest()));
                }
                zip.putNextEntry(new ZipEntry(MANIFEST));
                writeManifest(new DataOutputStream(zip), entries, control);
                zip.closeEntry();
            }
        });
    }

    public void restore(String id, Path newDirectory, char[] password, RestoreLimits limits,
            OperationControl control) throws IOException {
        Path destination = newDirectory.toAbsolutePath().normalize();
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Restore requires a new destination directory");
        }
        if (destination.getParent() == null) throw new IOException("Missing restore parent");
        Path parent = destination.getParent().toRealPath();
        Path target = parent.resolve(destination.getFileName());
        repository.readVerified(id, password, limits.maxArchiveBytes, control, plaintext -> {
            Path stage = Files.createTempDirectory(parent, ".petal-restore-");
            boolean reserved = false;
            boolean committed = false;
            try {
                Path objects = Files.createDirectory(stage.resolve("objects"));
                Path tree = Files.createDirectory(stage.resolve("tree"));
                List<Entry> manifest = extract(plaintext, objects, limits, control);
                for (int index = 0; index < manifest.size(); index++) {
                    control.check();
                    Entry entry = manifest.get(index);
                    Path restored = tree.resolve(entry.name).normalize();
                    if (!restored.startsWith(tree)) throw new IOException("Invalid restore path");
                    Files.createDirectories(restored.getParent());
                    Files.move(objects.resolve(Integer.toString(index)), restored);
                    Files.setLastModifiedTime(restored, FileTime.fromMillis(entry.modified));
                }
                control.check();
                // Reserve without replacing even an empty directory created during decryption.
                Files.createDirectory(target);
                reserved = true;
                try (var children = Files.newDirectoryStream(tree)) {
                    for (Path child : children) {
                        control.check();
                        Files.move(child, target.resolve(child.getFileName()));
                    }
                }
                committed = true;
            } finally {
                try {
                    if (reserved && !committed) deleteTree(target);
                } finally {
                    deleteTree(stage);
                }
            }
        });
    }

    private static List<Entry> extract(Path archive, Path objects, RestoreLimits limits,
            OperationControl control) throws IOException {
        List<Entry> actual = new ArrayList<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry item;
            byte[] buffer = new byte[64 * 1024];
            while ((item = zip.getNextEntry()) != null) {
                control.check();
                if (MANIFEST.equals(item.getName())) {
                    List<Entry> declared = readManifest(new DataInputStream(
                            new LimitedInput(zip, MAX_MANIFEST_BYTES)), limits.maxFiles, control);
                    if (zip.read() != -1 || zip.getNextEntry() != null || declared.size() != actual.size()) {
                        throw new IOException("Snapshot manifest does not match its payload");
                    }
                    for (int index = 0; index < actual.size(); index++) {
                        Entry expected = declared.get(index);
                        Entry found = actual.get(index);
                        if (expected.size != found.size || !MessageDigest.isEqual(expected.hash, found.hash)) {
                            throw new IOException("File integrity check failed");
                        }
                    }
                    return declared;
                }
                if (actual.size() >= limits.maxFiles || item.isDirectory()
                        || !("payload/" + actual.size()).equals(item.getName())) {
                    throw new IOException("Unexpected snapshot entry");
                }
                MessageDigest digest = sha256();
                long size = 0;
                try (var output = Files.newOutputStream(objects.resolve(Integer.toString(actual.size())),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        control.check();
                        if (count > limits.maxRestoredBytes - total) {
                            throw new IOException("Snapshot exceeds the expanded size limit");
                        }
                        output.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                        size += count;
                        total += count;
                    }
                }
                actual.add(new Entry("", size, 0, digest.digest()));
            }
        }
        throw new IOException("Snapshot manifest is missing");
    }

    private static void writeManifest(DataOutputStream output, List<Entry> entries,
            OperationControl control) throws IOException {
        output.writeInt(MANIFEST_MAGIC);
        output.writeInt(1);
        output.writeInt(entries.size());
        for (Entry entry : entries) {
            control.check();
            byte[] name = entry.name.getBytes(StandardCharsets.UTF_8);
            output.writeInt(name.length);
            output.write(name);
            output.writeLong(entry.size);
            output.writeLong(entry.modified);
            output.write(entry.hash);
        }
    }

    private static List<Entry> readManifest(DataInputStream input, int maxFiles,
            OperationControl control) throws IOException {
        if (input.readInt() != MANIFEST_MAGIC || input.readInt() != 1) {
            throw new IOException("Unsupported snapshot manifest");
        }
        int count = input.readInt();
        if (count <= 0 || count > maxFiles) throw new IOException("Invalid snapshot file count");
        List<Entry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int index = 0; index < count; index++) {
            control.check();
            int length = input.readInt();
            if (length <= 0 || length > MAX_PATH_BYTES) throw new IOException("Invalid path length");
            byte[] encoded = new byte[length];
            input.readFully(encoded);
            String name = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded)).toString();
            validatePath(name);
            if (!names.add(name)) throw new IOException("Duplicate restore path");
            long size = input.readLong();
            long modified = input.readLong();
            if (size < 0) throw new IOException("Invalid file size");
            byte[] hash = new byte[32];
            input.readFully(hash);
            entries.add(new Entry(name, size, modified, hash));
        }
        return entries;
    }

    private static void validatePath(String name) throws IOException {
        if (name == null || name.isEmpty() || !StandardCharsets.UTF_8.newEncoder().canEncode(name)
                || name.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES
                || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                || name.chars().anyMatch(character -> character < 32)) {
            throw new IOException("Invalid logical file path");
        }
        for (String part : name.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IOException("Restore paths must be relative and normalized");
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static final class Entry {
        final String name;
        final long size;
        final long modified;
        final byte[] hash;

        Entry(String name, long size, long modified, byte[] hash) {
            this.name = name;
            this.size = size;
            this.modified = modified;
            this.hash = hash;
        }
    }

    private static final class LimitedInput extends FilterInputStream {
        private long remaining;

        LimitedInput(InputStream input, long limit) {
            super(input);
            remaining = limit;
        }

        @Override public int read() throws IOException {
            if (remaining == 0) throw new IOException("Manifest is too large");
            int value = in.read();
            if (value != -1) remaining--;
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining == 0) throw new IOException("Manifest is too large");
            int count = in.read(bytes, offset, (int) Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }
    }
}
