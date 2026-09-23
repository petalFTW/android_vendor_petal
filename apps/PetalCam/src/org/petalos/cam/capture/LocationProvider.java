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

package org.petalos.cam.capture;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.List;

/** Last-known GPS fix for geotagging, with graceful permission/absence handling. */
public final class LocationProvider {

    private static final String TAG = "PetalCamLocation";

    private LocationProvider() {
    }

    @Nullable
    public static double[] lastKnown(Context context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context,
                        Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        try {
            LocationManager lm = (LocationManager)
                    context.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                return null;
            }
            Location best = null;
            List<String> providers = lm.getProviders(true);
            for (String provider : providers) {
                Location l = lm.getLastKnownLocation(provider);
                if (l == null) {
                    continue;
                }
                if (best == null || l.getTime() > best.getTime()) {
                    best = l;
                }
            }
            if (best == null) {
                return null;
            }
            return new double[]{best.getLatitude(), best.getLongitude()};
        } catch (RuntimeException e) {
            Log.d(TAG, "Location unavailable", e);
            return null;
        }
    }
}
