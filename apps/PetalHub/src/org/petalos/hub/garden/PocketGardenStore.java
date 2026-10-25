/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.hub.garden;

import java.time.LocalDate;
import java.util.Random;

final class PocketGardenStore {
    interface Storage {
        String read();
        boolean write(String value);
    }
    private static final Object LOCK = new Object();
    private final Storage storage;

    record Change(PocketGarden garden, PocketGarden.Action action, boolean saved) {}

    PocketGardenStore(Storage storage) {
        this.storage = storage;
    }

    PocketGarden read() {
        synchronized (LOCK) {
            return PocketGarden.decode(storage.read(), new Random());
        }
    }

    Change tend(int plot, LocalDate day) {
        synchronized (LOCK) {
            String before = storage.read();
            PocketGarden garden = PocketGarden.decode(before, new Random());
            PocketGarden.Action action = garden.tend(plot, day);
            if (action == PocketGarden.Action.ALREADY_TENDED) return new Change(garden, action, true);
            if (storage.write(garden.encode())) return new Change(garden, action, true);
            // restore the in-memory preference too; a failed commit can still change it
            storage.write(before);
            return new Change(PocketGarden.decode(before, new Random()), action, false);
        }
    }
}
