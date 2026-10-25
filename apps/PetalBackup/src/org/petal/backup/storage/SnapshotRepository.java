/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petal.backup.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.petal.backup.core.OperationControl;
import org.petal.backup.crypto.EncryptedArchive;

/** Encrypted snapshots; implementations own provider-specific staging and commit. */
public interface SnapshotRepository {
    String create(char[] password, OperationControl control,
            EncryptedArchive.PayloadWriter payload) throws IOException;

    List<String> list(OperationControl control) throws IOException;

    @FunctionalInterface
    interface VerifiedPayloadReader {
        void read(Path privatePlaintext) throws IOException;
    }

    // Never invoke the reader until snapshot identity, all tags and END/EOF verify.
    void readVerified(String id, char[] password, long maxPlaintextBytes,
            OperationControl control, VerifiedPayloadReader reader) throws IOException;

    void delete(String id, OperationControl control) throws IOException;
}
