/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petal.backup.crypto;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.petal.backup.core.OperationControl;

// Callers own the streams; don't close someone else's bloody output.
public final class EncryptedArchive {
    private static final byte[] MAGIC = "PETALBK1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RECOVERY_MAGIC = "PETALBK2".getBytes(StandardCharsets.US_ASCII);
    private static final int RECOVERY_HEADER_BYTES = 40 + RecoveryKeyEnvelope.BYTES;
    private static final int VERSION = 1;
    private static final int KDF_PBKDF2_SHA256 = 1;
    private static final int ITERATIONS = 600_000;
    private static final int CHUNK_BYTES = 256 * 1024;
    private static final int HEADER_BYTES = 64;
    private static final int TAG_BYTES = 16;
    private static final int DATA = 1;
    private static final int END = 0;
    private static final SecureRandom RANDOM = new SecureRandom();

    private EncryptedArchive() {}

    // Just a routing hint until the whole archive authenticates.
    public static UUID inspectUnverifiedSnapshotId(InputStream source) throws IOException {
        return parseHeader(readHeader(new DataInputStream(source))).snapshotId;
    }

    @FunctionalInterface
    public interface PayloadWriter {
        void write(OutputStream destination) throws IOException;
    }

    public static final class AuthenticationException extends IOException {
        AuthenticationException(Throwable cause) {
            super("Incorrect password or damaged backup", cause);
        }
    }

    public static void encrypt(OutputStream destination, char[] password, OperationControl control,
            PayloadWriter payload) throws IOException {
        encryptForSnapshot(UUID.randomUUID(), destination, password, control, payload);
    }

    public static void encryptForSnapshot(UUID snapshotId, OutputStream destination, char[] password,
            OperationControl control, PayloadWriter payload) throws IOException {
        encryptForSnapshot(snapshotId, destination, password, control, payload, false);
    }

    public static void encryptWithRecoveryForSnapshot(UUID snapshotId, OutputStream destination, char[] number,
            OperationControl control, PayloadWriter payload) throws IOException {
        if (!RecoveryKeyEnvelope.isValidNumber(number)) {
            throw new IllegalArgumentException("Recovery number must contain exactly 16 ASCII digits");
        }
        encryptForSnapshot(snapshotId, destination, number, control, payload, true);
    }

    private static void encryptForSnapshot(UUID snapshotId, OutputStream destination, char[] password,
            OperationControl control, PayloadWriter payload, boolean recovery) throws IOException {
        Objects.requireNonNull(snapshotId);
        Objects.requireNonNull(destination);
        Objects.requireNonNull(control).check();
        Objects.requireNonNull(payload);
        try (ChunkOutput encrypted = new ChunkOutput(snapshotId, destination, password, control, recovery)) {
            payload.write(encrypted);
            encrypted.finish();
        }
    }

    // Plaintext is provisional until END and EOF are authenticated; use private staging.
    public static long decrypt(InputStream source, OutputStream destination, char[] password,
            long maxPlaintextBytes, OperationControl control) throws IOException {
        return decryptForSnapshot(null, source, destination, password, maxPlaintextBytes, control);
    }

