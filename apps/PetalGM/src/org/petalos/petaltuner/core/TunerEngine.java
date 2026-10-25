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

package org.petalos.petaltuner.core;

import android.content.Context;
import android.util.Log;

import org.petalos.petaltuner.controllers.ChargingController;
import org.petalos.petaltuner.controllers.DisplayController;
import org.petalos.petaltuner.controllers.DistractionController;
import org.petalos.petaltuner.controllers.MemoryController;
import org.petalos.petaltuner.controllers.NetworkController;
import org.petalos.config.PetalConfig;
import org.petalos.petaltuner.controllers.PerformanceController;

public final class TunerEngine {

    private static final String TAG = "PetalTuner/Engine";

    private static TunerEngine sInstance;

    private final Prefs mPrefs;
    private final StateBackup mBackup;
    private final ChargingController mCharging;
    private final PerformanceController mPerformance;
    private final DisplayController mDisplay;
    private final DistractionController mFocus;
    private final NetworkController mNetwork;
    private final MemoryController mMemory;
    private final GameModeController mGameMode;
    private final ForegroundAppDetector mForeground;
    private final GmConfig mGm;
    private final Context mContext;

    private String mAppliedGame;

    private TunerEngine(Context context) {
        Context app = context.getApplicationContext();
        mContext = app;
        mPrefs = new Prefs(app);
        mGm = new GmConfig(app);
        mBackup = new StateBackup(app);
        mCharging = new ChargingController(app);
        mPerformance = new PerformanceController(app);
        mDisplay = new DisplayController(app);
        mFocus = new DistractionController(app);
        mNetwork = new NetworkController(app);
        mMemory = new MemoryController(app);
        mGameMode = new GameModeController(app);
        mForeground = new ForegroundAppDetector(app);
    }

    public static synchronized TunerEngine get(Context context) {
        if (sInstance == null) {
            sInstance = new TunerEngine(context);
        }
        return sInstance;
    }

    public Prefs prefs() {
        return mPrefs;
    }

    public StateBackup backup() {
        return mBackup;
    }

    public ChargingController charging() {
        return mCharging;
    }

    public PerformanceController performance() {
        return mPerformance;
    }

    public DisplayController display() {
        return mDisplay;
    }

    public DistractionController focus() {
        return mFocus;
    }

    public NetworkController network() {
        return mNetwork;
    }

    public MemoryController memory() {
        return mMemory;
    }

    public GameModeController gameMode() {
        return mGameMode;
    }

    public GmConfig gm() {
        return mGm;
    }

    public ForegroundAppDetector foreground() {
        return mForeground;
    }

    public String getActiveProfile() {
        return mPrefs.getString(Prefs.ACTIVE_PROFILE, "balanced");
    }

    public void setActiveProfile(String key) {
        mPrefs.putString(Prefs.ACTIVE_PROFILE, key);
    }

    public boolean applyProfile(String key) {
        Profile profile = ProfileManager.get(key);
        setActiveProfile(profile.key);
        return mPerformance.apply(profile);
    }

    public boolean applyActiveProfile() {
        return applyProfile(getActiveProfile());
    }

    private int mLastCoolDown = -1;
    private int mLastSlow = -1;
    private int mLastEndurance = -1;

    public void applyChargingExtras() {
        int coolDown = mPrefs.getInt(Prefs.COOLDOWN, 0);
        int slow = mPrefs.getBool(Prefs.SLOW_CHARGE, false) ? 1 : 0;
        int endurance = mPrefs.getBool(Prefs.ENDURANCE, false) ? 1 : 0;
        if (mLastCoolDown == -1 && coolDown == 0 && slow == 0 && endurance == 0) {
            mLastCoolDown = 0;
            mLastSlow = 0;
            mLastEndurance = 0;
            return;
        }
        if (coolDown != mLastCoolDown) {
            mCharging.setCoolDown(coolDown);
            mLastCoolDown = coolDown;
        }
        if (slow != mLastSlow) {
            mCharging.setSlowCharge(slow == 1);
            mLastSlow = slow;
        }
        if (endurance != mLastEndurance) {
            mCharging.setEndurance(endurance == 1);
            mLastEndurance = endurance;
        }
    }

    public void applyDisplayState() {
        int refresh = mPrefs.getInt(Prefs.REFRESH_RATE, 0);
        if (refresh > 0) {
            mDisplay.applyRefreshRate(refresh, 60);
        }
        float animation = Float.parseFloat(mPrefs.getString(Prefs.ANIMATION_SCALE, "1.0"));
        mDisplay.applyAnimationScale(animation);
        mDisplay.setForceGpuRendering(mPrefs.getBool(Prefs.FORCE_GPU, false));
        mDisplay.setDisableHwOverlays(mPrefs.getBool(Prefs.DISABLE_HW_OVERLAYS, false));
    }

