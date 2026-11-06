/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.power;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import android.app.AppOpsManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.*;
import android.os.PowerManager;
import android.os.PowerExemptionManager;
import android.provider.Settings;
import java.lang.reflect.Method;
import java.util.*;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

/** Runs actual PetalPower code with fake Android persistence/services, not a copy of its algorithm. */
public class IdlePowerJournalTest {
    private static final String NAME = "com.example.dashcam";
    private static final String OP = AppOpsManager.OPSTR_FINE_LOCATION;
    private Context context;
    private PackageInfo info;
    private UsageStatsManager usage;
    private AppOpsManager ops;
    private PowerManager power;
    private PowerExemptionManager exemptions;
    private MemoryPrefs prefs;
    private int bucket;
    private final Map<String, Integer> modes = new HashMap<>();

    @Before public void setup() throws Exception {
        Settings.clear();
        prefs = new MemoryPrefs();
        context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.createDeviceProtectedStorageContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        PackageManager pm = mock(PackageManager.class);
        when(context.getPackageManager()).thenReturn(pm);
        info = new PackageInfo();
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = NAME;
        info.applicationInfo.uid = 10023;
        info.firstInstallTime = 12345;
        info.signingInfo = new SigningInfo(new SigningDetails(new Signature[]{new Signature("aa")}, 3, null, null));
        when(pm.getPackageInfo(eq(NAME), anyInt())).thenReturn(info);
        when(pm.getPackagesForUid(10023)).thenReturn(new String[]{NAME});
        when(pm.hasSigningCertificate(eq(NAME), any(byte[].class), eq(PackageManager.CERT_INPUT_SHA256))).thenReturn(true);
        usage = mock(UsageStatsManager.class);
        ops = mock(AppOpsManager.class);
        power = mock(PowerManager.class);
        exemptions = mock(PowerExemptionManager.class);
        when(context.getSystemService(UsageStatsManager.class)).thenReturn(usage);
        when(context.getSystemService(AppOpsManager.class)).thenReturn(ops);
        when(context.getSystemService(PowerManager.class)).thenReturn(power);
        when(context.getSystemService(PowerExemptionManager.class)).thenReturn(exemptions);
        when(power.isInteractive()).thenReturn(false);
        bucket = UsageStatsManager.STANDBY_BUCKET_RESTRICTED;
        modes.clear();
        modes.put(OP, AppOpsManager.MODE_IGNORED);
        when(usage.getAppStandbyBucket(NAME)).thenAnswer(invocation -> bucket);
        doAnswer(invocation -> { bucket = invocation.getArgument(1); return null; })
                .when(usage).setAppStandbyBucket(eq(NAME), anyInt());
        when(ops.unsafeCheckOpRawNoThrow(anyString(), eq(10023), eq(NAME))).thenAnswer(
                invocation -> modes.getOrDefault(invocation.getArgument(0), AppOpsManager.MODE_ALLOWED));
        doAnswer(invocation -> { modes.put(invocation.getArgument(0), invocation.getArgument(3)); return null; })
                .when(ops).setMode(anyString(), eq(10023), eq(NAME), anyInt());
        Settings.System.putString(null, IdlePowerReceiver.SETTING_PACKAGES, NAME);
        seed();
    }

    private void seed() {
        prefs.edit().putBoolean("transaction_active", true).putInt("applied_profile", 2)
                .putInt("uid:" + NAME, 10023).putLong("installed:" + NAME, 12345)
                .putInt("bucket:" + NAME, UsageStatsManager.STANDBY_BUCKET_FREQUENT)
                .putInt("op:" + OP + ':' + NAME, AppOpsManager.MODE_FOREGROUND).commit();
    }

