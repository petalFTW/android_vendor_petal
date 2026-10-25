package org.petalos.proton4a.runtime;

import android.content.Context;

import com.android.apksig.ApkSigner;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.zip.CRC32;

/** Builds one ordinary, signed, installable launcher APK per EXE, on the device. */
public final class ProgramApk {
    private static final String PACKAGE_PREFIX = "org.petalos.proton4a.program.p";
    private static final String TEMPLATE_PACKAGE = PACKAGE_PREFIX + "0000000000000000";
    private static final String TEMPLATE_LABEL = "Program placeholder";
    private static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;

    public static final class Result {
        public final String packageName;
        public final File apk;

        Result(String packageName, File apk) {
            this.packageName = packageName;
            this.apk = apk;
        }
    }

    private ProgramApk() {}

    public interface ProgressListener {
        void onProgress(String stage, long bytes);
    }

    static Result build(Context context, InputStream source, String displayName) throws Exception {
        return build(context, context, source, displayName, null);
    }

    /** assetsContext supplies the template and signing identity; context owns the APK cache. */
    public static Result build(Context context, Context assetsContext, InputStream source,
            String displayName, ProgressListener progress) throws Exception {
        File directory = new File(context.getCacheDir(), "program-apks");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create APK cache");
        File exe = new File(directory, "import.exe");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try {
            try (FileOutputStream output = new FileOutputStream(exe)) {
                byte[] buffer = new byte[65536];
                int count;
                long total = 0;
                while ((count = source.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_BYTES) throw new IOException("EXE exceeds 2 GiB limit");
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    if (progress != null) progress.onProgress("copy", total);
                }
            }
            checkExecutable(exe);
            if (progress != null) progress.onProgress("package", 0);
            byte[] hash = digest.digest();
            StringBuilder id = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                id.append(Character.forDigit((hash[i] >>> 4) & 15, 16));
                id.append(Character.forDigit(hash[i] & 15, 16));
            }
            String packageName = PACKAGE_PREFIX + id;
            String label = displayName.replaceFirst("(?i)\\.exe$", "").trim();
            if (label.isEmpty()) label = "Windows program";
            if (label.length() > 60) label = label.substring(0, 60);

            File unsigned = new File(directory, id + ".unsigned.apk");
            File signed = new File(directory, id + ".apk");
            try (ZipInputStream template = new ZipInputStream(
                        assetsContext.getAssets().open("program-template.apk"));
                    CountingOutputStream counter = new CountingOutputStream(
                            new FileOutputStream(unsigned));
                    ZipOutputStream output = new ZipOutputStream(counter)) {
                ZipEntry entry;
                while ((entry = template.getNextEntry()) != null) {
                    if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) continue;
                    byte[] data = null;
                    if (!entry.getName().equals("assets/program.exe")) {
                        data = entry.getName().equals("AndroidManifest.xml")
                                ? rewriteManifest(readAll(template), packageName, label)
                                : readAll(template);
                    }
                    ZipEntry next = new ZipEntry(entry.getName());
                    if (entry.getMethod() == ZipEntry.STORED && data != null) {
                        CRC32 crc = new CRC32();
                        crc.update(data);
                        next.setMethod(ZipEntry.STORED);
                        next.setSize(data.length);
                        next.setCrc(crc.getValue());
                        // Android maps uncompressed resources.arsc directly from the APK.
                        // Keep its data start aligned after the ZIP local file header.
                        int headerEnd = (int) (counter.count + 30
                                + entry.getName().getBytes(StandardCharsets.UTF_8).length);
                        int padding = (4 - ((headerEnd + 4) & 3)) & 3;
                        byte[] extra = new byte[4 + padding];
                        extra[0] = (byte) 0xef;
                        extra[1] = (byte) 0xbe;
                        extra[2] = (byte) padding;
                        next.setExtra(extra);
                    }
                    output.putNextEntry(next);
                    if (entry.getName().equals("assets/program.exe")) {
                        try (InputStream input = new FileInputStream(exe)) { copy(input, output); }
                    } else {
                        output.write(data);
                    }
                    output.closeEntry();
                    template.closeEntry();
                }
            }

            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(readAsset(assetsContext, "program-signing-key.pk8")));
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(assetsContext.getAssets().open("program-signing-cert.der"));
            if (progress != null) progress.onProgress("sign", 0);
            ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                    "program", key, Collections.singletonList(certificate)).build();
            new ApkSigner.Builder(Collections.singletonList(signer))
                    .setInputApk(unsigned).setOutputApk(signed).setMinSdkVersion(26)
                    // v1's JAR/PKCS#7 signing fails on-device; v2 is supported since API 24.
                    .setV1SigningEnabled(false).setV2SigningEnabled(true)
                    .setV3SigningEnabled(true).build().sign();
            unsigned.delete();
            return new Result(packageName, signed);
        } finally {
            exe.delete();
        }
    }

    private static void checkExecutable(File file) throws IOException {
        try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(file, "r")) {
            if (input.length() < 64 || input.readUnsignedByte() != 'M'
                    || input.readUnsignedByte() != 'Z') throw new IOException("Not a Windows EXE");
            input.seek(0x3c);
            long pe = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));
            if (pe < 64 || pe > input.length() - 24) throw new IOException("Invalid PE header");
            input.seek(pe);
            if (input.readInt() != 0x50450000) throw new IOException("Invalid PE signature");
            int machine = Short.toUnsignedInt(Short.reverseBytes(input.readShort()));
            if (machine != 0x14c && machine != 0x8664) {
                throw new IOException("Unsupported Windows CPU architecture: " + machine);
            }
        }
    }

    private static byte[] readAsset(Context context, String path) throws IOException {
        try (InputStream input = context.getAssets().open(path)) { return readAll(input); }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(input, output);
        return output.toByteArray();
    }

    private static void copy(InputStream input, java.io.OutputStream output) throws IOException {
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }

    /** Rewrite the compiled Android binary XML string pool, retaining all other chunks. */
    private static byte[] rewriteManifest(byte[] xml, String packageName, String label)
            throws IOException {
        ByteBuffer old = ByteBuffer.wrap(xml).order(ByteOrder.LITTLE_ENDIAN);
        if (old.getShort(0) != 3 || old.getShort(8) != 1) {
            throw new IOException("Invalid program template manifest");
        }
        int poolStart = 8;
        int header = Short.toUnsignedInt(old.getShort(poolStart + 2));
        int poolSize = old.getInt(poolStart + 4);
        int count = old.getInt(poolStart + 8);
        int styles = old.getInt(poolStart + 12);
        boolean utf8 = (old.getInt(poolStart + 16) & 256) != 0;
        int stringsStart = old.getInt(poolStart + 20);
        if (styles != 0 || count < 1 || count > 4096 || poolSize > xml.length - poolStart) {
            throw new IOException("Unsupported program template string pool");
        }
        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        int[] offsets = new int[count];
        boolean foundPackage = false, foundLabel = false;
        for (int i = 0; i < count; i++) {
            int position = poolStart + stringsStart + old.getInt(poolStart + header + i * 4);
            int length;
            int byteLength;
            if (utf8) {
                int[] chars = length8(xml, position); position += chars[1];
                int[] bytes = length8(xml, position); position += bytes[1];
                length = chars[0]; byteLength = bytes[0];
            } else {
                int[] chars = length16(old, position); position += chars[1];
                length = chars[0]; byteLength = length * 2;
            }
            if (position < 0 || byteLength < 0 || position + byteLength >= xml.length) {
                throw new IOException("Invalid program template string");
            }
            String value = new String(xml, position, byteLength,
                    utf8 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE);
            if (value.equals(TEMPLATE_PACKAGE)) { value = packageName; foundPackage = true; }
            if (value.equals(TEMPLATE_LABEL)) { value = label; foundLabel = true; }
            byte[] bytes = value.getBytes(utf8 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE);
            offsets[i] = strings.size();
            if (utf8) {
                putLength8(strings, value.length());
                putLength8(strings, bytes.length);
            } else {
                putLength16(strings, value.length());
            }
            strings.write(bytes);
            strings.write(0);
            if (!utf8) strings.write(0);
        }
        if (!foundPackage || !foundLabel) throw new IOException("Program template is missing labels");
        while ((strings.size() & 3) != 0) strings.write(0);
        int size = stringsStart + strings.size();
        ByteArrayOutputStream output = new ByteArrayOutputStream(xml.length + size - poolSize);
        byte[] prefix = xml.clone();
        putInt(prefix, 4, xml.length + size - poolSize);
        putInt(prefix, poolStart + 4, size);
        for (int i = 0; i < count; i++) putInt(prefix, poolStart + header + i * 4, offsets[i]);
        output.write(prefix, 0, poolStart + stringsStart);
        strings.writeTo(output);
        output.write(xml, poolStart + poolSize, xml.length - poolStart - poolSize);
        return output.toByteArray();
    }

    private static int[] length8(byte[] data, int position) {
        int value = data[position] & 255;
        if ((value & 128) == 0) return new int[]{value, 1};
        return new int[]{((value & 127) << 8) | (data[position + 1] & 255), 2};
    }

    private static int[] length16(ByteBuffer data, int position) {
        int value = Short.toUnsignedInt(data.getShort(position));
        if ((value & 0x8000) == 0) return new int[]{value, 2};
        return new int[]{((value & 0x7fff) << 16)
                | Short.toUnsignedInt(data.getShort(position + 2)), 4};
    }

    private static void putLength8(ByteArrayOutputStream output, int length) {
        if (length < 128) output.write(length);
        else { output.write(128 | (length >> 8)); output.write(length & 255); }
    }

    private static void putLength16(ByteArrayOutputStream output, int length) {
        if (length >= 0x8000) {
            int first = (length >> 16) | 0x8000;
            output.write(first & 255); output.write(first >> 8);
        }
        output.write(length & 255); output.write((length >> 8) & 255);
    }

    private static void putInt(byte[] bytes, int offset, int value) {
        for (int i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> (i * 8));
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        long count;

        CountingOutputStream(FileOutputStream stream) { super(stream); }

        @Override public void write(int value) throws IOException {
            out.write(value);
            count++;
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
            count += length;
        }
    }
}
