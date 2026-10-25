#!/usr/bin/env bash
# Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0
set -euo pipefail
TOP="$(realpath "$(dirname "$0")/../../../../..")"
HERE="$TOP/vendor/petal/power/tests/host"
WORK="$(mktemp -d /tmp/opencode/petal-spoof-tests.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
JDK="$TOP/prebuilts/jdk/jdk21/linux-x86/bin"
ANDROID16="$TOP/prebuilts/misc/common/robolectric/android-all/android-all-16-robolectric-13921718.jar"
ANDROID13="$TOP/prebuilts/misc/common/robolectric/android-all/android-all-13-robolectric-9030017.jar"
JUNIT="$TOP/prebuilts/devtools/tools/lib/junit-4.12.jar"
HAMCREST="$TOP/prebuilts/devtools/tools/lib/hamcrest-core-1.3.jar"
M2="$TOP/prebuilts/tools/common/m2/repository"
MOCKITO="$M2/org/mockito/mockito-core/4.8.1/mockito-core-4.8.1.jar"
INLINE="$M2/org/mockito/mockito-inline/4.8.1/mockito-inline-4.8.1.jar"
BUDDY="$M2/net/bytebuddy/byte-buddy/1.14.18/byte-buddy-1.14.18.jar"
AGENT="$M2/net/bytebuddy/byte-buddy-agent/1.14.18/byte-buddy-agent-1.14.18.jar"
OBJENESIS="$TOP/prebuilts/devtools/tools/lib/objenesis-2.1.jar"
CORETESTS="$TOP/frameworks/base/core/tests/coretests/src/com/android/internal/app"

"$JDK/javac" -d "$WORK" -cp "$ANDROID16:$JUNIT:$MOCKITO" \
    "$HERE/fixtures/android/provider/Settings.java" \
    "$HERE/fixtures/android/app/PetalStateOverrides.java" \
    "$HERE/fixtures/android/app/compat/gms/GmsCompat.java" \
    "$HERE/fixtures/android/app/AppOpsManager.java" \
    "$HERE/fixtures/android/os/SystemClock.java" \
    "$HERE/fixtures/android/util/Log.java" \
    "$TOP/frameworks/base/core/java/com/android/internal/app/PetalSpoofConfig.java" \
    "$CORETESTS/PetalSpoofConfigTest.java" "$CORETESTS/PetalSpoofIdentityTest.java" \
    "$TOP/vendor/petal/power/src/org/petalos/power/IdlePowerReceiver.java" \
    "$TOP/vendor/petal/power/src/org/petalos/power/IdlePowerProtection.java" \
    "$TOP/vendor/petal/power/src/org/petalos/power/NetworkPolicyReceiver.java" \
    "$TOP/vendor/petal/power/src/org/petalos/power/SpoofPolicyReceiver.java" \
    "$HERE/IdlePowerJournalTest.java"

"$JDK/java" -cp "$WORK:$ANDROID16:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore \
    com.android.internal.app.PetalSpoofConfigTest
# Android 13's host model avoids Android 16's native-only PackageManager cache initialization.
# Only the actual new policy and journal code is under test; Android services are test doubles.
"$JDK/java" -XX:+EnableDynamicAgentLoading -Djdk.attach.allowAttachSelf=true \
    -cp "$WORK:$ANDROID13:$JUNIT:$HAMCREST:$MOCKITO:$INLINE:$BUDDY:$AGENT:$OBJENESIS" \
    org.junit.runner.JUnitCore com.android.internal.app.PetalSpoofIdentityTest \
    org.petalos.power.IdlePowerJournalTest