    private void exempt(boolean value) throws Exception {
        JSONObject entry = new JSONObject().put("uid", 10023).put("installed", 12345)
                .put("signer", "a".repeat(64)).put("idle_exempt", value).put("modes", new JSONObject());
        Settings.Secure.putStringForUser(null, Settings.Secure.PETAL_SPOOFED_PACKAGES,
                new JSONObject().put("version", 1).put("packages", new JSONObject().put(NAME, entry)).toString(), 0);
    }

    private void call(String name) throws Exception {
        Method method = IdlePowerReceiver.class.getDeclaredMethod(name, Context.class);
        method.setAccessible(true);
        method.invoke(null, context);
    }

    @Test public void rawForegroundModeSurvivesRestoration() throws Exception {
        call("restore");
        assertEquals(AppOpsManager.MODE_FOREGROUND, (int) modes.get(OP));
        assertEquals(UsageStatsManager.STANDBY_BUCKET_FREQUENT, bucket);
        assertFalse(prefs.contains("transaction_active"));
    }

    @Test public void newerRealSelectionsAreNotOverwritten() throws Exception {
        bucket = UsageStatsManager.STANDBY_BUCKET_ACTIVE;
        modes.put(OP, AppOpsManager.MODE_ALLOWED);
        call("restore");
        verify(usage, never()).setAppStandbyBucket(anyString(), anyInt());
        verify(ops, never()).setMode(anyString(), anyInt(), anyString(), anyInt());
        assertFalse(prefs.contains("transaction_active"));
    }

    @Test public void reinstallDiscardsOldJournalWithoutApplyingIt() throws Exception {
        info.firstInstallTime++;
        call("restore");
        verify(usage, never()).setAppStandbyBucket(anyString(), anyInt());
        verify(ops, never()).setMode(anyString(), anyInt(), anyString(), anyInt());
        assertTrue(prefs.getAll().isEmpty());
    }

    @Test public void failedRestoreRetainsWorkAndCanRetry() throws Exception {
        doThrow(new SecurityException("temporary failure")).when(ops).setMode(OP, 10023, NAME, AppOpsManager.MODE_FOREGROUND);
        call("restore");
        assertTrue(prefs.contains("op:" + OP + ':' + NAME));
        assertTrue(prefs.getBoolean("restore_error:" + NAME, false));
        assertTrue(IdlePowerReceiver.idleNetworkPackages(context).isEmpty());
        doAnswer(invocation -> { modes.put(OP, invocation.getArgument(3)); return null; })
                .when(ops).setMode(OP, 10023, NAME, AppOpsManager.MODE_FOREGROUND);
        call("restore");
        assertEquals(AppOpsManager.MODE_FOREGROUND, (int) modes.get(OP));
        assertTrue(prefs.getAll().isEmpty());
    }

    @Test public void exemptionRestoresAnActiveTransactionAndIdleNetwork() throws Exception {
        exempt(true);
        IdlePowerReceiver.reconcileExemptions(context);
        assertEquals(AppOpsManager.MODE_FOREGROUND, (int) modes.get(OP));
        assertFalse(prefs.contains("bucket:" + NAME));
        assertTrue(IdlePowerReceiver.idleNetworkPackages(context).isEmpty());
    }

    @Test public void explicitNetworkDenySurvivesIdleExemption() throws Exception {
        exempt(true);
        Settings.System.putString(null, NetworkPolicyReceiver.SETTING_DENIED, NAME);
        assertTrue(NetworkPolicyReceiver.deniedPackages(context).contains(NAME));
        assertTrue(IdlePowerReceiver.idleNetworkPackages(context).isEmpty());
    }

    @Test public void disablingExemptionRejoinsCurrentTransactionWithFreshBaseline() throws Exception {
        exempt(true);
        IdlePowerReceiver.reconcileExemptions(context);
        exempt(false);
        IdlePowerReceiver.reconcileExemptions(context);
        assertEquals(UsageStatsManager.STANDBY_BUCKET_RESTRICTED, bucket);
        assertEquals(AppOpsManager.MODE_IGNORED, (int) modes.get(OP));
        assertEquals(AppOpsManager.MODE_FOREGROUND, prefs.getInt("op:" + OP + ':' + NAME, -1));
        assertTrue(IdlePowerReceiver.idleNetworkPackages(context).contains(NAME));
    }

