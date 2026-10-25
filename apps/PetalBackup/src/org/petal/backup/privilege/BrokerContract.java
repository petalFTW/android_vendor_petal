/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petal.backup.privilege;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class BrokerContract {
    public static final int VERSION = 1;
    public static final String SERVICE = "petal_backup";
    public static final String PERMISSION = "org.petal.permission.MANAGE_BACKUPS";
    public static final String CLIENT_PACKAGE = "org.petal.backup";
    private static final Pattern PACKAGE = Pattern.compile(
            "(?:android|[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+)");

    public enum Component {
        APK(1), PRIVATE_CE(2), PRIVATE_DE(3), EXTERNAL_DATA(4), OBB(5), MEDIA(6), APP_SETTINGS(7);
        public final int wireId;
        Component(int wireId) { this.wireId = wireId; }
        public static Component fromWire(int id) {
            for (Component value : values()) if (value.wireId == id) return value;
            throw new IllegalArgumentException("Unknown backup component");
        }
    }

    public enum Direction {
        CAPTURE(1), RESTORE(2);
        public final int wireId;
        Direction(int wireId) { this.wireId = wireId; }
        public static Direction fromWire(int id) {
            for (Direction value : values()) if (value.wireId == id) return value;
            throw new IllegalArgumentException("Unknown backup direction");
        }
    }

    public enum State { AVAILABLE, UNAVAILABLE, NEEDS_UNLOCK, NEEDS_AUTHORISATION }
    public enum Reason {
        NONE, BROKER_NOT_INSTALLED, PROTOCOL_MISMATCH, USER_LOCKED, PROFILE_UNAVAILABLE,
        PACKAGE_NOT_FOUND, UNSUPPORTED_COMPONENT, PERMISSION_DENIED, PACKAGE_BUSY
    }

    public static final class Target {
        public final String packageName;
        public final int userId;

        public Target(String packageName, int userId) {
            if (packageName == null || packageName.length() > 255 || !PACKAGE.matcher(packageName).matches()) {
                throw new IllegalArgumentException("Expected a package identity, not a path");
            }
            if (userId < 0) throw new IllegalArgumentException("Expected an explicit Android user");
            this.packageName = packageName;
            this.userId = userId;
        }

        @Override public boolean equals(Object other) {
            return other instanceof Target target && userId == target.userId && packageName.equals(target.packageName);
        }

        @Override public int hashCode() { return Objects.hash(packageName, userId); }
    }

    public static final class Request {
        public final String operationId;
        public final Target target;
        public final Component component;
        public final Direction direction;

        public Request(String operationId, Target target, Component component, Direction direction) {
            if (operationId == null || !UUID.fromString(operationId).toString().equals(operationId)) {
                throw new IllegalArgumentException("Invalid backup operation identity");
            }
            this.operationId = operationId;
            this.target = Objects.requireNonNull(target);
            this.component = Objects.requireNonNull(component);
            this.direction = Objects.requireNonNull(direction);
        }
    }

    public static final class Capability {
        public final Request request;
        public final State state;
        public final Reason reason;

        public Capability(Request request, Reason reason) {
            this.request = Objects.requireNonNull(request);
            this.reason = Objects.requireNonNull(reason);
            state = switch (reason) {
                case NONE -> State.AVAILABLE;
                case USER_LOCKED -> State.NEEDS_UNLOCK;
                case PERMISSION_DENIED -> State.NEEDS_AUTHORISATION;
                case BROKER_NOT_INSTALLED, PROTOCOL_MISMATCH, PROFILE_UNAVAILABLE, PACKAGE_NOT_FOUND,
                        UNSUPPORTED_COMPONENT, PACKAGE_BUSY -> State.UNAVAILABLE;
            };
        }
    }

    public static Reason connectionFailure(Integer remoteVersion) {
        // A privileged APK isn't proof that the bloody service exists.
        return remoteVersion == null ? Reason.BROKER_NOT_INSTALLED
                : remoteVersion != VERSION ? Reason.PROTOCOL_MISMATCH : Reason.NONE;
    }

    private BrokerContract() {}
}
