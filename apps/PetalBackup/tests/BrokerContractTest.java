/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petal.backup.tests;

import java.util.UUID;
import org.petal.backup.privilege.BrokerContract;
import org.petal.backup.privilege.BrokerContract.Component;
import org.petal.backup.privilege.BrokerContract.Direction;
import org.petal.backup.privilege.BrokerContract.Reason;
import org.petal.backup.privilege.BrokerContract.State;
import org.petal.backup.privilege.BrokerContract.Target;

public final class BrokerContractTest {
    private static int checks;
    private interface Action { void run(); }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void rejects(Action action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected invalid broker request to be rejected");
    }

    public static void run() {
        Target owner = new Target("org.petal.fixture", 0);
        require(owner.equals(new Target("org.petal.fixture", 0)), "Same package and user identify the same target");
        require(!owner.equals(new Target("org.petal.fixture", 10)), "Android users are distinct targets");
        for (String path : new String[] {"../org.petal.fixture", "/data/user/0/org.petal.fixture", "org.petal;id",
                "org.petal fixture", "org..fixture", "org.petal.\nfixture", "org.petal\\fixture"}) {
            rejects(() -> new Target(path, 0));
        }
        rejects(() -> new Target("org.petal.fixture", -1));
        rejects(() -> new BrokerContract.Request("../operation", owner, Component.PRIVATE_CE, Direction.CAPTURE));
        rejects(() -> Component.fromWire(0));
        rejects(() -> Direction.fromWire(99));
        for (Component component : Component.values()) require(Component.fromWire(component.wireId) == component, "Stable component wire identity");
        BrokerContract.Request request = new BrokerContract.Request(UUID.randomUUID().toString(), owner,
                Component.PRIVATE_CE, Direction.CAPTURE);
        require(BrokerContract.connectionFailure(null) == Reason.BROKER_NOT_INSTALLED, "Missing service never claims access");
        require(BrokerContract.connectionFailure(2) == Reason.PROTOCOL_MISMATCH, "Reject unsupported protocol");
        require(new BrokerContract.Capability(request, Reason.USER_LOCKED).state == State.NEEDS_UNLOCK, "Locked CE storage waits for unlock");
        require(new BrokerContract.Capability(request, Reason.PERMISSION_DENIED).state == State.NEEDS_AUTHORISATION, "Permission failure is explicit");
        require(new BrokerContract.Capability(request, Reason.NONE).state == State.AVAILABLE, "Successful per-operation check is available");
        System.out.println("PASS: " + checks + " broker-contract checks — typed targets, users, wire IDs and unavailable states.");
    }
}
