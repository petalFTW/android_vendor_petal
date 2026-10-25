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

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioManager;
import android.provider.Settings;
import android.util.Log;

import org.petalos.petaltuner.core.Prefs;

public final class DistractionController {

    private static final String TAG = "PetalTuner/Focus";

    private static final String ORIG_HEADS_UP = "focus_orig_heads_up";
    private static final String ORIG_ZEN = "focus_orig_zen";
    private static final String ORIG_VOLUME = "focus_orig_volume";
    private static final String ORIG_BRIGHTNESS_MODE = "focus_orig_brightness_mode";
    private static final String ORIG_BRIGHTNESS = "focus_orig_brightness";
    private static final String ORIG_ROTATION = "focus_orig_rotation";

    private final Context mContext;
    private final Prefs mPrefs;

    public DistractionController(Context context) {
        mContext = context.getApplicationContext();
        mPrefs = new Prefs(context);
    }

    public void apply(boolean dnd, boolean headsUp, boolean mute, boolean brightness,
            boolean rotation) {
        captureOriginals();
        if (dnd) {
            setDnd(true);
        } else {
            setDnd(false);
        }
        if (headsUp) {
            setHeadsUp(false);
        }
        if (mute) {
            muteMedia(true);
        }
        if (brightness) {
            lockBrightness(true);
        }
        if (rotation) {
            lockRotation(true);
        }
    }

    public void restore() {
        // Android 16 owns our DND rule; copying global DND back turns ours on again
        setDnd(false);
        if (mPrefs.raw().contains(ORIG_HEADS_UP)) {
            setHeadsUp(mPrefs.getInt(ORIG_HEADS_UP, 1) != 0);
        }
        if (mPrefs.raw().contains(ORIG_VOLUME)) {
            muteMedia(false);
        }
        if (mPrefs.raw().contains(ORIG_BRIGHTNESS_MODE)) {
            lockBrightness(false);
        }
        if (mPrefs.raw().contains(ORIG_ROTATION)) {
            lockRotation(false);
        }
        mPrefs.raw().edit().remove(ORIG_ZEN).remove(ORIG_HEADS_UP).remove(ORIG_VOLUME)
                .remove(ORIG_BRIGHTNESS_MODE).remove(ORIG_BRIGHTNESS).remove(ORIG_ROTATION)
                .apply();
    }

    private void captureOriginals() {
        if (!mPrefs.raw().contains(ORIG_HEADS_UP)) {
            mPrefs.putInt(ORIG_HEADS_UP, Settings.Global.getInt(
                    mContext.getContentResolver(), "heads_up_notifications_enabled", 1));
        }
        if (!mPrefs.raw().contains(ORIG_VOLUME)) {
            AudioManager audio = mContext.getSystemService(AudioManager.class);
            mPrefs.putInt(ORIG_VOLUME, audio == null ? -1
                    : audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        }
        if (!mPrefs.raw().contains(ORIG_BRIGHTNESS_MODE)) {
            mPrefs.putInt(ORIG_BRIGHTNESS_MODE, Settings.System.getInt(
                    mContext.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC));
        }
        if (!mPrefs.raw().contains(ORIG_BRIGHTNESS)) {
            mPrefs.putInt(ORIG_BRIGHTNESS, Settings.System.getInt(
                    mContext.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128));
        }
        if (!mPrefs.raw().contains(ORIG_ROTATION)) {
            mPrefs.putInt(ORIG_ROTATION, Settings.System.getInt(
                    mContext.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1));
        }
    }

    private void setDnd(boolean enabled) {
        NotificationManager manager = mContext.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        try {
            manager.setInterruptionFilter(enabled
                    ? NotificationManager.INTERRUPTION_FILTER_ALARMS
                    : NotificationManager.INTERRUPTION_FILTER_ALL);
        } catch (SecurityException e) {
            Log.w(TAG, "DND denied", e);
        }
    }

    public void setHeadsUp(boolean enabled) {
        Settings.Global.putInt(mContext.getContentResolver(),
                "heads_up_notifications_enabled", enabled ? 1 : 0);
    }

    private void muteMedia(boolean mute) {
        AudioManager audio = mContext.getSystemService(AudioManager.class);
        if (audio == null) {
            return;
        }
        if (mute) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        } else {
            int original = mPrefs.getInt(ORIG_VOLUME, -1);
            if (original >= 0) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0);
            }
        }
    }

    private void lockBrightness(boolean lock) {
        if (lock) {
            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        } else {
            int mode = mPrefs.getInt(ORIG_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE, mode);
            int brightness = mPrefs.getInt(ORIG_BRIGHTNESS, 128);
            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, brightness);
        }
    }

    private void lockRotation(boolean lock) {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, lock ? 0
                        : mPrefs.getInt(ORIG_ROTATION, 1));
    }

}