    public static long decryptForSnapshot(UUID expectedId, InputStream source, OutputStream destination,
            char[] password, long maxPlaintextBytes, OperationControl control) throws IOException {
        if (maxPlaintextBytes < 0) throw new IllegalArgumentException("Negative restore limit");
        Objects.requireNonNull(destination);
        Objects.requireNonNull(control).check();
        DataInputStream input = new DataInputStream(Objects.requireNonNull(source));
        byte[] header = readHeader(input);
        Header parsed = parseHeader(header);
        if (expectedId != null && !expectedId.equals(parsed.snapshotId)) {
            throw new IOException("Snapshot identity does not match the selected backup");
        }
        byte[] key = parsed.envelope == null ? derive(password, parsed.salt)
                : RecoveryKeyEnvelope.unwrap(parsed.envelope, password, control);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec secret = new SecretKeySpec(key, "AES");
            long total = 0;
            for (int index = 0; index < Integer.MAX_VALUE; index++) {
                control.check();
                int type;
                try {
                    type = input.readUnsignedByte();
                } catch (EOFException exception) {
                    throw new IOException("Backup is incomplete: missing end record", exception);
                }
                int length = input.readInt();
                if (!((type == END && length == 0)
                        || (type == DATA && length > 0 && length <= CHUNK_BYTES))) {
                    throw new IOException("Invalid encrypted record");
                }
                if (length > maxPlaintextBytes - total) {
                    throw new IOException("Backup exceeds the restore size limit");
                }
                byte[] ciphertext = new byte[length + TAG_BYTES];
                byte[] plaintext = null;
                try {
                    input.readFully(ciphertext);
                    init(cipher, Cipher.DECRYPT_MODE, secret, parsed.noncePrefix,
                            header, index, type, length);
                    plaintext = cipher.doFinal(ciphertext);
                    control.check();
                    if (type == END) {
                        if (input.read() != -1) throw new IOException("Unexpected data after backup");
                        return total;
                    }
                    destination.write(plaintext);
                    total += plaintext.length;
                } finally {
                    Arrays.fill(ciphertext, (byte) 0);
                    if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
                }
            }
            throw new IOException("Too many encrypted records");
        } catch (AEADBadTagException exception) {
            throw new AuthenticationException(exception);
        } catch (GeneralSecurityException exception) {
            throw new IOException("Could not decrypt backup", exception);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static byte[] derive(char[] password, byte[] salt) throws IOException {
        if (password == null || password.length == 0 || password.length > 1024) {
            throw new IllegalArgumentException("Password must contain 1 to 1024 characters");
        }
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IOException("Password key derivation is unavailable", exception);
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] readHeader(DataInputStream input) throws IOException {
        byte[] magic = new byte[8];
        input.readFully(magic);
        int length;
        if (Arrays.equals(magic, MAGIC)) length = HEADER_BYTES;
        else if (Arrays.equals(magic, RECOVERY_MAGIC)) length = RECOVERY_HEADER_BYTES;
        else throw new IOException("Unsupported backup format");
        byte[] header = new byte[length];
        System.arraycopy(magic, 0, header, 0, magic.length);
        input.readFully(header, magic.length, length - magic.length);
        return header;
    }

    private static Header parseHeader(byte[] header) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(header);
        byte[] magic = new byte[MAGIC.length];
        buffer.get(magic);
        if (Arrays.equals(magic, RECOVERY_MAGIC)) {
            if (buffer.getInt() != 2 || buffer.getInt() != CHUNK_BYTES) {
                throw new IOException("Unsupported recovery archive");
            }
            byte[] prefix = new byte[8];
            buffer.get(prefix);
            UUID id = new UUID(buffer.getLong(), buffer.getLong());
            byte[] envelope = new byte[RecoveryKeyEnvelope.BYTES];
            buffer.get(envelope);
            RecoveryKeyEnvelope.validate(envelope);
            return new Header(null, prefix, id, envelope);
        }
        if (!Arrays.equals(magic, MAGIC) || buffer.getInt() != VERSION
                || buffer.getInt() != KDF_PBKDF2_SHA256 || buffer.getInt() != ITERATIONS
                || buffer.getInt() != CHUNK_BYTES) {
            throw new IOException("Unsupported or damaged backup header");
        }
        byte[] salt = new byte[16];
        byte[] prefix = new byte[8];
        buffer.get(salt);
        buffer.get(prefix);
        UUID snapshotId = new UUID(buffer.getLong(), buffer.getLong());
        return new Header(salt, prefix, snapshotId, null);
    }

