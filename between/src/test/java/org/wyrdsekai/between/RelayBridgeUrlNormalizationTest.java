package org.wyrdsekai.between;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the same-server detection that prevents {@link RelayBridge} from
 * setting up a publish→echo loop when local NATS and relay NATS happen to
 * resolve to the same endpoint (a misconfiguration that has bitten us live —
 * see the federation feedback-loop incidents).
 *
 * The helper is deliberately NOT DNS-aware: two different hostnames pointing
 * at the same box are treated as distinct (safer default — the bridge still
 * runs in that case, which is correct when operators chose distinct names).
 */
class RelayBridgeUrlNormalizationTest {

    @Test
    void identicalUrlsResolveSame() {
        assertTrue(RelayBridge.urlsResolveSame(
            "nats://relay-node:4222", "nats://relay-node:4222"));
    }

    @Test
    void schemeStrippedBeforeCompare() {
        assertTrue(RelayBridge.urlsResolveSame(
            "nats://relay-node:4222", "relay-node:4222"));
    }

    @Test
    void defaultPortApplied() {
        assertTrue(RelayBridge.urlsResolveSame(
            "nats://relay-node", "nats://relay-node:4222"));
    }

    @Test
    void caseInsensitiveHost() {
        assertTrue(RelayBridge.urlsResolveSame(
            "nats://Relay-Node:4222", "nats://relay-node:4222"));
    }

    @Test
    void differentHostsNotSame() {
        assertFalse(RelayBridge.urlsResolveSame(
            "nats://127.0.0.1:4222", "nats://relay-node:4222"));
    }

    @Test
    void differentPortsNotSame() {
        assertFalse(RelayBridge.urlsResolveSame(
            "nats://relay-node:4222", "nats://relay-node:4333"));
    }

    @Test
    void nullSafe() {
        assertFalse(RelayBridge.urlsResolveSame(null, "nats://relay-node:4222"));
        assertFalse(RelayBridge.urlsResolveSame("nats://relay-node:4222", null));
        assertFalse(RelayBridge.urlsResolveSame(null, null));
    }

    @Test
    void userPasswordPrefixIgnored() {
        assertTrue(RelayBridge.urlsResolveSame(
            "nats://user:pw@relay-node:4222", "nats://relay-node:4222"));
    }
}
