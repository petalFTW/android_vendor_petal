/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petal.backup;

import android.content.Context;
import android.app.Application;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.petal.backup.core.FileSnapshotEngine;
import org.petal.backup.core.OperationControl;
import org.petal.backup.core.OperationJournal;
import org.petal.backup.core.BackupNames;
import org.petal.backup.core.BackupResult;
import org.petal.backup.core.BackupResultStore;
import org.petal.backup.crypto.EncryptedArchive;
import org.petal.backup.crypto.RecoveryKeyEnvelope;
import org.petal.backup.storage.LocalSnapshotRepository;

final class BackupJobs {
    enum Kind { BACKUP, RESTORE, SELF_TEST }
    interface Listener { void changed(); }
    private static BackupJobs instance;
    private final Application context;
    private final Path workRoot;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private Job active;
    private String message;
    private String history = "";
    private List<BackupResult> results = List.of();
    private BackupResult result;
    private ProgressInfo progressInfo;
    private BackupResultStore resultStore;
    private boolean catalogueUnavailable;
    private List<OperationJournal.Record> plans = List.of();
    record ProgressInfo(Kind kind, OperationJournal.Phase phase, String text, int completed, int total) {}
    private boolean initialising = true;
    private volatile OperationJournal journal;

    static synchronized BackupJobs get(Context context) {
        if (instance == null) instance = new BackupJobs((Application) context.getApplicationContext());
        return instance;
    }

    private BackupJobs(Application context) {
        this.context = context;
        workRoot = context.getNoBackupFilesDir().toPath().resolve("backup-work");
        message = context.getString(R.string.opening_history);
        worker.execute(() -> {
            String initialMessage;
            String initialHistory = "";
            OperationJournal opened = null;
            try {
                Files.createDirectories(workRoot);
                OperationJournal candidate = new OperationJournal(context.getNoBackupFilesDir().toPath().resolve("operations"));
                boolean interrupted = !candidate.recoverInterrupted().isEmpty();
                var legacy = context.getSharedPreferences("job_status", Context.MODE_PRIVATE);
                interrupted |= legacy.getBoolean("running", false);
                legacy.edit().remove("running").apply();
                // No active worker yet, so these leftovers really are ours.
                cleanWork(workRoot);
                cleanWork(context.getCacheDir().toPath());
                initialHistory = history(candidate);
                resultStore = new BackupResultStore(context.getNoBackupFilesDir().toPath().resolve("results"));
                Set<String> known = new HashSet<>();
                List<BackupResult> saved = resultStore.list();
                for (BackupResult item : saved) known.add(item.id());
                for (OperationJournal.Record record : candidate.list()) {
                    if (!record.state.terminal() || known.contains(record.id)) continue;
                    resultStore.save(new BackupResult(record.id, record.kind, record.state, record.updatedAt,
                            context.getString(R.string.files_backup), record.archiveUri, record.outputUri,
                            record.destinationUri, record.kind == OperationJournal.Kind.BACKUP ? record.sources.size() : -1, -1,
                            record.state == OperationJournal.State.INTERRUPTED ? BackupResult.Problem.INTERRUPTED
                                    : record.state == OperationJournal.State.COMPLETE ? BackupResult.Problem.NONE : BackupResult.Problem.IO,
                            record.state != OperationJournal.State.COMPLETE && record.outputUri != null, ""));
                }
                opened = candidate;
                initialMessage = context.getString(interrupted ? R.string.interrupted : R.string.idle);
            } catch (IOException exception) {
                initialMessage = context.getString(R.string.journal_failed);
            }
            String initialStatus = initialMessage;
            String records = initialHistory;
            List<BackupResult> saved;
            try { saved = resultStore == null ? List.of() : resultStore.list(); }
            catch (IOException unavailable) { saved = List.of(); }
            List<BackupResult> initialResults = saved;
            List<OperationJournal.Record> initialPlans;
            try { initialPlans = opened == null ? List.of() : opened.list(); }
            catch (IOException unavailable) { initialPlans = List.of(); }
            List<OperationJournal.Record> recoveredPlans = initialPlans;
            journal = opened;
            main.post(() -> {
                initialising = false;
                message = initialStatus;
                history = records;
                results = initialResults;
                result = results.isEmpty() ? null : results.get(0);
                plans = recoveredPlans;
                notifyListeners();
            });
        });
    }

