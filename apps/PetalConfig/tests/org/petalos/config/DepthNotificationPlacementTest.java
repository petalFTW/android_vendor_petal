/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.config;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class DepthNotificationPlacementTest {
    @Test public void automaticKeepsExistingClockPlacement() {
        assertEquals(420f, DepthNotificationPlacement.resolveTop(420, 900, 1000, -1, 100), 0f);
    }

    @Test public void customPositionUsesScreenPercentage() {
        assertEquals(550f, DepthNotificationPlacement.resolveTop(420, 900, 1000, 55, 100), 0f);
    }

    @Test public void customPositionCanMoveAboveTheStockClockBarrier() {
        assertEquals(200f, DepthNotificationPlacement.resolveTop(500, 900, 1000, 20, 100), 0f);
    }

    @Test public void lowerPositionLeavesRoomForANotification() {
        assertEquals(650f, DepthNotificationPlacement.resolveTop(400, 750, 1000, 75, 100), 0f);
    }

    @Test public void mediaMovesRequestedPositionUpToFit() {
        assertEquals(500f, DepthNotificationPlacement.resolveTop(400, 800, 1000, 75, 300), 0f);
    }

    @Test public void smallBoundsNeverProduceANegativeStart() {
        assertEquals(0f, DepthNotificationPlacement.resolveTop(10, 50, 1000, 60, 100), 0f);
    }

    @Test public void storedPercentIsBoundedAndAutomaticSurvives() {
        assertEquals(-1, DepthNotificationPlacement.clampPercent(-1));
        assertEquals(20, DepthNotificationPlacement.clampPercent(-50));
        assertEquals(75, DepthNotificationPlacement.clampPercent(200));
        assertEquals(55, DepthNotificationPlacement.clampPercent(55));
    }

    @Test public void missingScreenSizeKeepsAutomaticPlacement() {
        assertEquals(420f, DepthNotificationPlacement.resolveTop(420, 900, 0, 55, 100), 0f);
        assertEquals(420f, DepthNotificationPlacement.resolveTop(420, 900, Float.NaN, 55, 100), 0f);
    }
}
