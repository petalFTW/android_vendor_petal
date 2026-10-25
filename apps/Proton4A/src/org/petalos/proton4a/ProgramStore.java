/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package org.petalos.proton4a;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.UserManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Imports untrusted Windows binaries into credential-protected private storage. */
public final class ProgramStore {
    private static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;

    private ProgramStore() {}

    public static File programsDir(Context context) {
        return new File(context.createCredentialProtectedStorageContext().getFilesDir(),
                "proton4a/programs");
    }

    public static final class ImportedProgram {
        public final String id;
        public final String architecture;
        public final String sha256;

        ImportedProgram(String id, String architecture, String sha256) {
            this.id = id;
            this.architecture = architecture;
            this.sha256 = sha256;
        }
    }

    public static final class Program {
        public final String id;
        public final String name;
        public final String architecture;
        public final String sha256;

        Program(String id, String name, String architecture, String sha256) {
            this.id = id;
            this.name = name;
            this.architecture = architecture;
            this.sha256 = sha256;
        }
    }

    /** Programs imported so far, newest last. */
    public static List<Program> list(Context context) {
        List<Program> programs = new ArrayList<>();
        String raw = preferences(context).getString("registry", "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length(); index++) {
                JSONObject entry = array.getJSONObject(index);
                programs.add(new Program(entry.getString("id"), entry.optString("name", "Program"),
                        entry.optString("arch", "unknown"), entry.optString("sha256", "")));
            }
        } catch (JSONException ignored) {
            // A corrupt registry is treated as empty.
        }
        return programs;
    }

    private static android.content.SharedPreferences preferences(Context context) {
        return context.createCredentialProtectedStorageContext()
                .getSharedPreferences("proton4a", Context.MODE_PRIVATE);
    }

    private static void record(Context context, String id, String name, String architecture,
            String sha256) {
        List<Program> programs = list(context);
        JSONArray array = new JSONArray();
        for (Program program : programs) {
            array.put(toJson(program.id, program.name, program.architecture, program.sha256));
        }
        array.put(toJson(id, name, architecture, sha256));
        preferences(context).edit().putString("registry", array.toString()).apply();
    }

    private static JSONObject toJson(String id, String name, String architecture, String sha256) {
        JSONObject entry = new JSONObject();
        try {
            entry.put("id", id);
            entry.put("name", name);
            entry.put("arch", architecture);
            entry.put("sha256", sha256);
        } catch (JSONException ignored) {
            // values are strings, this cannot fail
        }
        return entry;
    }

    public static ImportedProgram importExecutable(Context context, Uri uri, String name)
            throws IOException {
        if (!"content".equals(uri.getScheme())) {
            throw new IOException("Select a document from the file picker");
        }
        try (InputStream source = context.getContentResolver().openInputStream(uri)) {
            if (source == null) throw new IOException("Cannot open selected document");
            return importStream(context, source, name, UUID.randomUUID().toString());
        }
    }

    /** Copy the executable bundled by a separately installed program APK. */
    public static ImportedProgram installPackaged(Context context, String packageName)
            throws IOException {
        String prefix = "org.petalos.proton4a.program.p";
        if (packageName == null || !packageName.matches(
                "org\\.petalos\\.proton4a\\.program\\.p[0-9a-f]{16}")) {
            throw new IOException("Invalid program package");
        }
        String id = packageName.substring(prefix.length());
        for (Program program : list(context)) {
            if (program.id.equals(id) && new File(programsDir(context), id + ".exe").isFile()) {
                return new ImportedProgram(id, program.architecture, program.sha256);
            }
        }
        try {
            Context bundled = context.createPackageContext(packageName, 0);
            String name = context.getPackageManager().getApplicationLabel(
                    context.getPackageManager().getApplicationInfo(packageName, 0)).toString();
            try (InputStream source = bundled.getAssets().open("program.exe")) {
                return importStream(context, source, name, id);
            }
        } catch (PackageManager.NameNotFoundException exception) {
            throw new IOException("Program APK is not installed", exception);
        }
    }

    private static ImportedProgram importStream(Context context, InputStream source,
            String name, String id) throws IOException {
        UserManager users = context.getSystemService(UserManager.class);
        if (users == null || !users.isUserUnlocked()) {
            throw new IOException("Unlock the device before importing a program");
        }
        Context storage = context.createCredentialProtectedStorageContext();
        File directory = new File(storage.getFilesDir(), "proton4a/programs");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create program storage");
        }
        File destination = new File(directory, id + ".exe");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
        boolean complete = false;
        try {
            try (FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int count;
                while ((count = source.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_BYTES) throw new IOException("Executable exceeds 2 GiB limit");
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                }
                output.getFD().sync();
            }
            String architecture = inspectPe(destination);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) {
                hex.append(Character.forDigit((value >>> 4) & 15, 16));
                hex.append(Character.forDigit(value & 15, 16));
            }
            complete = true;
            preferences(context).edit()
                    .putString("latest_id", id).putString("latest_name", name)
                    .putString("latest_arch", architecture)
                    .putString("latest_sha256", hex.toString()).apply();
            record(context, id, name, architecture, hex.toString());
            return new ImportedProgram(id, architecture, hex.toString());
        } finally {
            if (!complete) destination.delete();
        }
    }

    /** Check DOS + PE signatures and supported Windows CPU machine values. */
    public static String inspectPe(File executable) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(executable, "r")) {
            if (input.length() < 64 || input.readUnsignedByte() != 'M'
                    || input.readUnsignedByte() != 'Z') {
                throw new IOException("Not a Windows PE executable");
            }
            input.seek(0x3c);
            long offset = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));
            if (offset < 64 || offset > input.length() - 24) {
                throw new IOException("Invalid PE header offset");
            }
            input.seek(offset);
            if (input.readUnsignedByte() != 'P' || input.readUnsignedByte() != 'E'
                    || input.readUnsignedByte() != 0 || input.readUnsignedByte() != 0) {
                throw new IOException("Invalid PE signature");
            }
            int machine = Short.toUnsignedInt(Short.reverseBytes(input.readShort()));
            if (machine == 0x14c) return "x86";
            if (machine == 0x8664) return "x86-64";
            throw new IOException("Unsupported Windows CPU architecture: " + machine);
        }
    }
}