    private static void init(Cipher cipher, int mode, SecretKeySpec secret, byte[] prefix,
            byte[] header, int index, int type, int length) throws GeneralSecurityException {
        byte[] nonce = ByteBuffer.allocate(12).put(prefix).putInt(index).array();
        cipher.init(mode, secret, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(header);
        cipher.updateAAD(ByteBuffer.allocate(9).putInt(index).put((byte) type).putInt(length).array());
    }

    private static final class Header {
        final byte[] salt;
        final byte[] noncePrefix;
        final UUID snapshotId;
        final byte[] envelope;

        Header(byte[] salt, byte[] noncePrefix, UUID snapshotId, byte[] envelope) {
            this.salt = salt;
            this.noncePrefix = noncePrefix;
            this.snapshotId = snapshotId;
            this.envelope = envelope;
        }
    }

    private static final class ChunkOutput extends OutputStream {
        private final DataOutputStream output;
        private final OperationControl control;
        private final byte[] header;
        private final byte[] noncePrefix;
        private final byte[] key;
        private final SecretKeySpec secret;
        private final Cipher cipher;
        private final byte[] buffer = new byte[CHUNK_BYTES];
        private int buffered;
        private int index;
        private boolean finished;

        ChunkOutput(UUID snapshotId, OutputStream destination, char[] password, OperationControl control,
                boolean recovery)
                throws IOException {
            this.control = control;
            byte[] salt = new byte[16];
            noncePrefix = new byte[8];
            RANDOM.nextBytes(salt);
            RANDOM.nextBytes(noncePrefix);
            if (recovery) {
                key = new byte[32];
                RANDOM.nextBytes(key);
                try {
                    byte[] envelope = RecoveryKeyEnvelope.wrap(key, password, control);
                    header = ByteBuffer.allocate(RECOVERY_HEADER_BYTES).put(RECOVERY_MAGIC)
                            .putInt(2).putInt(CHUNK_BYTES).put(noncePrefix)
                            .putLong(snapshotId.getMostSignificantBits()).putLong(snapshotId.getLeastSignificantBits())
                            .put(envelope).array();
                } catch (IOException | RuntimeException exception) {
                    Arrays.fill(key, (byte) 0);
                    throw exception;
                }
            } else {
                header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).putInt(VERSION)
                        .putInt(KDF_PBKDF2_SHA256).putInt(ITERATIONS).putInt(CHUNK_BYTES)
                        .put(salt).put(noncePrefix).putLong(snapshotId.getMostSignificantBits())
                        .putLong(snapshotId.getLeastSignificantBits()).array();
                key = derive(password, salt);
            }
            secret = new SecretKeySpec(key, "AES");
            output = new DataOutputStream(destination);
            try {
                cipher = Cipher.getInstance("AES/GCM/NoPadding");
                control.check();
                output.write(header);
            } catch (GeneralSecurityException exception) {
                Arrays.fill(key, (byte) 0);
                throw new IOException("Could not start encrypted backup", exception);
            } catch (IOException exception) {
                Arrays.fill(key, (byte) 0);
                throw exception;
            }
        }

        @Override public void write(int value) throws IOException {
            ensureOpen();
            buffer[buffered++] = (byte) value;
            if (buffered == buffer.length) record(DATA);
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            ensureOpen();
            while (length > 0) {
                control.check();
                int count = Math.min(length, buffer.length - buffered);
                System.arraycopy(bytes, offset, buffer, buffered, count);
                buffered += count;
                offset += count;
                length -= count;
                if (buffered == buffer.length) record(DATA);
            }
        }

        private void ensureOpen() throws IOException {
            if (finished) throw new IOException("Encrypted payload is already closed");
        }

        private void record(int type) throws IOException {
            control.check();
            if (index == Integer.MAX_VALUE) throw new IOException("Backup is too large");
            try {
                init(cipher, Cipher.ENCRYPT_MODE, secret, noncePrefix,
                        header, index++, type, buffered);
                byte[] ciphertext = cipher.doFinal(buffer, 0, buffered);
                output.writeByte(type);
                output.writeInt(buffered);
                output.write(ciphertext);
                Arrays.fill(ciphertext, (byte) 0);
                Arrays.fill(buffer, 0, buffered, (byte) 0);
                buffered = 0;
            } catch (GeneralSecurityException exception) {
                throw new IOException("Could not encrypt backup", exception);
            }
        }

        void finish() throws IOException {
            if (finished) return;
            if (buffered > 0) record(DATA);
            record(END);
            output.flush();
            finished = true;
        }

        @Override public void close() throws IOException {
            try {
                finish();
            } finally {
                Arrays.fill(buffer, (byte) 0);
                Arrays.fill(key, (byte) 0);
                finished = true;
            }
        }
    }
}