    @Test public void failedWriteAheadCommitDoesNotMutatePolicy() throws Exception {
        prefs.edit().clear().commit();
        prefs.failCommit = true;
        call("apply");
        verify(usage, never()).setAppStandbyBucket(anyString(), anyInt());
        verify(ops, never()).setMode(anyString(), anyInt(), anyString(), anyInt());
    }

    @Test public void pushTransportsAreProtectedInBothProfiles() throws Exception {
        String[] transports = {"com.google.android.gms", "com.google.android.gsf", "com.android.vending",
                "app.revanced.android.gms", "com.mgoogle.android.gms", "org.microg.gms",
                "app.grapheneos.gmscompat", "app.grapheneos.gmscompat.lib"};
        for (int profile : new int[]{1, 2}) {
            for (String name : transports) {
                prefs.edit().clear().commit();
                Settings.System.putString(null, IdlePowerReceiver.SETTING_PACKAGES, name);
                Settings.System.putString(null, IdlePowerReceiver.SETTING_PROFILE, Integer.toString(profile));
                call("apply");
                assertTrue(name, prefs.getAll().isEmpty());
                assertTrue(IdlePowerReceiver.idleNetworkPackages(context).isEmpty());
            }
        }
        verify(usage, never()).setAppStandbyBucket(anyString(), anyInt());
        verify(ops, never()).setMode(anyString(), anyInt(), anyString(), anyInt());
    }

    @Test public void protectedPushSiblingRestoresTheWholeIdleUid() throws Exception {
        when(context.getPackageManager().getPackagesForUid(10023))
                .thenReturn(new String[]{NAME, "com.google.android.gms"});
        IdlePowerReceiver.reconcileExemptions(context);
        assertEquals(UsageStatsManager.STANDBY_BUCKET_FREQUENT, bucket);
        assertEquals(AppOpsManager.MODE_FOREGROUND, (int) modes.get(OP));
        assertTrue(IdlePowerReceiver.idleNetworkPackages(context).isEmpty());
        assertFalse(prefs.contains("bucket:" + NAME));
    }

    @Test public void realBatteryExemptionRestoresPreviousRestriction() throws Exception {
        when(exemptions.isAllowListed(NAME, true)).thenReturn(true);
        IdlePowerReceiver.reconcileExemptions(context);
        assertEquals(UsageStatsManager.STANDBY_BUCKET_FREQUENT, bucket);
        assertEquals(AppOpsManager.MODE_FOREGROUND, (int) modes.get(OP));
        assertTrue(IdlePowerReceiver.idleNetworkPackages(context).isEmpty());
    }

    @Test public void batteryExemptionOfSiblingProtectsUid() throws Exception {
        when(context.getPackageManager().getPackagesForUid(10023))
                .thenReturn(new String[]{NAME, "com.example.push"});
        when(exemptions.isAllowListed("com.example.push", true)).thenReturn(true);
        assertTrue(IdlePowerProtection.protects(context, NAME));
        assertTrue(IdlePowerReceiver.idleNetworkPackages(context).isEmpty());
    }

    @Test public void explicitNetworkDenyStillAppliesToProtectedUid() throws Exception {
        when(exemptions.isAllowListed(NAME, true)).thenReturn(true);
        Settings.System.putString(null, NetworkPolicyReceiver.SETTING_DENIED, NAME);
        assertTrue(NetworkPolicyReceiver.deniedPackages(context).contains(NAME));
    }

