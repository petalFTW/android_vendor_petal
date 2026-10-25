/*
 * Copyright (C) 2026 petalOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.petalos.petaltuner.controllers;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.view.Display;

import org.petalos.petaltuner.core.Sysfs;

public final class DisplayController {

    public static final String PEAK_REFRESH_RATE = "peak_refresh_rate";
    public static final String MIN_REFRESH_RATE = "min_refresh_rate";
    public static final String DISPLAY_DENSITY_FORCED = "display_density_forced";
    public static final String FORCE_GPU_RENDERING = "force_gpu_rendering";
    public static final String DISABLE_HW_OVERLAYS = "disable_hw_overlays";

    private final Context mContext;

    public DisplayController(Context context) {
        mContext = context.getApplicationContext();
    }

    public void applyRefreshRate(float peak, float min) {
        Settings.System.putFloat(mContext.getContentResolver(), PEAK_REFRESH_RATE, peak);
        Settings.System.putFloat(mContext.getContentResolver(), MIN_REFRESH_RATE, min);
    }

    public float getPeakRefreshRate() {
        return Settings.System.getFloat(mContext.getContentResolver(), PEAK_REFRESH_RATE, 0f);
    }

    public float getMinRefreshRate() {
        return Settings.System.getFloat(mContext.getContentResolver(), MIN_REFRESH_RATE, 0f);
    }

    public void applyAnimationScale(float scale) {
        Settings.Global.putFloat(mContext.getContentResolver(),
                Settings.Global.WINDOW_ANIMATION_SCALE, scale);
        Settings.Global.putFloat(mContext.getContentResolver(),
                Settings.Global.TRANSITION_ANIMATION_SCALE, scale);
        Settings.Global.putFloat(mContext.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, scale);
    }

    public float getAnimationScale() {
        return Settings.Global.getFloat(mContext.getContentResolver(),
                Settings.Global.WINDOW_ANIMATION_SCALE, 1f);
    }

    public void setForceGpuRendering(boolean enabled) {
        Settings.Global.putInt(mContext.getContentResolver(), FORCE_GPU_RENDERING,
                enabled ? 1 : 0);
    }

    public void setDisableHwOverlays(boolean enabled) {
        Settings.Global.putInt(mContext.getContentResolver(), DISABLE_HW_OVERLAYS,
                enabled ? 1 : 0);
    }

    public boolean setDensity(int dpi) {
        return Settings.Secure.putInt(mContext.getContentResolver(), DISPLAY_DENSITY_FORCED, dpi);
    }

    public int getDensity() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                DISPLAY_DENSITY_FORCED, 0);
    }

    public float getDisplayRefreshRate() {
        Display display = mContext.getDisplay();
        return display == null ? 0f : display.getRefreshRate();
    }

    public float getMaxSupportedRefreshRate() {
        DisplayManager manager = mContext.getSystemService(DisplayManager.class);
        Display display = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) {
            return 0f;
        }
        float max = display.getRefreshRate();
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getRefreshRate() > max) {
                max = mode.getRefreshRate();
            }
        }
        return max;
    }

    public boolean hasRefreshRateNode() {
        return Sysfs.exists("/sys/class/drm/card0-DSI-1/measure_clk");
    }
}
