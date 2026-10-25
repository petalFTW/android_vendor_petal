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

package org.petalos.agever;

import android.content.Context;
import android.content.SharedPreferences;

import org.petalos.agever.core.ControlMode;

/** Small typed wrapper over SharedPreferences. Safe to call from any thread. */
public final class Prefs {

    private static final String FILE = "agever";

    public static final String KEY_MODE = "control_mode";
    public static final String KEY_AVATAR = "avatar";
    public static final String KEY_MIRROR = "mirror";
    public static final String KEY_IDLE = "idle_motion";
    public static final String KEY_JOYSTICK = "joystick_overlay";
    public static final String KEY_ENABLED = "vcam_enabled";
    public static final String KEY_RESTORE_ON_BOOT = "restore_on_boot";

    /** Asset name of the bundled avatar. */
    public static final String DEFAULT_AVATAR = "avatars/free_old_man_head.glb";

    private static volatile Prefs sInstance;

    private final SharedPreferences mPrefs;

    private Prefs(SharedPreferences prefs) {
        mPrefs = prefs;
    }

    public static void init(Context context) {
        if (sInstance == null) {
            synchronized (Prefs.class) {
                if (sInstance == null) {
                    Context dp = context.createDeviceProtectedStorageContext();
                    sInstance = new Prefs(dp.getSharedPreferences(FILE, Context.MODE_PRIVATE));
                }
            }
        }
    }

    public static Prefs get() {
        Prefs p = sInstance;
        if (p == null) {
            throw new IllegalStateException("Prefs.init() not called");
        }
        return p;
    }

    public ControlMode getMode() {
        return ControlMode.fromKey(mPrefs.getString(KEY_MODE, ControlMode.JOYSTICK.key()));
    }

    public void setMode(ControlMode mode) {
        mPrefs.edit().putString(KEY_MODE, mode.key()).apply();
    }

    public String getAvatar() {
        return mPrefs.getString(KEY_AVATAR, DEFAULT_AVATAR);
    }

    public void setAvatar(String avatar) {
        mPrefs.edit().putString(KEY_AVATAR, avatar).apply();
    }
    public String getLastGoodAvatar() { return mPrefs.getString("last_good_avatar", DEFAULT_AVATAR); }
    public void setLastGoodAvatar(String ref) { mPrefs.edit().putString("last_good_avatar", ref).apply(); }

    public boolean getMirror() {
        return mPrefs.getBoolean(KEY_MIRROR, true);
    }

    public void setMirror(boolean v) {
        mPrefs.edit().putBoolean(KEY_MIRROR, v).apply();
    }

    public boolean getIdleMotion() {
        return mPrefs.getBoolean(KEY_IDLE, true);
    }

    public void setIdleMotion(boolean v) {
        mPrefs.edit().putBoolean(KEY_IDLE, v).apply();
    }

    public boolean getJoystickOverlay() {
        return mPrefs.getBoolean(KEY_JOYSTICK, false);
    }

    public void setJoystickOverlay(boolean v) {
        mPrefs.edit().putBoolean(KEY_JOYSTICK, v).apply();
    }

    public boolean getEnabled() {
        return mPrefs.getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean v) {
        mPrefs.edit().putBoolean(KEY_ENABLED, v).apply();
    }

    public boolean getRestoreOnBoot() {
        return mPrefs.getBoolean(KEY_RESTORE_ON_BOOT, false);
    }

    public void setRestoreOnBoot(boolean v) {
        mPrefs.edit().putBoolean(KEY_RESTORE_ON_BOOT, v).apply();
    }

    public float getExpressionSpeed() { return mPrefs.getFloat("expression_speed", 0.75f); }
    public void setExpressionSpeed(float value) {
        mPrefs.edit().putFloat("expression_speed", Math.max(0f, Math.min(1f, value))).apply();
    }
    public int getFrameRate() { return mPrefs.getInt("frame_rate", 24) == 30 ? 30 : 24; }
    public void setFrameRate(int value) {
        mPrefs.edit().putInt("frame_rate", value == 30 ? 30 : 24).apply();
    }
    public int getPanelX() { return mPrefs.getInt("panel_x", -1); }
    public int getPanelY() { return mPrefs.getInt("panel_y", -1); }
    public void setPanelPosition(int x, int y) {
        mPrefs.edit().putInt("panel_x", x).putInt("panel_y", y).apply();
    }
    public boolean getPanelCollapsed() { return mPrefs.getBoolean("panel_collapsed", false); }
    public void setPanelCollapsed(boolean value) {
        mPrefs.edit().putBoolean("panel_collapsed", value).apply();
    }
    public String getAvatarLabel(String ref, String fallback) {
        return mPrefs.getString("label:" + ref, fallback);
    }
    public void setAvatarLabel(String ref, String label) {
        mPrefs.edit().putString("label:" + ref, label).apply();
    }
    public void removeAvatarLabel(String ref) { mPrefs.edit().remove("label:" + ref).apply(); }
}