    @Test public void reportedBatteryExemptionDoesNotChangeActualEnforcement() throws Exception {
        prefs.edit().clear().commit();
        when(power.isIgnoringBatteryOptimizations(NAME)).thenReturn(true);
        when(exemptions.isAllowListed(NAME, true)).thenReturn(false);
        Settings.System.putString(null, IdlePowerReceiver.SETTING_PROFILE, "2");
        call("apply");
        assertEquals(UsageStatsManager.STANDBY_BUCKET_RESTRICTED, bucket);
        assertTrue(IdlePowerReceiver.idleNetworkPackages(context).contains(NAME));
    }

    @Test public void optimizedKeepsNetworkAndBackgroundAppOpsAndRestoresRareBucket() throws Exception {
        prefs.edit().clear().commit();
        bucket = UsageStatsManager.STANDBY_BUCKET_ACTIVE;
        Settings.System.putString(null, IdlePowerReceiver.SETTING_PROFILE, "1");
        call("apply");
        assertEquals(UsageStatsManager.STANDBY_BUCKET_RARE, bucket);
        assertTrue(IdlePowerReceiver.idleNetworkPackages(context).isEmpty());
        verify(ops, never()).setMode(anyString(), anyInt(), anyString(), anyInt());
        call("restore");
        assertEquals(UsageStatsManager.STANDBY_BUCKET_ACTIVE, bucket);
        assertTrue(prefs.getAll().isEmpty());
    }

    @Test public void optimizedDoesNotUndoNewerRestrictedChoice() throws Exception {
        prefs.edit().clear().commit();
        bucket = UsageStatsManager.STANDBY_BUCKET_ACTIVE;
        Settings.System.putString(null, IdlePowerReceiver.SETTING_PROFILE, "1");
        call("apply");
        bucket = UsageStatsManager.STANDBY_BUCKET_RESTRICTED;
        call("restore");
        assertEquals(UsageStatsManager.STANDBY_BUCKET_RESTRICTED, bucket);
        assertTrue(prefs.getAll().isEmpty());
    }

    @Test public void deepRetainsExplicitRestrictionsAndRollback() throws Exception {
        prefs.edit().clear().commit();
        bucket = UsageStatsManager.STANDBY_BUCKET_FREQUENT;
        modes.put(OP, AppOpsManager.MODE_FOREGROUND);
        Settings.System.putString(null, IdlePowerReceiver.SETTING_PROFILE, "2");
        call("apply");
        assertEquals(UsageStatsManager.STANDBY_BUCKET_RESTRICTED, bucket);
        assertEquals(AppOpsManager.MODE_IGNORED, (int) modes.get(OP));
        assertTrue(IdlePowerReceiver.idleNetworkPackages(context).contains(NAME));
        call("restore");
        assertEquals(UsageStatsManager.STANDBY_BUCKET_FREQUENT, bucket);
        assertEquals(AppOpsManager.MODE_FOREGROUND, (int) modes.get(OP));
        assertTrue(prefs.getAll().isEmpty());
    }

    @Test public void staleApplyAfterDisableCannotRestrict() throws Exception {
        prefs.edit().clear().commit();
        Settings.System.putString(null, IdlePowerReceiver.SETTING_ENABLED, "0");
        call("apply");
        verify(usage, never()).setAppStandbyBucket(anyString(), anyInt());
        verify(ops, never()).setMode(anyString(), anyInt(), anyString(), anyInt());
        assertTrue(prefs.getAll().isEmpty());
    }

    @Test public void switchingOffRestoresActiveTransactionImmediately() throws Exception {
        Settings.System.putString(null, IdlePowerReceiver.SETTING_ENABLED, "0");
        IdlePowerReceiver.updateSettings(context);
        assertEquals(UsageStatsManager.STANDBY_BUCKET_FREQUENT, bucket);
        assertEquals(AppOpsManager.MODE_FOREGROUND, (int) modes.get(OP));
        assertTrue(prefs.getAll().isEmpty());
    }