    void observe(Listener listener) { listeners.add(listener); }
    void remove(Listener listener) { listeners.remove(listener); }
    boolean busy() { return active != null || initialising; }
    boolean ready() { return journal != null && !initialising; }
    String message() { return message; }
    String history() { return history; }
    List<BackupResult> results() { return results; }
    BackupResult result() { return result; }
    ProgressInfo progressInfo() { return progressInfo; }
    boolean catalogueUnavailable() { return catalogueUnavailable; }
    boolean running() { return active != null; }
    void dismissResult() { result = null; }
    OperationJournal.Record plan(String id) {
        for (OperationJournal.Record record : plans) if (record.id.equals(id)) return record;
        return null;
    }

    String prepare(Kind kind, List<Uri> sources, Uri archive, Uri destination, char[] password) {
        if (active != null) throw new IllegalStateException("An operation is already running");
        if (!ready()) throw new IllegalStateException("Operation history is unavailable");
        if (kind == Kind.BACKUP && !RecoveryKeyEnvelope.isValidNumber(password)) {
            throw new IllegalArgumentException("A new backup needs exactly 16 digits");
        }
        Job job = new Job(kind, sources, archive, destination, password);
        try {
            List<String> sourceUris = new ArrayList<>();
            if (kind == Kind.BACKUP) for (Uri uri : sources) sourceUris.add(uri.toString());
            journal.create(job.id, OperationJournal.Kind.valueOf(kind.name()), sourceUris,
                    archive == null ? null : archive.toString(), destination == null ? null : destination.toString());
        } catch (IOException | RuntimeException exception) {
            Arrays.fill(job.password, '\0');
            throw new IllegalStateException("Could not save operation plan", exception);
        }
        active = job;
        result = null;
        progressInfo = new ProgressInfo(kind, OperationJournal.Phase.QUEUED, context.getString(R.string.queued), -1, -1);
        message = context.getString(R.string.queued);
        notifyListeners();
        return active.id;
    }

    void rejectStart(String id) {
        if (active != null && active.id.equals(id) && !active.started) {
            Arrays.fill(active.password, '\0');
            String reason = context.getString(R.string.interrupted);
            try { journal.finish(active.id, OperationJournal.State.INTERRUPTED); }
            catch (IOException exception) {
                journal = null;
                reason = context.getString(R.string.journal_failed);
            }
            active = null;
            message = reason;
            progressInfo = null;
            notifyListeners();
        }
    }

    void cancel() {
        if (active != null) {
            active.signal.cancel();
            Thread running = active.thread;
            if (running != null) running.interrupt();
            message = context.getString(R.string.cancel_pending);
            notifyListeners();
        }
    }

