/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.hub.garden;

import static org.junit.Assert.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Random;
import org.junit.Test;

public class PocketGardenTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 21);

    @Test public void firstSeedGrowsOverFourDailyVisits() {
        PocketGarden garden = new PocketGarden(new Random(42));
        assertEquals(PocketGarden.Action.PLANTED, garden.tend(0, DAY));
        assertEquals(0, garden.get(0).variety);
        assertFalse(garden.get(0).golden);
        assertFalse(garden.discovered(0));
        assertEquals(PocketGarden.Action.WATERED, garden.tend(0, DAY.plusDays(1)));
        assertEquals(1, garden.get(0).water);
        assertEquals(PocketGarden.Action.WATERED, garden.tend(0, DAY.plusDays(2)));
        assertEquals(PocketGarden.Action.BLOOMED, garden.tend(0, DAY.plusDays(3)));
        assertEquals(1, garden.bloomCount());
        assertTrue(garden.discovered(0));
    }

    @Test public void anotherPotCannotBeUsedOnTheSameDay() {
        PocketGarden garden = new PocketGarden(new Random(42));
        garden.tend(0, DAY);
        assertEquals(PocketGarden.Action.ALREADY_TENDED, garden.tend(1, DAY));
        assertNull(garden.get(1));
        assertEquals(1, garden.plantedCount());
    }

    @Test public void repeatedClickCannotWaterOrRerollTheSeed() {
        PocketGarden garden = new PocketGarden(new Random(42));
        garden.tend(0, DAY);
        PocketGarden.Plant original = garden.get(0);
        assertEquals(PocketGarden.Action.ALREADY_TENDED, garden.tend(0, DAY));
        assertSame(original, garden.get(0));
        assertEquals(0, garden.get(0).water);
    }

    @Test public void dailyLimitSurvivesSaveAndReopen() {
        PocketGarden garden = new PocketGarden(new Random(42));
        garden.tend(4, DAY);
        PocketGarden restored = PocketGarden.decode(garden.encode(), new Random(82));
        assertFalse(restored.canTend(DAY));
        assertEquals(DAY, restored.lastTendedDate());
        assertEquals(4, restored.lastTendedPlot());
        assertEquals(PocketGarden.Action.ALREADY_TENDED, restored.tend(5, DAY));
        assertTrue(restored.canTend(DAY.plusDays(1)));
    }

    @Test public void returningOnAnEarlierDateDoesNotGrantAnotherVisit() {
        PocketGarden garden = new PocketGarden(new Random(42));
        garden.tend(0, DAY);
        assertFalse(garden.canTend(DAY.minusDays(1)));
        assertEquals(PocketGarden.Action.ALREADY_TENDED, garden.tend(1, DAY.minusDays(1)));
    }

    @Test public void localMidnightUnlocksWithoutWaitingTwentyFourHours() {
        ZonedDateTime before = ZonedDateTime.of(2026, 3, 8, 23, 59, 0, 0, ZoneId.of("America/New_York"));
        ZonedDateTime after = before.plusMinutes(2);
        PocketGarden garden = new PocketGarden(new Random(42));
        garden.tend(0, before.toLocalDate());
        assertTrue(garden.canTend(after.toLocalDate()));
        assertEquals(PocketGarden.Action.WATERED, garden.tend(0, after.toLocalDate()));
    }

    @Test public void legacySaveKeepsExistingPlantsAndDiscoveries() {
        PocketGarden garden = PocketGarden.decode(legacy("1,2,0", "3,3,1"), new Random(42));
        assertEquals(2, garden.plantedCount());
        assertEquals(2, garden.get(0).water);
        assertTrue(garden.discovered(3));
        assertTrue(garden.discoveredGolden(3));
        assertTrue(garden.canTend(DAY));
        assertEquals(PocketGarden.Action.BLOOMED, garden.tend(0, DAY));
        assertTrue(garden.encode().startsWith("2|"));
        assertFalse(PocketGarden.decode(garden.encode(), new Random()).canTend(DAY));
    }

    @Test public void roundTripKeepsAllGrowthStagesAndTheDailyVisit() {
        String[] plants = new String[PocketGarden.PLOTS];
        for (int i = 0; i < plants.length; i++) plants[i] = i % 6 + "," + i % 4 + "," + (i == 3 ? 1 : 0);
        PocketGarden garden = PocketGarden.decode(legacy(plants), new Random(42));
        garden.tend(0, DAY);
        String saved = garden.encode();
        PocketGarden restored = PocketGarden.decode(saved, new Random());
        assertEquals(saved, restored.encode());
        assertEquals(12, restored.plantedCount());
        assertEquals(3, restored.bloomCount());
        assertEquals(garden.discoveryCount(), restored.discoveryCount());
    }

    @Test public void pickingABloomKeepsItsDiscoveryAndUsesTheDailyVisit() {
        PocketGarden garden = PocketGarden.decode(legacy("2,3,1"), new Random(42));
        assertEquals(PocketGarden.Action.PICKED, garden.tend(0, DAY));
        assertNull(garden.get(0));
        assertTrue(garden.discovered(2));
        assertTrue(garden.discoveredGolden(2));
        assertEquals(PocketGarden.Action.ALREADY_TENDED, garden.tend(0, DAY));
        PocketGarden restored = PocketGarden.decode(garden.encode(), new Random());
        assertFalse(restored.canTend(DAY));
        assertEquals(PocketGarden.Action.PLANTED, restored.tend(0, DAY.plusDays(1)));
    }

    @Test public void goldenBloomIsOnlyDiscoveredAtFullGrowth() {
        Random golden = new Random() {
            @Override public int nextInt(int bound) { return 0; }
        };
        PocketGarden garden = new PocketGarden(golden);
        garden.tend(0, DAY);
        garden.tend(1, DAY.plusDays(1));
        assertTrue(garden.get(1).golden);
        assertFalse(garden.discoveredGolden(0));
        garden.tend(1, DAY.plusDays(2));
        garden.tend(1, DAY.plusDays(3));
        garden.tend(1, DAY.plusDays(4));
        assertTrue(garden.discoveredGolden(0));
    }

    @Test public void malformedSaveDoesNotInventPlants() {
        for (String data : new String[] {null, "", "junk", "3|0|0", "2|0|0", "1|0|0"}) {
            assertEquals(0, PocketGarden.decode(data, new Random()).plantedCount());
        }
    }

    @Test public void corruptPlotDoesNotLoseOtherPlantsOrUnlockAnotherVisit() {
        PocketGarden garden = PocketGarden.decode(legacy("0,1,0", "1,0,0"), new Random(42));
        garden.tend(0, DAY);
        String[] saved = garden.encode().split("\\|", -1);
        saved[6] = "98,-1,2";
        PocketGarden restored = PocketGarden.decode(String.join("|", saved), new Random());
        assertEquals(1, restored.plantedCount());
        assertEquals(2, restored.get(0).water);
        assertNull(restored.get(1));
        assertFalse(restored.canTend(DAY));
    }

    @Test public void unknownPotDoesNotSpendTheVisit() {
        PocketGarden garden = new PocketGarden(new Random(42));
        try {
            garden.tend(PocketGarden.PLOTS, DAY);
            fail("Unknown pot accepted");
        } catch (IllegalArgumentException expected) { }
        assertTrue(garden.canTend(DAY));
    }

    private static String legacy(String... plants) {
        StringBuilder data = new StringBuilder("1|0|0");
        for (int i = 0; i < PocketGarden.PLOTS; i++) data.append('|').append(i < plants.length ? plants[i] : "-");
        return data.toString();
    }
}