    public void applyNetworkState() {
        String congestion = mPrefs.getString(Prefs.NETWORK_CONGESTION, null);
        if (congestion != null) {
            mNetwork.setCongestion(congestion);
        }
        if (mPrefs.getBool(Prefs.NETWORK_BUFFERS, false)) {
            mNetwork.applyLowLatency();
        }
        boolean suppress = mPrefs.getBool(Prefs.NETWORK_SUPPRESS_SCANS, false);
        mNetwork.setWifiScanEnabled(!suppress);
        mNetwork.setBleScanEnabled(!suppress);
    }

    public void applyMemoryState() {
        if (mPrefs.getBool(Prefs.MEMORY_APPLY, false)) {
            mMemory.setSwappiness(mPrefs.getInt(Prefs.MEMORY_SWAPPINESS, 100));
            mMemory.setExtraFreeKbytes(mPrefs.getInt(Prefs.MEMORY_EXTRA_FREE_KB, 32768));
        }
    }

    public void setFocusEnabled(boolean enabled) {
        mPrefs.putBool(Prefs.FOCUS_ENABLED, enabled);
        if (enabled) {
            mFocus.apply(mPrefs.getBool(Prefs.FOCUS_DND, true),
                    mPrefs.getBool(Prefs.FOCUS_HEADS_UP, true),
                    mPrefs.getBool(Prefs.FOCUS_MUTE, false),
                    mPrefs.getBool(Prefs.FOCUS_BRIGHTNESS, false),
                    mPrefs.getBool(Prefs.FOCUS_ROTATION, false));
        } else {
            mFocus.restore();
        }
    }

    public void onPowerChanged() {
        enforceCharging();
    }

    public void onForegroundChanged() {
        String game = mForeground.getForegroundGame();
        String active = mPrefs.getString(Prefs.GM_ACTIVE, null);
        if (game == null) {
            if (active != null) {
                endGameSession(active);
            }
            return;
        }
        if (!game.equals(active)) {
            if (active != null) {
                endGameSession(active);
            }
            startGameSession(game);
            return;
        }
        if (mGm.flag(PetalConfig.KEY_GM_BYPASS, true) && mCharging.isPlugged()
                && !mCharging.isBypassActive()) {
            mCharging.setBypass(true);
            mPrefs.putBool(Prefs.GM_BYPASS_BY_GM, true);
        }
    }

    private void startGameSession(String game) {
        Log.i(TAG, "game session start: " + game);
        mPrefs.putString(Prefs.GM_ACTIVE, game);
        mAppliedGame = game;

        mPrefs.putString(Prefs.GM_PREV_PROFILE, getActiveProfile());
        String profile = mGm.profile();
        applyProfile(profile);

        if (mGm.flag(PetalConfig.KEY_GM_BYPASS, true) && mCharging.isPlugged()
                && !mCharging.isBypassActive()) {
            mCharging.setBypass(true);
            mPrefs.putBool(Prefs.GM_BYPASS_BY_GM, true);
        } else if (mPrefs.getBool(Prefs.BYPASS_AUTO, false) && mCharging.isPlugged()) {
            mCharging.setBypass(true);
            mPrefs.putBool(Prefs.GM_BYPASS_BY_GM, true);
        }

        if (mGm.flag(PetalConfig.KEY_GM_MAX_HZ, true)) {
            float max = mDisplay.getMaxSupportedRefreshRate();
            if (max > 0f) {
                mPrefs.putString(Prefs.GM_ORIG_PEAK, Float.toString(
                        android.provider.Settings.System.getFloat(
                                mContext.getContentResolver(),
                                DisplayController.PEAK_REFRESH_RATE, -1f)));
                mPrefs.putString(Prefs.GM_ORIG_MIN, Float.toString(
                        android.provider.Settings.System.getFloat(
                                mContext.getContentResolver(),
                                DisplayController.MIN_REFRESH_RATE, -1f)));
                mPrefs.putBool(Prefs.GM_HZ_BY_GM, true);
                mDisplay.applyRefreshRate(max, max);
            }
        }

        if (mGm.flag(PetalConfig.KEY_GM_MAX_RES, true)) {
            mPrefs.putBool(Prefs.GM_RES_BY_GM, true);
            mGm.requestMaxResolution(true);
        }

        if (mGm.flag(PetalConfig.KEY_GM_FOCUS, true)) {
            mPrefs.putBool(Prefs.GM_FOCUS_PREV, mPrefs.getBool(Prefs.FOCUS_ENABLED, false));
            mPrefs.putBool(Prefs.GM_FOCUS_BY_GM, true);
            setFocusEnabled(true);
        }

        if (mGm.flag(PetalConfig.KEY_GM_FREE_RAM, true)) {
            mMemory.killBackgroundProcesses();
        }

        if (mPrefs.getBool(Prefs.USE_GAME_MANAGER, true)) {
            mGameMode.applyProfileMode(game, profile);
        }
    }

