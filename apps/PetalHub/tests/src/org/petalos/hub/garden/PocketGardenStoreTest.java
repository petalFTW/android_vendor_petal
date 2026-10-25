/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.hub.garden;

import static org.junit.Assert.*;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class PocketGardenStoreTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 21);

    private static final class Memory implements PocketGardenStore.Storage {
        String value;
        boolean failNext;
        @Override public String read() { return value; }
        @Override public boolean write(String value) {
            this.value = value;
            if (failNext) { failNext = false; return false; }
            return true;
        }
    }

    @Test public void reopeningTheStoreKeepsTodayLocked() {
        Memory memory = new Memory();
        assertTrue(new PocketGardenStore(memory).tend(0, DAY).saved());
        PocketGardenStore reopened = new PocketGardenStore(memory);
        assertFalse(reopened.read().canTend(DAY));
        assertEquals(PocketGarden.Action.ALREADY_TENDED, reopened.tend(1, DAY).action());
        assertEquals(1, reopened.read().plantedCount());
    }

    @Test public void failedSaveDoesNotSpendTheVisitOrLeaveAnInMemoryChange() {
        Memory memory = new Memory();
        memory.failNext = true;
        PocketGardenStore store = new PocketGardenStore(memory);
        assertFalse(store.tend(0, DAY).saved());
        assertTrue(store.read().canTend(DAY));
        assertEquals(0, store.read().plantedCount());
        assertEquals(PocketGarden.Action.PLANTED, store.tend(0, DAY).action());
    }

    @Test public void concurrentScreensCannotSpendTwoVisits() throws Exception {
        Memory memory = new Memory();
        PocketGardenStore first = new PocketGardenStore(memory);
        PocketGardenStore second = new PocketGardenStore(memory);
        CountDownLatch start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var one = workers.submit(() -> { start.await(); return first.tend(0, DAY); });
            var two = workers.submit(() -> { start.await(); return second.tend(1, DAY); });
            start.countDown();
            var firstResult = one.get(3, TimeUnit.SECONDS);
            var secondResult = two.get(3, TimeUnit.SECONDS);
            assertTrue(firstResult.action() == PocketGarden.Action.ALREADY_TENDED
                    || secondResult.action() == PocketGarden.Action.ALREADY_TENDED);
            assertEquals(1, first.read().plantedCount());
        } finally { workers.shutdownNow(); }
    }

    @Test public void anotherCalendarDayAllowsAnotherSavedAction() {
        Memory memory = new Memory();
        PocketGardenStore store = new PocketGardenStore(memory);
        store.tend(0, DAY);
        assertEquals(PocketGarden.Action.WATERED, store.tend(0, DAY.plusDays(1)).action());
        assertEquals(1, store.read().get(0).water);
        assertFalse(store.read().canTend(DAY.plusDays(1)));
    }
}
