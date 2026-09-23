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
        return mPrefs.getBoolean(KEY_RESTORE_ON_BOOT, true);
    }

    public void setRestoreOnBoot(boolean v) {
        mPrefs.edit().putBoolean(KEY_RESTORE_ON_BOOT, v).apply();
    }
}
