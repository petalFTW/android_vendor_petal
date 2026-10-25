#!/usr/bin/env python3
# Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0
import pathlib
import subprocess
import tempfile

TOP = pathlib.Path(__file__).resolve().parents[4]
source = (TOP / "frameworks/base/packages/SystemUI/src/com/android/systemui/media/controls/ui/view/MediaHost.kt").read_text()
start = source.index("state.changedListener = {")
brace = source.index("{", start)
depth = 1
end = brace + 1
while depth:
    depth += (source[end] == "{") - (source[end] == "}")
    end += 1
callback = source[start:end]

harness = r'''
private enum class Style { Large, Mini, Split }
private data class State(var petalStyle: Style, var expansion: Float = 1f) {
    var changedListener: (() -> Unit)? = null
}
private class Manager {
    val mediaHostStates = mutableMapOf<Int, State>()
    var updates = 0
    fun updateHostState(location: Int, state: State) {
        mediaHostStates[location] = state.copy()
        updates++
    }
}
private class HostView(val beforeLayout: () -> Unit) {
    var requests = 0
    fun requestLayout() {
        beforeLayout()
        requests++
    }
}
private class Host {
    val state = State(Style.Large)
    val location = 1
    val mediaHostStatesManager = Manager()
    val hostView = HostView {
        check(mediaHostStatesManager.mediaHostStates[location]?.petalStyle == state.petalStyle) {
            "layout requested before the new measurements were published"
        }
    }
    init {
        mediaHostStatesManager.updateHostState(location, state)
        CALLBACK
    }
    fun style(value: Style) {
        state.petalStyle = value
        state.changedListener!!()
    }
    fun expansion(value: Float) {
        state.expansion = value
        state.changedListener!!()
    }
}
fun main() {
    val host = Host()
    host.style(Style.Mini)
    check(host.hostView.requests == 1) { "compact switch left a stale host height" }
    host.style(Style.Mini)
    check(host.hostView.requests == 1) { "unchanged style caused another layout" }
    repeat(60) { host.expansion(it / 60f) }
    check(host.hostView.requests == 1) { "expansion now forces a layout every frame" }
    check(host.mediaHostStatesManager.updates == 63) { "state updates were dropped" }
    host.style(Style.Large)
    check(host.hostView.requests == 2) { "large player did not reclaim its height" }
    host.style(Style.Split)
    check(host.hostView.requests == 3) { "split layout kept stale dimensions" }
    val other = Host()
    other.style(Style.Mini)
    check(other.hostView.requests == 1 && host.hostView.requests == 3) {
        "one host invalidated another host"
    }
    println("PASS: production MediaHost callback relayout, ordering, stable style, expansion and host isolation")
}
'''.replace("CALLBACK", callback)

with tempfile.TemporaryDirectory(prefix="qs-media-layout-", dir="/tmp/opencode") as temp:
    work = pathlib.Path(temp)
    kotlin = work / "HostRelayout.kt"
    jar = work / "host-relayout.jar"
    kotlin.write_text(harness)
    subprocess.run([str(TOP / "external/kotlinc/bin/kotlinc"), str(kotlin),
                    "-include-runtime", "-d", str(jar)], check=True)
    subprocess.run([str(TOP / "prebuilts/jdk/jdk21/linux-x86/bin/java"),
                    "-jar", str(jar)], check=True)
