/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.power;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class PolicyUpdateQueueTest {
    @Test public void queuedRequestsShareOnePassAndAllComplete() {
        List<Runnable> tasks = new ArrayList<>();
        List<String> events = new ArrayList<>();
        PolicyUpdateQueue queue = new PolicyUpdateQueue(tasks::add, failure -> {
            throw new AssertionError(failure);
        });
        queue.submit(() -> events.add("old"), () -> events.add("first completed"));
        queue.submit(() -> events.add("latest"), () -> events.add("second completed"));
        assertEquals(1, tasks.size());
        tasks.remove(0).run();
        assertEquals(List.of("latest", "first completed", "second completed"), events);
    }

    @Test public void requestDuringApplyGetsAnotherPass() {
        List<Runnable> tasks = new ArrayList<>();
        List<String> events = new ArrayList<>();
        PolicyUpdateQueue queue = new PolicyUpdateQueue(tasks::add, failure -> {
            throw new AssertionError(failure);
        });
        queue.submit(() -> {
            events.add("apply");
            queue.submit(() -> events.add("reconcile"), () -> events.add("new completed"));
        }, () -> events.add("old completed"));
        tasks.remove(0).run();
        assertEquals(List.of("apply", "old completed", "reconcile", "new completed"), events);
        assertTrue(tasks.isEmpty());
    }

    @Test public void failedPassFinishesReceiversAndAcceptsLaterWork() {
        List<Runnable> tasks = new ArrayList<>();
        List<RuntimeException> failures = new ArrayList<>();
        List<String> events = new ArrayList<>();
        PolicyUpdateQueue queue = new PolicyUpdateQueue(tasks::add, failures::add);
        queue.submit(() -> { throw new IllegalStateException("temporary"); },
                () -> events.add("completed"));
        tasks.remove(0).run();
        assertEquals(1, failures.size());
        queue.submit(() -> events.add("retry"), null);
        tasks.remove(0).run();
        assertEquals(List.of("completed", "retry"), events);
    }

    @Test public void failedReceiverCompletionDoesNotStrandOtherReceivers() {
        List<Runnable> tasks = new ArrayList<>();
        List<RuntimeException> failures = new ArrayList<>();
        List<String> events = new ArrayList<>();
        PolicyUpdateQueue queue = new PolicyUpdateQueue(tasks::add, failures::add);
        queue.submit(() -> {}, () -> { throw new IllegalStateException("receiver gone"); });
        queue.submit(() -> {}, () -> events.add("completed"));
        tasks.remove(0).run();
        assertEquals(1, failures.size());
        assertEquals(List.of("completed"), events);
    }
}