    void start(BackupService service, String id) {
        Job job = active;
        if (job == null) { service.finishJob(); return; }
        if (!job.id.equals(id) || job.started) return;
        job.started = true;
        service.showProgress(message);
        worker.execute(() -> {
            OperationJournal store = journal;
            job.thread = Thread.currentThread();
            String outcome;
            Path cache = null;
            boolean success = false;
            boolean cancelled = false;
            BackupResult.Problem problem = BackupResult.Problem.NONE;
            try {
                job.check();
                cache = Files.createTempDirectory(workRoot, "petal-job-");
                outcome = switch (job.kind) {
                    case BACKUP -> backup(job, cache, service);
                    case RESTORE -> restore(job, cache, service);
                    case SELF_TEST -> selfTest(job, cache, service);
                };
                success = true;
            } catch (EncryptedArchive.AuthenticationException exception) {
                problem = BackupResult.Problem.AUTHENTICATION;
                outcome = context.getString(R.string.authentication_failed);
            } catch (InterruptedIOException | OperationCanceledException exception) {
                cancelled = true;
                outcome = context.getString(R.string.cancelled);
            } catch (Exception exception) {
                cancelled = job.signal.isCanceled();
                problem = exception instanceof DocumentFiles.SpaceException ? BackupResult.Problem.SPACE
                        : exception instanceof SecurityException || exception instanceof java.io.FileNotFoundException
                        ? BackupResult.Problem.ACCESS : exception instanceof DocumentFiles.FormatException
                        ? BackupResult.Problem.FORMAT : BackupResult.Problem.IO;
                outcome = job.signal.isCanceled() ? context.getString(R.string.cancelled)
                        : context.getString(R.string.operation_failed,
                        exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
            } finally {
                Arrays.fill(job.password, '\0');
                job.thread = null;
                Thread.interrupted();
            }
            if (!success) {
                Uri created = job.kind == Kind.BACKUP ? job.destination : job.createdRestore;
                if (created != null && !DocumentFiles.delete(context, created)) {
                    job.cleanupFailed = true;
                    outcome += "\n" + context.getString(R.string.partial_cleanup);
                }
            }
            try { if (cache != null) deleteTree(cache); }
            catch (IOException exception) {
                job.cleanupFailed = true;
                outcome += "\n" + context.getString(R.string.private_cleanup);
            }
            try {
                store.finish(job.id, success ? OperationJournal.State.COMPLETE
                        : cancelled ? OperationJournal.State.CANCELLED : OperationJournal.State.FAILED);
            } catch (IOException exception) {
                journal = null;
                outcome += "\n" + context.getString(R.string.journal_failed);
            }
            String records;
            try { records = history(store); }
            catch (IOException exception) {
                journal = null;
                records = context.getString(R.string.journal_failed);
            }
            String finalHistory = records;
            String result = outcome;
            BackupResult completed = new BackupResult(job.id, OperationJournal.Kind.valueOf(job.kind.name()),
                    success ? OperationJournal.State.COMPLETE : cancelled ? OperationJournal.State.CANCELLED : OperationJournal.State.FAILED,
                    System.currentTimeMillis(), job.name == null ? context.getString(job.kind == Kind.BACKUP
                            ? R.string.file_backup : job.kind == Kind.RESTORE ? R.string.file_restore : R.string.diagnostic_test) : job.name,
                    job.archive == null ? null : job.archive.toString(),
                    job.kind == Kind.BACKUP ? job.destination.toString() : job.createdRestore == null ? null : job.createdRestore.toString(),
                    job.destination == null ? null : job.destination.toString(), job.fileCount, job.bytes,
                    problem, job.cleanupFailed, outcome);
            List<BackupResult> updated;
            try {
                resultStore.save(completed);
                updated = resultStore.list();
            } catch (IOException | RuntimeException unavailable) {
                updated = null;
            }
            List<BackupResult> finalResults = updated;
            List<OperationJournal.Record> refreshedPlans;
            try { refreshedPlans = store.list(); }
            catch (IOException unavailable) { refreshedPlans = List.of(); }
            List<OperationJournal.Record> finalPlans = refreshedPlans;
            main.post(() -> {
                if (active == job) {
                    active = null;
                    message = result;
                    history = finalHistory;
                    BackupJobs.this.result = completed;
                    progressInfo = null;
                    catalogueUnavailable = finalResults == null;
                    if (finalResults != null) results = finalResults;
                    plans = finalPlans;
                    notifyListeners();
                }
                service.finishJob(completed);
            });
        });
    }

    private String backup(Job job, Path cache, BackupService service) throws IOException {
        Path sources = Files.createDirectory(cache.resolve("sources"));
        LocalSnapshotRepository repository = repository(cache);
        Map<String, Path> selected = new HashMap<>();
        Set<String> names = new HashSet<>();
        long remaining = available(cache) / 2;
        job.fileCount = job.sources.size();
        job.name = DocumentFiles.name(context, job.destination, 1);
        for (int index = 0; index < job.sources.size(); index++) {
            job.check();
            progress(job, service, OperationJournal.Phase.READING,
                    context.getString(R.string.copy_source, index + 1, job.sources.size()), index, job.sources.size());
            Uri uri = job.sources.get(index);
            String original = DocumentFiles.name(context, uri, index + 1);
            String name = BackupNames.unique(original, names);
            Path file = sources.resolve(Integer.toString(index));
            try (InputStream input = DocumentFiles.open(context, uri, job.signal);
                    OutputStream output = Files.newOutputStream(file)) {
                remaining -= DocumentFiles.copy(input, output, remaining, job);
            }
            selected.put(name, file);
            progress(job, service, OperationJournal.Phase.READING,
                    context.getResources().getQuantityString(R.plurals.files_read, job.sources.size(), index + 1, job.sources.size()), index + 1, job.sources.size());
        }
        progress(job, service, OperationJournal.Phase.ENCRYPTING, context.getString(R.string.encrypting));
        String snapshot = new FileSnapshotEngine(repository).backup(selected, job.password, job);
        job.snapshotId = snapshot;
        progress(job, service, OperationJournal.Phase.SAVING, context.getString(R.string.saving));
        Path archive = cache.resolve("repository").resolve(snapshot + ".petalbak");
        job.bytes = Files.size(archive);
        DocumentFiles.exportVerified(context, archive, job.destination, job.signal, job,
                () -> progress(job, service, OperationJournal.Phase.SAVING, context.getString(R.string.checking_saved)));
        return context.getString(R.string.backup_done);
    }

    private String restore(Job job, Path cache, BackupService service) throws IOException {
        progress(job, service, OperationJournal.Phase.IMPORTING, context.getString(R.string.importing));
        LocalSnapshotRepository repository = repository(cache);
        Path incoming = cache.resolve("incoming");
        try (InputStream input = DocumentFiles.open(context, job.archive, job.signal);
                OutputStream output = Files.newOutputStream(incoming)) {
            DocumentFiles.copy(input, output, available(cache) / 3, job);
        }
        UUID id;
        try (InputStream input = Files.newInputStream(incoming)) {
            id = EncryptedArchive.inspectUnverifiedSnapshotId(input);
        } catch (IOException invalid) {
            throw new DocumentFiles.FormatException(invalid);
        }
        job.snapshotId = id.toString();
        long encryptedBytes = Files.size(incoming);
        job.bytes = encryptedBytes;
        job.name = DocumentFiles.name(context, job.archive, 1);
        Files.move(incoming, cache.resolve("repository").resolve(id + ".petalbak"));
        long expandedBudget = (available(cache) - encryptedBytes) / 2;
        if (expandedBudget <= 0) throw new DocumentFiles.SpaceException();
        progress(job, service, OperationJournal.Phase.VERIFYING, context.getString(R.string.decrypting));
        Path tree = cache.resolve("verified");
        new FileSnapshotEngine(repository).restore(id.toString(), tree, job.password,
                new FileSnapshotEngine.RestoreLimits(encryptedBytes, expandedBudget, 10_000), job);
        try (var entries = Files.walk(tree)) { job.fileCount = (int) entries.filter(Files::isRegularFile).count(); }
        progress(job, service, OperationJournal.Phase.EXPORTING, context.getString(R.string.exporting));
        job.check();
        String folder = "Petal Restore " + java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        job.createdRestore = DocumentFiles.createRestoreDirectory(context, job.destination, folder);
        journal.checkpoint(job.id, OperationJournal.Phase.EXPORTING, job.createdRestore.toString(), job.snapshotId);
        Map<Path, Uri> directories = new HashMap<>();
        directories.put(tree, job.createdRestore);
        int exported = 0;
        try (var entries = Files.walk(tree)) {
            var iterator = entries.iterator();
            while (iterator.hasNext()) {
                Path entry = iterator.next();
                if (entry.equals(tree)) continue;
                job.check();
                Uri parent = directories.get(entry.getParent());
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    directories.put(entry, DocumentFiles.create(context, parent,
                            DocumentsContract.Document.MIME_TYPE_DIR, name));
                } else {
                    Uri output = DocumentFiles.create(context, parent, "application/octet-stream", name);
                    DocumentFiles.exportVerified(context, entry, output, job.signal, job);
                    progress(job, service, OperationJournal.Phase.EXPORTING,
                            context.getResources().getQuantityString(R.plurals.files_restored, job.fileCount, ++exported, job.fileCount), exported, job.fileCount);
                }
            }
        }
        return context.getString(R.string.restore_done, folder);
    }

