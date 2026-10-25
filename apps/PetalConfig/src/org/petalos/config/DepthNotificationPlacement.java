/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.config;

public final class DepthNotificationPlacement {
    public static final int AUTOMATIC = -1;
    public static final int MIN_PERCENT = 20;
    public static final int MAX_PERCENT = 75;
    public static final int DEFAULT_PERCENT = 43;

    private DepthNotificationPlacement() {}

    public static int clampPercent(int percent) {
        return percent == AUTOMATIC ? AUTOMATIC
                : Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, percent));
    }

    public static float resolveTop(float automaticTop, float bottom, float screenHeight,
            int percent, float minimumHeight) {
        if (percent == AUTOMATIC || !Float.isFinite(screenHeight) || screenHeight <= 0
                || !Float.isFinite(bottom) || !Float.isFinite(minimumHeight)) return automaticTop;
        float latestTop = Math.max(0f, bottom - Math.max(0f, minimumHeight));
        return Math.max(0f, Math.min(screenHeight * clampPercent(percent) / 100f, latestTop));
    }
}
