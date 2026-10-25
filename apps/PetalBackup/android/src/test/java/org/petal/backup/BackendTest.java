/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petal.backup;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import org.petal.backup.tests.RepositoryTest;

public final class BackendTest {
    @Test public void repositoryContracts() throws Exception {
        Path root = Files.createTempDirectory("petal-backup-junit-");
        try {
            RepositoryTest.main(new String[] {root.toString()});
        } finally {
            Files.delete(root);
        }
    }
}