    private String selfTest(Job job, Path cache, BackupService service) throws IOException {
        progress(job, service, OperationJournal.Phase.ENCRYPTING, context.getString(R.string.encrypting));
        Path source = Files.write(cache.resolve("fixture.txt"), new byte[] {0, 1, 2, 3, 4, 5});
        FileSnapshotEngine engine = new FileSnapshotEngine(repository(cache));
        String snapshot = engine.backup(Map.of("nested/fixture.txt", source), job.password, job);
        progress(job, service, OperationJournal.Phase.VERIFYING, context.getString(R.string.decrypting));
        Path restored = cache.resolve("restored");
        engine.restore(snapshot, restored, job.password,
                new FileSnapshotEngine.RestoreLimits(1024 * 1024, 1024 * 1024, 10), job);
        if (!Arrays.equals(Files.readAllBytes(source), Files.readAllBytes(restored.resolve("nested/fixture.txt")))) {
            throw new IOException("On-device encryption test did not round-trip");
        }
        return context.getString(R.string.self_test_done);
    }

    private LocalSnapshotRepository repository(Path cache) throws IOException {
        return new LocalSnapshotRepository(cache.resolve("repository"), cache.resolve("private"), true);
    }

    private static long available(Path cache) throws IOException {
        return Math.max(0, Files.getFileStore(cache).getUsableSpace() - 32L * 1024 * 1024);
    }

