/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.hub.garden;

import java.time.LocalDate;
import java.util.Random;

public final class PocketGarden {
    public static final int PLOTS = 12;
    public static final int VARIETIES = 6;
    public static final int BLOOM = 3;
    public enum Action { PLANTED, WATERED, BLOOMED, PICKED, ALREADY_TENDED }

    public static final class Plant {
        public final int variety;
        public final int water;
        public final boolean golden;

        private Plant(int variety, int water, boolean golden) {
            this.variety = variety;
            this.water = water;
            this.golden = golden;
        }
    }

    private final Plant[] plants = new Plant[PLOTS];
    private final Random random;
    private int discoveries;
    private int goldenDiscoveries;
    private long lastTendedDay = Long.MIN_VALUE;
    private int lastTendedPlot = -1;

    public PocketGarden(Random random) {
        this.random = random;
    }

    public Plant get(int plot) {
        return plants[plot];
    }

    private boolean plant(int plot) {
        if (plants[plot] != null) return false;
        boolean first = discoveries == 0 && plantedCount() == 0;
        plants[plot] = new Plant(first ? 0 : random.nextInt(VARIETIES), 0,
                !first && random.nextInt(32) == 0);
        return true;
    }

    private boolean water(int plot) {
        Plant plant = plants[plot];
        if (plant == null || plant.water == BLOOM) return false;
        Plant grown = new Plant(plant.variety, plant.water + 1, plant.golden);
        plants[plot] = grown;
        if (grown.water == BLOOM) {
            discoveries |= 1 << grown.variety;
            if (grown.golden) goldenDiscoveries |= 1 << grown.variety;
        }
        return true;
    }

    public int plantedCount() {
        int count = 0;
        for (Plant plant : plants) if (plant != null) count++;
        return count;
    }

    public int bloomCount() {
        int count = 0;
        for (Plant plant : plants) if (plant != null && plant.water == BLOOM) count++;
        return count;
    }

    public boolean discovered(int variety) {
        return (discoveries & (1 << variety)) != 0;
    }

    public boolean discoveredGolden(int variety) {
        return (goldenDiscoveries & (1 << variety)) != 0;
    }

    public int discoveryCount() {
        return Integer.bitCount(discoveries);
    }

    public boolean canTend(LocalDate day) {
        return day.toEpochDay() > lastTendedDay;
    }

    public LocalDate lastTendedDate() {
        return lastTendedDay == Long.MIN_VALUE ? null : LocalDate.ofEpochDay(lastTendedDay);
    }

    public int lastTendedPlot() {
        return lastTendedPlot;
    }

    public Action tend(int plot, LocalDate day) {
        if (plot < 0 || plot >= PLOTS) throw new IllegalArgumentException("Unknown pot");
        if (!canTend(day)) return Action.ALREADY_TENDED;
        Action action;
        Plant plant = plants[plot];
        if (plant == null) {
            plant(plot);
            action = Action.PLANTED;
        } else if (plant.water < BLOOM) {
            water(plot);
            action = plants[plot].water == BLOOM ? Action.BLOOMED : Action.WATERED;
        } else {
            plants[plot] = null;
            action = Action.PICKED;
        }
        lastTendedDay = day.toEpochDay();
        lastTendedPlot = plot;
        return action;
    }

    public String encode() {
        StringBuilder data = new StringBuilder("2|")
                .append(discoveries).append('|').append(goldenDiscoveries)
                .append('|').append(lastTendedDay).append('|').append(lastTendedPlot);
        for (Plant plant : plants) {
            data.append('|');
            if (plant == null) {
                data.append('-');
            } else {
                data.append(plant.variety).append(',').append(plant.water).append(',')
                        .append(plant.golden ? 1 : 0);
            }
        }
        return data.toString();
    }

    public static PocketGarden decode(String data, Random random) {
        PocketGarden garden = new PocketGarden(random);
        if (data == null || data.length() > 1024) return garden;
        String[] parts = data.split("\\|", -1);
        boolean legacy = parts.length == PLOTS + 3 && "1".equals(parts[0]);
        if (!legacy && (parts.length != PLOTS + 5 || !"2".equals(parts[0]))) return garden;
        try {
            int mask = (1 << VARIETIES) - 1;
            garden.discoveries = Integer.parseInt(parts[1]) & mask;
            garden.goldenDiscoveries = Integer.parseInt(parts[2]) & garden.discoveries;
        } catch (NumberFormatException ignored) {
            // salvage the plants anyway
        }
        if (!legacy) {
            try {
                long day = Long.parseLong(parts[3]);
                if (day == Long.MIN_VALUE || (day >= LocalDate.MIN.toEpochDay()
                        && day < LocalDate.MAX.toEpochDay())) garden.lastTendedDay = day;
                int plot = Integer.parseInt(parts[4]);
                if (plot >= 0 && plot < PLOTS) garden.lastTendedPlot = plot;
            } catch (NumberFormatException ignored) {
                // keep the plants if an old save got mangled
            }
        }
        int firstPlant = legacy ? 3 : 5;
        for (int i = 0; i < PLOTS; i++) {
            String[] plant = parts[i + firstPlant].split(",", -1);
            if (plant.length != 3) continue;
            try {
                int variety = Integer.parseInt(plant[0]);
                int water = Integer.parseInt(plant[1]);
                int golden = Integer.parseInt(plant[2]);
                if (variety < 0 || variety >= VARIETIES || water < 0 || water > BLOOM
                        || (golden != 0 && golden != 1)) continue;
                garden.plants[i] = new Plant(variety, water, golden == 1);
                if (water == BLOOM) {
                    garden.discoveries |= 1 << variety;
                    if (golden == 1) garden.goldenDiscoveries |= 1 << variety;
                }
            } catch (NumberFormatException ignored) {
                // one bad seed needn't eat the whole garden
            }
        }
        return garden;
    }
}
