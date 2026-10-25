/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petal.backup.tests;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.petal.backup.core.FileSnapshotEngine;
import org.petal.backup.core.OperationControl;
import org.petal.backup.crypto.EncryptedArchive;
import org.petal.backup.crypto.RecoveryKeyEnvelope;
import org.petal.backup.storage.LocalSnapshotRepository;

public final class RecoveryTest {
    private static final OperationControl CONTROL = OperationControl.THREAD_INTERRUPTION;
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
        char[] number = "0000123456789012".toCharArray();
        char[] wrong = "0000123456789013".toCharArray();
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        try {
            require(RecoveryKeyEnvelope.isValidNumber(number), "Leading zeroes are valid");
            for (String invalid : new String[] {"", "123", "12345678901234567", "123456789012345x",
                    "１２３４５６７８９０１２３４５６", "1234 5678 9012 3456"}) {
                require(!RecoveryKeyEnvelope.isValidNumber(invalid.toCharArray()), "Reject non-canonical number");
            }
            require(!RecoveryKeyEnvelope.isValidNumber(null), "Reject null number");
            char[] generated = RecoveryKeyEnvelope.generateNumber();
            require(RecoveryKeyEnvelope.isValidNumber(generated), "Generated number has the required format");
            Arrays.fill(generated, '\0');
            byte[] envelope = RecoveryKeyEnvelope.wrap(key, number, CONTROL);
            require(Arrays.equals(key, RecoveryKeyEnvelope.unwrap(envelope, number, CONTROL)), "Unlock portable key envelope");
            require(!Arrays.equals(envelope, RecoveryKeyEnvelope.wrap(key, number, CONTROL)), "Fresh salt and nonce for each envelope");
            String encoded = RecoveryKeyEnvelope.toBase64(envelope);
            require(Arrays.equals(envelope, RecoveryKeyEnvelope.fromBase64(encoded)), "Base64 contains the envelope");
            fails(EncryptedArchive.AuthenticationException.class, () -> RecoveryKeyEnvelope.unwrap(envelope, wrong, CONTROL));
            fails(IOException.class, () -> RecoveryKeyEnvelope.fromBase64(encoded + "="));
            fails(IOException.class, () -> RecoveryKeyEnvelope.fromBase64("!".repeat(128)));
            byte[] expensive = envelope.clone();
            ByteBuffer.wrap(expensive).putInt(16, Integer.MAX_VALUE);
            fails(IOException.class, () -> RecoveryKeyEnvelope.unwrap(expensive, number, CONTROL));
            byte[] damaged = envelope.clone();
            damaged[60] ^= 1;
            fails(EncryptedArchive.AuthenticationException.class, () -> RecoveryKeyEnvelope.unwrap(damaged, number, CONTROL));
            AtomicInteger checks = new AtomicInteger();
            fails(InterruptedIOException.class, () -> RecoveryKeyEnvelope.wrap(key, number, () -> {
                if (checks.incrementAndGet() == 2) throw new InterruptedIOException("Cancelled after derivation");
            }));

            byte[] payload = new byte[524_311];
            new java.util.Random(17).nextBytes(payload);
            UUID id = UUID.randomUUID();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            EncryptedArchive.encryptWithRecoveryForSnapshot(id, output, number, CONTROL, stream -> stream.write(payload));
            byte[] archive = output.toByteArray();
            require(id.equals(EncryptedArchive.inspectUnverifiedSnapshotId(new ByteArrayInputStream(archive))), "Route v2 by snapshot identity");
            ByteArrayOutputStream restored = new ByteArrayOutputStream();
            EncryptedArchive.decryptForSnapshot(id, new ByteArrayInputStream(archive), restored,
                    number, payload.length, CONTROL);
            require(Arrays.equals(payload, restored.toByteArray()), "Recovery archive crosses chunk boundaries");
            fails(IOException.class, () -> EncryptedArchive.decryptForSnapshot(UUID.randomUUID(),
                    new ByteArrayInputStream(archive), new ByteArrayOutputStream(), number, payload.length, CONTROL));
            fails(IOException.class, () -> EncryptedArchive.decrypt(new ByteArrayInputStream(
                    Arrays.copyOf(archive, archive.length - 21)), new ByteArrayOutputStream(), number, payload.length, CONTROL));
            byte[] changedHeader = archive.clone();
            changedHeader[16] ^= 1;
            fails(EncryptedArchive.AuthenticationException.class, () -> EncryptedArchive.decrypt(
                    new ByteArrayInputStream(changedHeader), new ByteArrayOutputStream(), number, payload.length, CONTROL));

            Path source = Files.write(root.resolve("numeric-source"), payload);
            Path store = root.resolve("numeric-store");
            FileSnapshotEngine engine = new FileSnapshotEngine(new LocalSnapshotRepository(store, root.resolve("numeric-stage"), true));
            String snapshot = engine.backup(Map.of("nested/data.bin", source), number, CONTROL);
            // Fresh repository, same copied archive: no phone-specific key hiding here.
            Path migrated = Files.createDirectory(root.resolve("migrated-store"));
            Files.copy(store.resolve(snapshot + ".petalbak"), migrated.resolve(snapshot + ".petalbak"));
            LocalSnapshotRepository fresh = new LocalSnapshotRepository(migrated, root.resolve("migrated-stage"));
            Path destination = root.resolve("migrated-files");
            new FileSnapshotEngine(fresh).restore(snapshot, destination, number,
                    new FileSnapshotEngine.RestoreLimits(2_000_000, 2_000_000, 10), CONTROL);
            require(Arrays.equals(payload, Files.readAllBytes(destination.resolve("nested/data.bin"))), "Fresh-install offline recovery");
            AtomicBoolean exposed = new AtomicBoolean();
            fails(EncryptedArchive.AuthenticationException.class, () -> fresh.readVerified(snapshot, wrong,
                    2_000_000, CONTROL, plaintext -> exposed.set(true)));
            require(!exposed.get(), "Wrong number never reaches restore application");
            fails(IllegalArgumentException.class, () -> engine.backup(Map.of("file", source), "not-a-number".toCharArray(), CONTROL));
            require(new LocalSnapshotRepository(store, root.resolve("numeric-stage")).list(CONTROL).size() == 1,
                    "Invalid recovery number cannot publish a snapshot");
            System.out.println("PASS: " + RecoveryTest.checks + " recovery checks — envelope, v2 archive, migration and wrong-number rejection.");
        } finally {
            Arrays.fill(number, '\0');
            Arrays.fill(wrong, '\0');
            Arrays.fill(key, (byte) 0);
        }
    }
}