    @Test public void delayedApplyAfterWakeRestoresInsteadOfRestricting() throws Exception {
        when(power.isInteractive()).thenReturn(true);
        call("apply");
        assertEquals(UsageStatsManager.STANDBY_BUCKET_FREQUENT, bucket);
        assertEquals(AppOpsManager.MODE_FOREGROUND, (int) modes.get(OP));
        assertTrue(prefs.getAll().isEmpty());
    }

    private void handle(String action) throws Exception {
        Method method = IdlePowerReceiver.class.getDeclaredMethod("handle", Context.class, String.class);
        method.setAccessible(true);
        method.invoke(null, context, action);
    }

    @Test public void relayedScreenOnAloneKeepsJournal() throws Exception {
        when(power.isInteractive()).thenReturn(true);
        handle("org.petalos.power.action.SCREEN_ON");
        assertTrue(prefs.getBoolean("transaction_active", false));
        assertEquals(AppOpsManager.MODE_FOREGROUND, prefs.getInt("op:" + OP + ':' + NAME, -1));
    }

    @Test public void relayedUnlockRestoresJournal() throws Exception {
        when(power.isInteractive()).thenReturn(true);
        handle("org.petalos.power.action.USER_PRESENT");
        assertEquals(UsageStatsManager.STANDBY_BUCKET_FREQUENT, bucket);
        assertEquals(AppOpsManager.MODE_FOREGROUND, (int) modes.get(OP));
        assertFalse(prefs.contains("transaction_active"));
    }

    @Test public void screenStillOnRestoresButNotIfAlreadyOffAgain() throws Exception {
        handle("org.petalos.power.action.SCREEN_STILL_ON");
        assertTrue(prefs.getBoolean("transaction_active", false));
        when(power.isInteractive()).thenReturn(true);
        handle("org.petalos.power.action.SCREEN_STILL_ON");
        assertEquals(UsageStatsManager.STANDBY_BUCKET_FREQUENT, bucket);
        assertFalse(prefs.contains("transaction_active"));
    }

    @Test public void optimizedNeverLoosensAStricterBucket() throws Exception {
        prefs.edit().clear().commit();
        bucket = UsageStatsManager.STANDBY_BUCKET_RESTRICTED;
        Settings.System.putString(null, IdlePowerReceiver.SETTING_PROFILE, "1");
        call("apply");
        assertEquals(UsageStatsManager.STANDBY_BUCKET_RESTRICTED, bucket);
        verify(usage, never()).setAppStandbyBucket(anyString(), anyInt());
        assertFalse(prefs.contains("bucket:" + NAME));
    }

    @Test public void deepAlsoBlocksWakeLocksAndRestoresThem() throws Exception {
        prefs.edit().clear().commit();
        bucket = UsageStatsManager.STANDBY_BUCKET_FREQUENT;
        Settings.System.putString(null, IdlePowerReceiver.SETTING_PROFILE, "2");
        call("apply");
        assertEquals(AppOpsManager.MODE_IGNORED, (int) modes.get(AppOpsManager.OPSTR_WAKE_LOCK));
        call("restore");
        assertEquals(AppOpsManager.MODE_ALLOWED, (int) modes.get(AppOpsManager.OPSTR_WAKE_LOCK));
    }

    @Test public void deepFlagFollowsTheTransaction() throws Exception {
        prefs.edit().clear().commit();
        bucket = UsageStatsManager.STANDBY_BUCKET_FREQUENT;
        Settings.System.putString(null, IdlePowerReceiver.SETTING_PROFILE, "2");
        call("apply");
        assertEquals(1, Settings.Global.getInt(null, IdlePowerReceiver.GLOBAL_DEEP_ACTIVE, 0));
        call("restore");
        assertEquals(0, Settings.Global.getInt(null, IdlePowerReceiver.GLOBAL_DEEP_ACTIVE, 1));
    }

