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

package org.petalos.petaltuner.fragments;

import android.os.Build;
import android.os.Bundle;

import androidx.preference.Preference;

import org.petalos.petaltuner.R;
import org.petalos.petaltuner.core.DeviceSupport;

public final class AboutFragment extends BasePreferenceFragment {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        createScreen();

        category(R.string.category_about);

        Preference device = action(R.string.about_device_title, 0);
        device.setSummary(Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")");

        Preference soc = action(R.string.about_soc_title, 0);
        soc.setSummary(Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL + " · " + Build.HARDWARE);

        Preference kernel = action(R.string.about_kernel_title, 0);
        kernel.setSummary(DeviceSupport.kernel());

        Preference backend = action(R.string.about_health_title, 0);
        backend.setSummary(DeviceSupport.chargingNode() == null
                ? getString(R.string.bypass_unavailable)
                : DeviceSupport.chargingNode());

        Preference version = action(R.string.about_version_title, 0);
        version.setSummary("1.0");

        Preference credits = action(R.string.about_credit_title, R.string.about_credit_summary);
        credits.setSelectable(false);
    }
}