    private void progress(Job job, BackupService service, OperationJournal.Phase phase, String text) throws IOException {
        progress(job, service, phase, text, -1, -1);
    }

    private void progress(Job job, BackupService service, OperationJournal.Phase phase, String text,
            int completed, int total) throws IOException {
        job.check();
        if (job.phase != phase) {
            journal.checkpoint(job.id, phase, null, job.snapshotId);
            job.phase = phase;
        }
        main.post(() -> {
            if (active == job && !job.signal.isCanceled()) {
                message = text;
                progressInfo = new ProgressInfo(job.kind, phase, text, completed, total);
                notifyListeners();
                service.showProgress(text);
            }
        });
    }

    private void notifyListeners() { for (Listener listener : listeners) listener.changed(); }

    private static String history(OperationJournal journal) throws IOException {
        StringBuilder text = new StringBuilder();
        int count = 0;
        for (OperationJournal.Record record : journal.list()) {
            if (count++ == 8) break;
            if (text.length() > 0) text.append('\n');
            text.append(record.kind).append(" · ").append(record.state).append(" · ").append(record.phase);
            if (record.state != OperationJournal.State.COMPLETE && record.outputUri != null) {
                text.append("\n").append("Output location: ").append(record.outputUri);
            }
        }
        return text.toString();
    }

    private static void cleanWork(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.filter(path -> path.getFileName().toString()
                    .startsWith("petal-job-")).toArray(Path[]::new)) deleteTree(entry);
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(path);
        }
    }

    private static final class Job implements OperationControl {
        final String id = UUID.randomUUID().toString();
        final Kind kind;
        final List<Uri> sources;
        final Uri archive;
        final Uri destination;
        final char[] password;
        final CancellationSignal signal = new CancellationSignal();
        boolean started;
        volatile Thread thread;
        Uri createdRestore;
        String snapshotId;
        String name;
        int fileCount;
        long bytes = -1;
        boolean cleanupFailed;
        OperationJournal.Phase phase = OperationJournal.Phase.QUEUED;

        Job(Kind kind, List<Uri> sources, Uri archive, Uri destination, char[] password) {
            this.kind = kind;
            this.sources = new ArrayList<>(sources);
            this.archive = archive;
            this.destination = destination;
            this.password = password.clone();
        }

        @Override public void check() throws InterruptedIOException {
            if (signal.isCanceled() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Operation cancelled");
            }
        }
    }
}
