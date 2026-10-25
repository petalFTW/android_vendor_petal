/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.agever;

public enum SessionState {
    OFF(R.string.state_off), STARTING(R.string.state_starting),
    PREVIEW_READY(R.string.state_preview), CAMERA_STARTING(R.string.state_camera_starting),
    CAMERA_READY(R.string.state_camera_ready), CAMERA_BLOCKED(R.string.state_camera_blocked),
    STOPPING(R.string.state_stopping), ERROR(R.string.state_error);

    public final int label;
    SessionState(int label) { this.label = label; }
}
