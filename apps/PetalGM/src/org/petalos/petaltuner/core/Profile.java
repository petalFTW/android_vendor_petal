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

public final class Profile {

    public final String key;
    public final String label;
    public final String cpuGovernor;
    public final boolean cpuMaxLock;
    public final String gpuGovernor;
    public final boolean gpuMaxLock;
    public final boolean schedulerBoost;
    public final boolean ioBurst;
    public final boolean thermalOverride;
    public final boolean reduceMemoryPressure;

    public Profile(String key, String label, String cpuGovernor, boolean cpuMaxLock,
            String gpuGovernor, boolean gpuMaxLock, boolean schedulerBoost, boolean ioBurst,
            boolean thermalOverride, boolean reduceMemoryPressure) {
        this.key = key;
        this.label = label;
        this.cpuGovernor = cpuGovernor;
        this.cpuMaxLock = cpuMaxLock;
        this.gpuGovernor = gpuGovernor;
        this.gpuMaxLock = gpuMaxLock;
        this.schedulerBoost = schedulerBoost;
        this.ioBurst = ioBurst;
        this.thermalOverride = thermalOverride;
        this.reduceMemoryPressure = reduceMemoryPressure;
    }
}
