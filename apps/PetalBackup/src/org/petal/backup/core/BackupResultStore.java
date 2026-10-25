/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petal.backup.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

public final class BackupResultStore {
    private final Path directory;

    public BackupResultStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        this.directory = directory;
    }

    public synchronized void save(BackupResult result) throws IOException {
        Properties p = new Properties();
        p.setProperty("version", "1");
        p.setProperty("id", result.id());
        p.setProperty("kind", result.kind().name());
        p.setProperty("state", result.state().name());
        p.setProperty("time", Long.toString(result.time()));
        p.setProperty("name", result.name());
        put(p, "archive", result.archiveUri());
        put(p, "output", result.outputUri());
        put(p, "location", result.locationUri());
        p.setProperty("files", Integer.toString(result.files()));
        p.setProperty("bytes", Long.toString(result.bytes()));
        p.setProperty("problem", result.problem().name());
        p.setProperty("cleanup", Boolean.toString(result.cleanupFailed()));
        p.setProperty("detail", result.detail());
        Path target = directory.resolve(result.id() + ".properties");
        Path temporary = Files.createTempFile(directory, "result-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) { p.store(output, null); }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    public synchronized List<BackupResult> list() throws IOException {
        List<BackupResult> results = new ArrayList<>();
        try (var paths = Files.newDirectoryStream(directory, "*.properties")) {
            for (Path path : paths) {
                try {
                    if (Files.isSymbolicLink(path) || Files.size(path) > 128 * 1024) continue;
                    Properties p = new Properties();
                    try (InputStream input = Files.newInputStream(path)) { p.load(input); }
                    if (!"1".equals(p.getProperty("version"))) continue;
                    String id = p.getProperty("id");
                    UUID.fromString(id);
                    if (!path.getFileName().toString().equals(id + ".properties")) continue;
                    results.add(new BackupResult(id, OperationJournal.Kind.valueOf(p.getProperty("kind")),
                            OperationJournal.State.valueOf(p.getProperty("state")), Long.parseLong(p.getProperty("time")),
                            p.getProperty("name"), p.getProperty("archive"), p.getProperty("output"), p.getProperty("location"),
                            Integer.parseInt(p.getProperty("files")), Long.parseLong(p.getProperty("bytes")),
                            BackupResult.Problem.valueOf(p.getProperty("problem")),
                            Boolean.parseBoolean(p.getProperty("cleanup")), p.getProperty("detail", "")));
                } catch (IOException | IllegalArgumentException | NullPointerException invalid) {
                    // a broken history card mustn't hide the good ones
                }
            }
        }
        results.sort(Comparator.comparingLong(BackupResult::time).reversed().thenComparing(BackupResult::id));
        return List.copyOf(results);
    }

    private static void put(Properties p, String key, String value) {
        if (value != null) p.setProperty(key, value);
    }
}
