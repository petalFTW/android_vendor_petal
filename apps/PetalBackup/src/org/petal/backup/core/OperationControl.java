/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petal.backup.core;

import java.io.InterruptedIOException;

@FunctionalInterface
public interface OperationControl {
    void check() throws InterruptedIOException;

    OperationControl THREAD_INTERRUPTION = () -> {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Backup operation cancelled");
        }
    };
}