    @Test public void optimizedNeverRaisesTheDeepFlag() throws Exception {
        prefs.edit().clear().commit();
        bucket = UsageStatsManager.STANDBY_BUCKET_ACTIVE;
        Settings.System.putString(null, IdlePowerReceiver.SETTING_PROFILE, "1");
        call("apply");
        assertEquals(0, Settings.Global.getInt(null, IdlePowerReceiver.GLOBAL_DEEP_ACTIVE, 0));
    }

    @Test public void autoAddsNothingWhenKeeperLookupFails() throws Exception {
        when(usage.getAppStandbyBuckets()).thenReturn(Map.of("com.example.stale", 40));
        assertTrue(IdlePowerReceiver.autoPackages(context).isEmpty());
    }

    @Test public void unresolvedUidDoesNotGetRestricted() throws Exception {
        prefs.edit().clear().commit();
        when(context.getPackageManager().getPackagesForUid(10023)).thenReturn(null);
        call("apply");
        verify(usage, never()).setAppStandbyBucket(anyString(), anyInt());
        verify(ops, never()).setMode(anyString(), anyInt(), anyString(), anyInt());
    }

    @Test public void optionalIdlePolicyDoesNotScheduleAWakeupAlarm() throws Exception {
        AlarmManager alarms = mock(AlarmManager.class);
        PendingIntent pending = mock(PendingIntent.class);
        when(context.getSystemService(AlarmManager.class)).thenReturn(alarms);
        try (org.mockito.MockedStatic<PendingIntent> intents = mockStatic(PendingIntent.class)) {
            intents.when(() -> PendingIntent.getBroadcast(eq(context), eq(0),
                    any(Intent.class), anyInt())).thenReturn(pending);
            // seeded journal means already applied, start clean
            prefs.edit().clear().commit();
            call("schedule");
            verify(alarms).set(eq(AlarmManager.ELAPSED_REALTIME), anyLong(), eq(pending));
            verify(alarms, never()).setAndAllowWhileIdle(anyInt(), anyLong(), any());
        }
    }

    private static final class MemoryPrefs implements SharedPreferences {
        private final Map<String,Object> values = new HashMap<>();
        boolean failCommit;
        public synchronized Map<String,?> getAll(){ return new HashMap<>(values); }
        public synchronized String getString(String key,String fallback){ return (String) values.getOrDefault(key,fallback); }
        @SuppressWarnings("unchecked") public synchronized Set<String> getStringSet(String key,Set<String> fallback){ return (Set<String>)values.getOrDefault(key,fallback); }
        public synchronized int getInt(String key,int fallback){ return (int)values.getOrDefault(key,fallback); }
        public synchronized long getLong(String key,long fallback){ return (long)values.getOrDefault(key,fallback); }
        public synchronized float getFloat(String key,float fallback){ return (float)values.getOrDefault(key,fallback); }
        public synchronized boolean getBoolean(String key,boolean fallback){ return (boolean)values.getOrDefault(key,fallback); }
        public synchronized boolean contains(String key){ return values.containsKey(key); }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        public Editor edit(){ return new Editor(){
            final Map<String,Object> pending = new HashMap<>(); boolean clear;
            public Editor putString(String key,String value){pending.put(key,value);return this;}
            public Editor putStringSet(String key,Set<String> value){pending.put(key,value);return this;}
            public Editor putInt(String key,int value){pending.put(key,value);return this;}
            public Editor putLong(String key,long value){pending.put(key,value);return this;}
            public Editor putFloat(String key,float value){pending.put(key,value);return this;}
            public Editor putBoolean(String key,boolean value){pending.put(key,value);return this;}
            public Editor remove(String key){pending.put(key,null);return this;}
            public Editor clear(){clear=true;return this;}
            public boolean commit(){synchronized(MemoryPrefs.this){ if(clear)values.clear(); for(Map.Entry<String,Object> entry:pending.entrySet()){if(entry.getValue()==null)values.remove(entry.getKey());else values.put(entry.getKey(),entry.getValue());} return !failCommit;}}
            public void apply(){commit();}
        };}
    }
}
