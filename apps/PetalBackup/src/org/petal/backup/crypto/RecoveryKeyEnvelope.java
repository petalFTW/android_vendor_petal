/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petal.backup.crypto;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.petal.backup.core.OperationControl;

public final class RecoveryKeyEnvelope {
    public static final int BYTES = 96;
    private static final byte[] MAGIC = "PETALKEY".getBytes(StandardCharsets.US_ASCII);
    private static final int ITERATIONS = 600_000;
    private static final int PREFIX_BYTES = 48;
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryKeyEnvelope() {}

    public static boolean isValidNumber(char[] number) {
        if (number == null || number.length != 16) return false;
        for (char digit : number) if (digit < '0' || digit > '9') return false;
        return true;
    }

    public static char[] generateNumber() {
        char[] number = new char[16];
        for (int i = 0; i < number.length; i++) number[i] = (char) ('0' + RANDOM.nextInt(10));
        return number;
    }

    public static byte[] wrap(byte[] vaultKey, char[] number, OperationControl control) throws IOException {
        if (vaultKey == null || vaultKey.length != 32) throw new IllegalArgumentException("Expected a 256-bit vault key");
        requireNumber(number);
        control.check();
        byte[] salt = new byte[16];
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(nonce);
        byte[] prefix = ByteBuffer.allocate(PREFIX_BYTES).put(MAGIC).putInt(1).putInt(1)
                .putInt(ITERATIONS).put(salt).put(nonce).array();
        byte[] key = derive(number, salt);
        try {
            control.check();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(prefix);
            byte[] wrapped = cipher.doFinal(vaultKey);
            try {
                control.check();
                return ByteBuffer.allocate(BYTES).put(prefix).put(wrapped).array();
            } finally {
                Arrays.fill(wrapped, (byte) 0);
            }
        } catch (GeneralSecurityException exception) {
            throw new IOException("Could not wrap backup key", exception);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public static byte[] unwrap(byte[] envelope, char[] number, OperationControl control) throws IOException {
        requireNumber(number);
        control.check();
        validate(envelope);
        byte[] salt = Arrays.copyOfRange(envelope, 20, 36);
        byte[] nonce = Arrays.copyOfRange(envelope, 36, PREFIX_BYTES);
        byte[] key = derive(number, salt);
        byte[] unwrapped = null;
        try {
            control.check();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(envelope, 0, PREFIX_BYTES);
            unwrapped = cipher.doFinal(envelope, PREFIX_BYTES, BYTES - PREFIX_BYTES);
            control.check();
            byte[] result = unwrapped;
            unwrapped = null;
            return result;
        } catch (AEADBadTagException exception) {
            throw new EncryptedArchive.AuthenticationException(exception);
        } catch (GeneralSecurityException exception) {
            throw new IOException("Could not unlock backup key", exception);
        } finally {
            Arrays.fill(key, (byte) 0);
            if (unwrapped != null) Arrays.fill(unwrapped, (byte) 0);
        }
    }

    public static String toBase64(byte[] envelope) throws IOException {
        validate(envelope);
        return Base64.getEncoder().encodeToString(envelope);
    }

    public static byte[] fromBase64(String encoded) throws IOException {
        if (encoded == null || encoded.length() != 128) throw new IOException("Invalid recovery envelope length");
        try {
            byte[] envelope = Base64.getDecoder().decode(encoded);
            validate(envelope);
            if (!Base64.getEncoder().encodeToString(envelope).equals(encoded)) {
                throw new IOException("Non-canonical recovery envelope");
            }
            return envelope;
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid recovery envelope encoding", exception);
        }
    }

    static void validate(byte[] envelope) throws IOException {
        if (envelope == null || envelope.length != BYTES) throw new IOException("Invalid recovery envelope length");
        ByteBuffer input = ByteBuffer.wrap(envelope);
        byte[] magic = new byte[MAGIC.length];
        input.get(magic);
        if (!Arrays.equals(magic, MAGIC) || input.getInt() != 1 || input.getInt() != 1
                || input.getInt() != ITERATIONS) throw new IOException("Unsupported recovery envelope");
    }

    private static void requireNumber(char[] number) {
        if (!isValidNumber(number)) throw new IllegalArgumentException("Recovery number must contain exactly 16 ASCII digits");
    }

    private static byte[] derive(char[] number, byte[] salt) throws IOException {
        PBEKeySpec spec = new PBEKeySpec(number, salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IOException("Recovery key derivation is unavailable", exception);
        } finally {
            spec.clearPassword();
        }
    }
}
