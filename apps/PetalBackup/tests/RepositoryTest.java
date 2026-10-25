/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petal.backup.tests;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.petal.backup.core.FileSnapshotEngine;
import org.petal.backup.core.OperationControl;
import org.petal.backup.crypto.EncryptedArchive;
import org.petal.backup.storage.LocalSnapshotRepository;

public final class RepositoryTest {
    private static final char[] PASSWORD = "host fixture — portable vault 🔐".toCharArray();
    private static final OperationControl CONTROL = OperationControl.THREAD_INTERRUPTION;
    private static int checks;

    @FunctionalInterface interface Action { void run() throws Exception; }

    private static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void fails(Class<? extends Throwable> expected, Action action) throws Exception {
        try {
            action.run();
        } catch (Throwable failure) {
            require(expected.isInstance(failure), "Expected " + expected + ", got " + failure);
            return;
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }

    private static byte[] encrypt(byte[] plaintext) throws IOException {
        var output = new ByteArrayOutputStream();
        EncryptedArchive.encrypt(output, PASSWORD, CONTROL, stream -> stream.write(plaintext));
        return output.toByteArray();
    }

    private static byte[] decrypt(byte[] ciphertext, long limit) throws IOException {
        var output = new ByteArrayOutputStream();
        EncryptedArchive.decrypt(new ByteArrayInputStream(ciphertext), output, PASSWORD, limit, CONTROL);
        return output.toByteArray();
    }

    private static long files(Path directory) throws IOException {
        try (var entries = Files.list(directory)) { return entries.count(); }
    }

    private static void cryptoCases() throws Exception {
        Random fixture = new Random(139);
        for (int size : new int[] {0, 1, 262143, 262144, 262145, 524311}) {
            byte[] data = new byte[size];
            fixture.nextBytes(data);
            require(Arrays.equals(data, decrypt(encrypt(data), size)), "Chunk-boundary round trip");
        }
        byte[] data = new byte[524311];
        fixture.nextBytes(data);
        byte[] encrypted = encrypt(data);
        require(!Arrays.equals(encrypted, encrypt(data)), "Fresh salt/nonce required");
        fails(EncryptedArchive.AuthenticationException.class, () -> EncryptedArchive.decrypt(
                new ByteArrayInputStream(encrypted), new ByteArrayOutputStream(),
                "wrong password".toCharArray(), data.length, CONTROL));

        byte[] flipped = encrypted.clone();
        flipped[80] ^= 1;
        fails(EncryptedArchive.AuthenticationException.class, () -> decrypt(flipped, data.length));
        byte[] changedSalt = encrypted.clone();
        changedSalt[24] ^= 1;
        fails(EncryptedArchive.AuthenticationException.class, () -> decrypt(changedSalt, data.length));
        fails(IOException.class, () -> decrypt(Arrays.copyOf(encrypted, encrypted.length - 21), data.length));
        fails(IOException.class, () -> decrypt(Arrays.copyOf(encrypted, encrypted.length - 1), data.length));
        fails(IOException.class, () -> decrypt(Arrays.copyOf(encrypted, encrypted.length + 1), data.length));
        fails(IOException.class, () -> decrypt(encrypted, data.length - 1));
        byte[] expensiveHeader = encrypted.clone();
        ByteBuffer.wrap(expensiveHeader).putInt(16, Integer.MAX_VALUE);
        fails(IOException.class, () -> decrypt(expensiveHeader, data.length));
        byte[] hugeChunk = encrypted.clone();
        ByteBuffer.wrap(hugeChunk).putInt(20, Integer.MAX_VALUE);
        fails(IOException.class, () -> decrypt(hugeChunk, data.length));

        int frame = 5 + 262144 + 16;
        byte[] reordered = encrypted.clone();
        System.arraycopy(encrypted, 64, reordered, 64 + frame, frame);
        System.arraycopy(encrypted, 64 + frame, reordered, 64, frame);
        fails(EncryptedArchive.AuthenticationException.class, () -> decrypt(reordered, data.length));
        AtomicInteger calls = new AtomicInteger();
        fails(InterruptedIOException.class, () -> EncryptedArchive.encrypt(
                new ByteArrayOutputStream(), PASSWORD, () -> {
                    if (calls.incrementAndGet() == 2) throw new InterruptedIOException("Cancelled after KDF");
                }, stream -> stream.write(data)));
    }

    private static void repositoryCases(Path root) throws Exception {
        Path store = root.resolve("repository");
        Path scratch = root.resolve("private-staging");
        LocalSnapshotRepository repository = new LocalSnapshotRepository(store, scratch);
        byte[] data = "An actual portable backup payload".getBytes(StandardCharsets.UTF_8);
        String id = repository.create(PASSWORD, CONTROL, output -> output.write(data));
        require(repository.list(CONTROL).equals(java.util.List.of(id)), "Publish one snapshot");
        require(files(store) == 1, "No temporary ciphertext after success");

        // A new repository object has no remembered key or Android Keystore state.
        new LocalSnapshotRepository(store, scratch).readVerified(id, PASSWORD, 1024, CONTROL,
                plaintext -> require(Arrays.equals(data, Files.readAllBytes(plaintext)), "Portable reopen"));
        require(files(scratch) == 0, "Plaintext staging cleaned after success");
        AtomicBoolean called = new AtomicBoolean();
        fails(EncryptedArchive.AuthenticationException.class, () -> repository.readVerified(id,
                "wrong".toCharArray(), 1024, CONTROL, plaintext -> called.set(true)));
        require(!called.get() && files(scratch) == 0, "Wrong password cannot reach restore callback");
        fails(IOException.class, () -> repository.readVerified(id, PASSWORD, 1, CONTROL,
                plaintext -> called.set(true)));
        require(!called.get() && files(scratch) == 0, "Size limit cannot reach restore callback");
        fails(IOException.class, () -> repository.readVerified(id, PASSWORD, 1024, CONTROL,
                plaintext -> { throw new IOException("Injected restore consumer failure"); }));
        require(files(scratch) == 0, "Consumer failure cleans plaintext staging");

        fails(IOException.class, () -> repository.create(PASSWORD, CONTROL, output -> {
            output.write(data);
            throw new IOException("Injected producer failure");
        }));
        require(repository.list(CONTROL).size() == 1 && files(store) == 1,
                "Failed writer must not publish a snapshot");
        AtomicInteger calls = new AtomicInteger();
        fails(InterruptedIOException.class, () -> repository.create(PASSWORD, () -> {
            if (calls.incrementAndGet() >= 4) throw new InterruptedIOException("Cancelled");
        }, output -> output.write(new byte[1024 * 1024])));
        require(repository.list(CONTROL).size() == 1 && files(store) == 1,
                "Cancelled writer must not publish a snapshot");

        Path archive = store.resolve(id + ".petalbak");
        byte[] original = Files.readAllBytes(archive);
        String other = repository.create(PASSWORD, CONTROL, output -> output.write("other snapshot".getBytes(StandardCharsets.UTF_8)));
        Files.copy(store.resolve(other + ".petalbak"), archive, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        fails(IOException.class, () -> repository.readVerified(id, PASSWORD, 1024, CONTROL,
                plaintext -> called.set(true)));
        require(!called.get() && files(scratch) == 0, "A valid snapshot cannot impersonate another ID");
        Files.write(archive, original);
        repository.delete(other, CONTROL);
        byte[] corrupted = Files.readAllBytes(archive);
        corrupted[80] ^= 1;
        Files.write(archive, corrupted);
        fails(EncryptedArchive.AuthenticationException.class, () -> repository.readVerified(id,
                PASSWORD, 1024, CONTROL, plaintext -> called.set(true)));
        require(!called.get() && files(scratch) == 0, "Corrupt data cannot reach restore callback");

        fails(IllegalArgumentException.class, () -> repository.delete("../outside", CONTROL));
        String linkId = UUID.randomUUID().toString();
        Files.createSymbolicLink(store.resolve(linkId + ".petalbak"), archive);
        require(repository.list(CONTROL).size() == 1, "Ignore symlinked snapshot files");
        fails(IOException.class, () -> repository.readVerified(linkId, PASSWORD, 1024, CONTROL,
                plaintext -> called.set(true)));
        require(files(scratch) == 0, "Failed symlink read cleans staging");
        repository.delete(id, CONTROL);
        require(repository.list(CONTROL).isEmpty(), "Delete an explicit snapshot");
    }

    private static String crafted(LocalSnapshotRepository repository, String logicalName,
            boolean correctHash, boolean unexpectedZipEntry) throws Exception {
        byte[] data = "fixture".getBytes(StandardCharsets.UTF_8);
        return repository.create(PASSWORD, CONTROL, encrypted -> {
            try (ZipOutputStream zip = new ZipOutputStream(encrypted)) {
                zip.putNextEntry(new ZipEntry(unexpectedZipEntry ? "../../escape" : "payload/0"));
                zip.write(data);
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("petal-manifest-v1"));
                DataOutputStream manifest = new DataOutputStream(zip);
                manifest.writeInt(0x50424d31);
                manifest.writeInt(1);
                manifest.writeInt(1);
                byte[] name = logicalName.getBytes(StandardCharsets.UTF_8);
                manifest.writeInt(name.length);
                manifest.write(name);
                manifest.writeLong(data.length);
                manifest.writeLong(0);
                try {
                    manifest.write(correctHash ? MessageDigest.getInstance("SHA-256").digest(data)
                            : new byte[32]);
                } catch (java.security.NoSuchAlgorithmException exception) {
                    throw new AssertionError(exception);
                }
                zip.closeEntry();
            }
        });
    }

    private static void fileCases(Path root) throws Exception {
        Path scratch = root.resolve("file-staging");
        LocalSnapshotRepository repository = new LocalSnapshotRepository(root.resolve("file-repository"), scratch);
        FileSnapshotEngine engine = new FileSnapshotEngine(repository);
        var limits = new FileSnapshotEngine.RestoreLimits(16 * 1024 * 1024, 8 * 1024 * 1024, 100);
        Path sources = Files.createDirectory(root.resolve("sources"));
        Path photo = sources.resolve("image");
        byte[] random = new byte[700_000];
        new Random(92).nextBytes(random);
        Files.write(photo, random);
        Path empty = Files.createFile(sources.resolve("empty"));
        Path text = Files.write(sources.resolve("text"),
                "A real encrypted file backup.\n".getBytes(StandardCharsets.UTF_8));
        String id = engine.backup(Map.of("photos/image.bin", photo, "empty", empty,
                "notes/日本語.txt", text), PASSWORD, CONTROL);
        Path restored = root.resolve("restored");
        engine.restore(id, restored, PASSWORD, limits, CONTROL);
        require(Arrays.equals(random, Files.readAllBytes(restored.resolve("photos/image.bin"))), "Binary file restore");
        require(Files.size(restored.resolve("empty")) == 0, "Empty file restore");
        require(Arrays.equals(Files.readAllBytes(restored.resolve("notes/日本語.txt")),
                Files.readAllBytes(text)), "Unicode file restore");
        require(files(scratch) == 0, "Private ZIP staging removed");
        fails(IOException.class, () -> engine.restore(id, restored, PASSWORD, limits, CONTROL));
        require(Arrays.equals(random, Files.readAllBytes(restored.resolve("photos/image.bin"))), "Existing destination retained");
        fails(IOException.class, () -> engine.backup(Map.of("../escape", text), PASSWORD, CONTROL));
        fails(IOException.class, () -> engine.backup(Map.of("invalid\ud800", text), PASSWORD, CONTROL));

        Path raced = root.resolve("raced-destination");
        AtomicBoolean created = new AtomicBoolean();
        OperationControl createDuringRestore = () -> {
            CONTROL.check();
            try (var children = Files.list(root)) {
                if (!created.get() && children.anyMatch(path -> path.getFileName().toString().startsWith(".petal-restore-"))) {
                    Files.createDirectory(raced);
                    created.set(true);
                }
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        };
        fails(IOException.class, () -> engine.restore(id, raced, PASSWORD, limits, createDuringRestore));
        require(created.get() && Files.isDirectory(raced) && files(raced) == 0,
                "A destination created during restore is not replaced");

        Path cancelledTarget = root.resolve("cancelled-restore");
        OperationControl cancelRestore = () -> {
            CONTROL.check();
            try (var children = Files.list(root)) {
                if (children.anyMatch(path -> path.getFileName().toString().startsWith(".petal-restore-"))) {
                    throw new InterruptedIOException("Cancelled during extraction");
                }
            } catch (InterruptedIOException cancelled) {
                throw cancelled;
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        };
        fails(InterruptedIOException.class, () -> engine.restore(id, cancelledTarget, PASSWORD, limits, cancelRestore));
        require(!Files.exists(cancelledTarget) && files(scratch) == 0,
                "Cancelled restore leaves no destination or plaintext cache");

        for (String name : new String[] {"../escape", "/absolute", "a/../../escape", "a\\escape", "a//b"}) {
            String bad = crafted(repository, name, true, false);
            Path target = root.resolve("bad-path-" + UUID.randomUUID());
            fails(IOException.class, () -> engine.restore(bad, target, PASSWORD, limits, CONTROL));
            require(!Files.exists(target) && files(scratch) == 0, "Reject authenticated traversal manifest");
        }
        String badHash = crafted(repository, "file", false, false);
        Path hashTarget = root.resolve("bad-hash");
        fails(IOException.class, () -> engine.restore(badHash, hashTarget, PASSWORD, limits, CONTROL));
        require(!Files.exists(hashTarget), "Mismatched checksum not published");
        String zipSlip = crafted(repository, "file", true, true);
        fails(IOException.class, () -> engine.restore(zipSlip, root.resolve("bad-zip"), PASSWORD, limits, CONTROL));
        require(!Files.exists(root.resolve("escape")), "ZIP entry names cannot escape staging");

        Path compressible = Files.write(sources.resolve("zeros"), new byte[1024 * 1024]);
        String expanded = engine.backup(Map.of("zeros", compressible), PASSWORD, CONTROL);
        Path bombTarget = root.resolve("expanded-limit");
        fails(IOException.class, () -> engine.restore(expanded, bombTarget, PASSWORD,
                new FileSnapshotEngine.RestoreLimits(16 * 1024 * 1024, 16, 100), CONTROL));
        require(!Files.exists(bombTarget) && files(scratch) == 0, "Expanded bytes bounded before publication");
        try (var entries = Files.list(root)) {
            require(entries.noneMatch(path -> path.getFileName().toString().startsWith(".petal-restore-")),
                    "All restore staging cleaned");
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(args[0]), "repository-fixture-");
        try {
            cryptoCases();
            repositoryCases(root);
            fileCases(root);
            RecoveryTest.run(root);
            OperationJournalTest.run(root);
            BrokerContractTest.run();
            System.out.println("PASS: " + checks + " assertions — encryption, authentication, "
                    + "commit/cancel, portable reopen, file restore, path and expansion limits.");
        } finally {
            Arrays.fill(PASSWORD, '\0');
            try (var entries = Files.walk(root)) {
                for (Path path : entries.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
