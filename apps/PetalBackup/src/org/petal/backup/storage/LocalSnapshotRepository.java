/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petal.backup.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.petal.backup.core.OperationControl;
import org.petal.backup.crypto.EncryptedArchive;

/** Filesystem store. Android callers must supply app-private plaintext staging. */
public final class LocalSnapshotRepository implements SnapshotRepository {
    private static final String SUFFIX = ".petalbak";
    private final Path directory;
    private final Path privateStaging;
    private final boolean numericRecovery;

    public LocalSnapshotRepository(Path directory, Path privateStaging) throws IOException {
        this(directory, privateStaging, false);
    }

    public LocalSnapshotRepository(Path directory, Path privateStaging, boolean numericRecovery) throws IOException {
        this.numericRecovery = numericRecovery;
        Files.createDirectories(Objects.requireNonNull(directory));
        Files.createDirectories(Objects.requireNonNull(privateStaging));
        this.directory = directory.toRealPath();
        this.privateStaging = privateStaging.toRealPath();
        if (this.privateStaging.startsWith(this.directory)) {
            throw new IllegalArgumentException("Plaintext staging must be outside the repository");
        }
    }

    @Override
    public String create(char[] password, OperationControl control,
            EncryptedArchive.PayloadWriter payload) throws IOException {
        control.check();
        String id = UUID.randomUUID().toString();
        Path temporary = Files.createTempFile(directory, ".writing-", ".partial");
        try {
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                OutputStream output = Channels.newOutputStream(file);
                if (numericRecovery) {
                    EncryptedArchive.encryptWithRecoveryForSnapshot(UUID.fromString(id), output, password, control, payload);
                } else {
                    EncryptedArchive.encryptForSnapshot(UUID.fromString(id), output, password, control, payload);
                }
                file.force(true);
            }
            control.check();
            // Publication is the commit point; cancellation after this is a completed write.
            Files.move(temporary, snapshot(id), StandardCopyOption.ATOMIC_MOVE);
            return id;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public List<String> list(OperationControl control) throws IOException {
        List<String> ids = new ArrayList<>();
        try (var files = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
            for (Path file : files) {
                control.check();
                String name = file.getFileName().toString();
                String id = name.substring(0, name.length() - SUFFIX.length());
                if (isId(id) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) ids.add(id);
            }
        }
        Collections.sort(ids);
        return Collections.unmodifiableList(ids);
    }

    @Override
    public void readVerified(String id, char[] password, long maxPlaintextBytes,
            OperationControl control, VerifiedPayloadReader reader) throws IOException {
        Objects.requireNonNull(reader);
        control.check();
        Path source = snapshot(id);
        Path plaintext = Files.createTempFile(privateStaging, ".reading-", ".payload");
        try {
            try (InputStream input = Files.newInputStream(source, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS);
                    FileChannel file = FileChannel.open(plaintext, StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)) {
                EncryptedArchive.decryptForSnapshot(UUID.fromString(id), input,
                        Channels.newOutputStream(file), password, maxPlaintextBytes, control);
                file.force(true);
            }
            control.check();
            reader.read(plaintext);
        } finally {
            Files.deleteIfExists(plaintext);
        }
    }

    @Override
    public void delete(String id, OperationControl control) throws IOException {
        control.check();
        Files.delete(snapshot(id));
    }

    private Path snapshot(String id) {
        if (!isId(id)) throw new IllegalArgumentException("Invalid snapshot ID");
        return directory.resolve(id + SUFFIX);
    }

    private static boolean isId(String id) {
        if (id == null) return false;
        try {
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