    private void endGameSession(String game) {
        Log.i(TAG, "game session end: " + game);
        mPrefs.remove(Prefs.GM_ACTIVE);
        mAppliedGame = null;

        if (mPrefs.getBool(Prefs.USE_GAME_MANAGER, true)) {
            mGameMode.applyGameMode(game, android.app.GameManager.GAME_MODE_STANDARD);
        }

        if (mPrefs.getBool(Prefs.GM_FOCUS_BY_GM, false)) {
            mPrefs.putBool(Prefs.GM_FOCUS_BY_GM, false);
            if (!mPrefs.getBool(Prefs.GM_FOCUS_PREV, false)) {
                setFocusEnabled(false);
            }
        }

        if (mPrefs.getBool(Prefs.GM_RES_BY_GM, false)) {
            mPrefs.putBool(Prefs.GM_RES_BY_GM, false);
            mGm.requestMaxResolution(false);
        }

        if (mPrefs.getBool(Prefs.GM_HZ_BY_GM, false)) {
            mPrefs.putBool(Prefs.GM_HZ_BY_GM, false);
            restoreRefresh(Prefs.GM_ORIG_PEAK, DisplayController.PEAK_REFRESH_RATE);
            restoreRefresh(Prefs.GM_ORIG_MIN, DisplayController.MIN_REFRESH_RATE);
            int refresh = mPrefs.getInt(Prefs.REFRESH_RATE, 0);
            if (refresh > 0) {
                mDisplay.applyRefreshRate(refresh, mPrefs.getInt(Prefs.MIN_REFRESH_RATE, 60));
            }
        }

        if (mPrefs.getBool(Prefs.GM_BYPASS_BY_GM, false)) {
            mPrefs.putBool(Prefs.GM_BYPASS_BY_GM, false);
            if (!mPrefs.getBool(Prefs.BYPASS_ENABLED, false)) {
                mCharging.setBypass(false);
            }
        }

        String previous = mPrefs.getString(Prefs.GM_PREV_PROFILE, null);
        if (previous != null) {
            applyProfile(previous);
        }
    }

    private void restoreRefresh(String prefKey, String settingKey) {
        float value;
        try {
            value = Float.parseFloat(mPrefs.getString(prefKey, "-1"));
        } catch (NumberFormatException e) {
            value = -1f;
        }
        if (value > 0f) {
            android.provider.Settings.System.putFloat(mContext.getContentResolver(),
                    settingKey, value);
        } else {
            android.provider.Settings.System.putString(mContext.getContentResolver(),
                    settingKey, null);
        }
    }

    public void shutdownSession() {
        String active = mPrefs.getString(Prefs.GM_ACTIVE, null);
        if (active != null) {
            endGameSession(active);
        }
    }

    public void enforceCharging() {
        if (mPrefs.getBool(Prefs.CHARGE_LIMIT_ENABLED, false)) {
            mCharging.enforceChargeLimit(true, mPrefs.getInt(Prefs.CHARGE_LIMIT, 80),
                    mPrefs.getInt(Prefs.CHARGE_RESUME, 75));
        }
        if (mPrefs.getBool(Prefs.BYPASS_ENABLED, false)) {
            mCharging.setBypass(true);
        }
        applyChargingExtras();
    }

    public void restoreDefaults() {
        mFocus.restore();
        mBackup.restoreAll();
        mGameMode.resetAll();
        setActiveProfile("balanced");
        mPrefs.putBool(Prefs.BYPASS_ENABLED, false);
        mPrefs.putBool(Prefs.CHARGE_LIMIT_ENABLED, false);
        mPrefs.putBool(Prefs.FOCUS_ENABLED, false);
        mPrefs.putBool(Prefs.THERMAL_DISABLE, false);
        mPrefs.putBool(Prefs.EXPERT_FSYNC, false);
        if (mCharging.isSupported()) {
            mCharging.setBypass(false);
        }
        Log.i(TAG, "restored defaults");
    }
}
