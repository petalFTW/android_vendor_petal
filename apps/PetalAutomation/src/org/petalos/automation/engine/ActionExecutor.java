/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.automation.engine;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.petalos.automation.data.AutomationStore;
import org.petalos.automation.model.Action;
import org.petalos.automation.model.Types;
import org.petalos.automation.service.UiAutomationService;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** does the actual shit. one worker thread, actions in order. */
public final class ActionExecutor {
    private static final String TAG = "PetalAutomation";
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    private ActionExecutor() {
    }

    public static void run(Context context, List<Action> actions) {
        final Context app = context.getApplicationContext();
        POOL.execute(() -> {
            int ran = 0;
            for (Action action : actions) {
                if (action.gate != null && !ConditionEvaluator.passes(app, action.gate)) {
                    continue;
                }
                try {
                    execute(app, action);
                    ran++;
                } catch (Throwable e) {
                    // no god-mode anymore, a dead action must not take the worker with it
                    Log.w(TAG, "action " + action.type + " blew up", e);
                }
            }
            AutomationStore.setStats(app, "last run: " + ran + "/" + actions.size()
                    + " actions @ " + new java.util.Date());
        });
    }

    private static void execute(Context context, Action action) throws Exception {
        switch (action.type) {
            case Types.A_LAUNCH: {
                final String pkg = action.get(Types.P_APP, "");
                final Intent intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                }
                break;
            }
            case Types.A_CLOSE: {
                final android.app.ActivityManager am =
                        context.getSystemService(android.app.ActivityManager.class);
                if (am != null) {
                    am.forceStopPackage(action.get(Types.P_APP, ""));
                }
                break;
            }
            case Types.A_SETTING:
                setToggle(context, action);
                break;
            case Types.A_BRIGHTNESS: {
                final int level = clamp(action.getInt(Types.P_LEVEL, 50));
                Settings.System.putInt(context.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(context.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, Math.round(level * 255f / 100f));
                break;
            }
            case Types.A_VOLUME: {
                final AudioManager audio = context.getSystemService(AudioManager.class);
                if (audio != null) {
                    final int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    final int index = Math.round(clamp(action.getInt(Types.P_LEVEL, 50))
                            * max / 100f);
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0);
                }
                break;
            }
            case Types.A_RINGER: {
                final AudioManager audio = context.getSystemService(AudioManager.class);
                if (audio != null) {
                    final String mode = action.get(Types.P_VALUE, "normal");
                    if ("silent".equals(mode)) {
                        audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);
                    } else if ("vibrate".equals(mode)) {
                        audio.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                    } else {
                        audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                    }
                }
                break;
            }
            case Types.A_TAP:
                UiAutomationService.tap(action.getInt(Types.P_X, 0), action.getInt(Types.P_Y, 0));
                break;
            case Types.A_SWIPE:
                UiAutomationService.swipe(action.getInt(Types.P_X, 0), action.getInt(Types.P_Y, 0),
                        action.getInt(Types.P_X2, 0), action.getInt(Types.P_Y2, 0),
                        action.getInt(Types.P_MS, 300));
                break;
            case Types.A_TEXT:
                UiAutomationService.typeText(action.get(Types.P_TEXT, ""));
                break;
            case Types.A_INTENT: {
                final Intent intent = new Intent(action.get(Types.P_ACTION, Intent.ACTION_MAIN));
                final String component = action.get(Types.P_COMPONENT, "");
                final String pkg = action.get(Types.P_PACKAGE, "");
                if (!component.isEmpty() && component.contains("/")) {
                    intent.setClassName(component.substring(0, component.indexOf('/')),
                            component.substring(component.indexOf('/') + 1));
                } else if (!pkg.isEmpty()) {
                    intent.setPackage(pkg);
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                break;
            }
            case Types.A_NOTIFY:
                notify(context, action);
                break;
            case Types.A_TOAST: {
                final String text = action.get(Types.P_TEXT, "");
                new Handler(Looper.getMainLooper()).post(
                        () -> Toast.makeText(context, text, Toast.LENGTH_SHORT).show());
                break;
            }
            case Types.A_WAIT: {
                final int seconds = Math.max(0, Math.min(30, action.getInt(Types.P_SECONDS, 1)));
                Thread.sleep(seconds * 1000L);
                break;
            }
            default:
                Log.w(TAG, "unknown action " + action.type);
        }
    }

    private static void setToggle(Context context, Action action) {
        final String setting = action.get(Types.P_SETTING, "wifi");
        final boolean on = "1".equals(action.get(Types.P_VALUE, "1"))
                || "on".equalsIgnoreCase(action.get(Types.P_VALUE, "1"));
        switch (setting) {
            case "wifi": {
                final android.net.wifi.WifiManager wifi =
                        context.getSystemService(android.net.wifi.WifiManager.class);
                if (wifi != null) {
                    wifi.setWifiEnabled(on);
                }
                break;
            }
            case "bluetooth": {
                final android.bluetooth.BluetoothManager bm =
                        context.getSystemService(android.bluetooth.BluetoothManager.class);
                if (bm != null && bm.getAdapter() != null) {
                    final android.bluetooth.BluetoothAdapter adapter = bm.getAdapter();
                    if (on) {
                        adapter.enable();
                    } else {
                        adapter.disable();
                    }
                }
                break;
            }
            case "airplane":
                Settings.Global.putInt(context.getContentResolver(),
                        Settings.Global.AIRPLANE_MODE_ON, on ? 1 : 0);
                break;
            case "rotation":
                Settings.System.putInt(context.getContentResolver(),
                        Settings.System.ACCELEROMETER_ROTATION, on ? 1 : 0);
                break;
            case "flashlight":
                setTorch(context, on);
                break;
            case "dnd": {
                final NotificationManager nm = context.getSystemService(NotificationManager.class);
                if (nm != null) {
                    nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_NONE
                            : NotificationManager.INTERRUPTION_FILTER_ALL);
                }
                break;
            }
            default:
                Log.w(TAG, "unknown toggle " + setting);
        }
    }

    private static void setTorch(Context context, boolean on) {
        final CameraManager cameras = context.getSystemService(CameraManager.class);
        if (cameras == null) {
            return;
        }
        try {
            for (String id : cameras.getCameraIdList()) {
                final CameraCharacteristics chars = cameras.getCameraCharacteristics(id);
                if (Boolean.TRUE.equals(chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) {
                    cameras.setTorchMode(id, on);
                    return;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "torch said no", e);
        }
    }

    private static void notify(Context context, Action action) {
        final NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        final String channelId = "petal_automation";
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(new NotificationChannel(channelId, "Automation",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        final Notification notification = new Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(action.get(Types.P_TITLE, "Automation"))
                .setContentText(action.get(Types.P_TEXT, ""))
                .build();
        nm.notify((int) (System.currentTimeMillis() & 0xFFFFFF), notification);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
