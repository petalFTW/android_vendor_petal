/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.petalos.proton4a;

import android.content.Context;
import android.net.Uri;
import android.os.UserManager;
import android.system.Os;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.zip.GZIPInputStream;

/**
 * Installs and describes the Proton4A runtime payload: the Box64 translator
 * plus the upstream Proton redist, extracted into credential-protected
 * app storage.
 */
public final class RuntimeManager {
    public static final String STAGED_PAYLOAD =
            "/product/proton4a/proton4a-runtime.tar.gz";

    private static final String MANIFEST = "manifest.json";

    private RuntimeManager() {}

    public static final class Status {
        public final boolean installed;
        public final String protonVersion;
        public final String box64Revision;

        Status(boolean installed, String protonVersion, String box64Revision) {
            this.installed = installed;
            this.protonVersion = protonVersion;
            this.box64Revision = box64Revision;
        }
    }

    public static File runtimeDir(Context context) {
        return new File(context.createCredentialProtectedStorageContext().getFilesDir(),
                "proton4a/runtime");
    }

    public static Status status(Context context) {
        File manifest = new File(runtimeDir(context), MANIFEST);
        if (!manifest.isFile()) return new Status(false, null, null);
        try {
            JSONObject json = new JSONObject(readText(manifest));
            return new Status(true,
                    json.getJSONObject("proton").optString("version", "unknown"),
                    json.getJSONObject("box64").optString("revision", "unknown"));
        } catch (Exception exception) {
            return new Status(false, null, null);
        }
    }

    /** Installs the runtime from a staged payload, replacing any previous copy. */
    public static Status installFromStaged(Context context) throws IOException {
        File staged = new File(STAGED_PAYLOAD);
        if (!staged.isFile()) {
            throw new IOException("No runtime payload staged at " + STAGED_PAYLOAD);
        }
        try (InputStream input = new FileInputStream(staged)) {
            return install(context, input);
        }
    }

    public static Status install(Context context, Uri uri) throws IOException {
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("Cannot open selected payload");
        try {
            return install(context, input);
        } finally {
            input.close();
        }
    }

    public static Status install(Context context, InputStream raw) throws IOException {
        UserManager users = context.getSystemService(UserManager.class);
        if (users == null || !users.isUserUnlocked()) {
            throw new IOException("Unlock the device before installing the runtime");
        }
        File target = runtimeDir(context);
        File staging = new File(target.getParentFile(), "runtime.staging");
        deleteRecursively(staging);
        if (!staging.mkdirs()) throw new IOException("Cannot create runtime staging directory");

        try (InputStream input = new GZIPInputStream(raw)) {
            TarArchive.read(input, entry -> extract(staging, entry));
        }

        File manifest = new File(staging, MANIFEST);
        if (!manifest.isFile()) {
            deleteRecursively(staging);
            throw new IOException("Runtime payload has no manifest");
        }
        String expected = expectedBox64Hash(manifest);
        File box64 = new File(staging, "box64");
        if (!box64.isFile() || !expected.equalsIgnoreCase(sha256(box64))) {
            deleteRecursively(staging);
            throw new IOException("Box64 checksum does not match the manifest");
        }

        deleteRecursively(target);
        if (!staging.renameTo(target)) {
            deleteRecursively(staging);
            throw new IOException("Cannot activate the runtime");
        }
        return status(context);
    }

    private static String expectedBox64Hash(File manifest) throws IOException {
        try {
            return new JSONObject(readText(manifest))
                    .getJSONObject("box64").getString("sha256");
        } catch (Exception exception) {
            throw new IOException("Runtime manifest is malformed", exception);
        }
    }

    private static boolean extract(File root, TarArchive.TarEntry entry) throws IOException {
        String name = entry.name.replace('\\', '/');
        if ("runtime".equals(name) || "runtime/".equals(name)) return false;
        if (!name.startsWith("runtime/")) {
            throw new IOException("Unexpected runtime entry: " + name);
        }
        name = name.substring("runtime/".length());
        if (name.startsWith("/") || name.contains("../") || name.equals("..")) {
            throw new IOException("Unsafe runtime entry: " + name);
        }
        File destination = new File(root, name);
        String rootPath = root.getCanonicalPath();
        if (!destination.getCanonicalPath().startsWith(rootPath + File.separator)
                && !destination.getCanonicalPath().equals(rootPath)) {
            throw new IOException("Runtime entry escapes its directory: " + name);
        }

        if (entry.directory) {
            if (!destination.isDirectory() && !destination.mkdirs() && !destination.isDirectory()) {
                throw new IOException("Cannot create " + name);
            }
            return false;
        }
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create " + parent);
        }
        if (entry.symlink) {
            if (entry.linkName == null || entry.linkName.startsWith("/")
                    || !destination.toPath().getParent().resolve(entry.linkName)
                            .normalize().startsWith(root.toPath().toAbsolutePath().normalize())) {
                throw new IOException("Unsafe symlink target: " + entry.linkName);
            }
            destination.delete();
            try {
                Os.symlink(entry.linkName, destination.getAbsolutePath());
            } catch (android.system.ErrnoException exception) {
                throw new IOException("Cannot create symlink " + name, exception);
            }
            return false;
        }
        if (entry.hardlink) {
            if (entry.linkName == null || !entry.linkName.startsWith("runtime/")) {
                throw new IOException("Unsafe hardlink target: " + entry.linkName);
            }
            File source = new File(root, entry.linkName.substring("runtime/".length()));
            if (!source.toPath().normalize().startsWith(root.toPath().toAbsolutePath().normalize())) {
                throw new IOException("Unsafe hardlink target: " + entry.linkName);
            }
            try {
                Path link = destination.toPath();
                Files.deleteIfExists(link);
                Files.createLink(link, source.toPath());
            } catch (IOException exception) {
                // Fall back to a copy when hardlinks are unavailable.
                Files.copy(source.toPath(), destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            return false;
        }
        TarArchive.copy(entry.content, destination);
        try {
            Os.chmod(destination.getAbsolutePath(), entry.mode & 0777);
        } catch (android.system.ErrnoException exception) {
            throw new IOException("Cannot set permissions on " + name, exception);
        }
        // Content already consumed; true lets TarArchive drain the empty entry
        // and skip only its padding instead of the whole payload again.
        return true;
    }

    private static String readText(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int offset = 0;
            while (offset < buffer.length) {
                int count = input.read(buffer, offset, buffer.length - offset);
                if (count == -1) break;
                offset += count;
            }
            return new String(buffer, 0, offset, StandardCharsets.UTF_8);
        }
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(file.toPath())) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
