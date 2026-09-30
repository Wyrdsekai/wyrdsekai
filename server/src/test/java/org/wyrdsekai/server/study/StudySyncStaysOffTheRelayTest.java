package org.wyrdsekai.server.study;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.Connection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit W4 (2026-09-28): Study sync ran on the relay connection too, and the home's deltas
 * (Study items, private journal entries decrypted) and the phones' tokens crossed the relay
 * as plain JSON. On a relay leg the peer now only tells a device, once, that Study sync runs
 * on the home network; it merges nothing and sends no Study content.
 */
class StudySyncStaysOffTheRelayTest {

    private static final ObjectMapper M = new ObjectMapper();

    record Published(String subject, String body) {}

    /** A NATS connection that records what is published on it and does nothing else. */
    private static Connection recording(List<Published> out) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("publish") && args != null && args.length == 2
                        && args[0] instanceof String s && args[1] instanceof byte[] b) {
                    out.add(new Published(s, new String(b, StandardCharsets.UTF_8)));
                }
                return null;
            });
    }

    @Test
    @DisplayName("on the relay a study_state is answered with a plain refusal, once per device, and nothing else")
    void relayLegRefuses() throws Exception {
        var out = new ArrayList<Published>();
        var peer = StudySyncPeer.homeNetworkOnly(recording(out), "zone1", "srv-zone1");
        var state = """
            {"type":"study_state","deviceId":"phone-A","userDid":"did:key:alice",
             "token":"session-secret","clockSummary":{"phone-A":2}}""".getBytes(StandardCharsets.UTF_8);

        peer.onFrame("between.zone1.phone-A.srv-zone1.study.state", state);
        peer.onFrame("between.zone1.phone-A.srv-zone1.study.state", state);

        assertEquals(1, out.size(), "told once, not every time it asks");
        var notice = M.readTree(out.getFirst().body());
        assertEquals("between.zone1.srv-zone1.phone-A.study.sync", out.getFirst().subject());
        assertEquals("study_sync_refused", notice.path("type").asText());
        assertEquals("home_network_only", notice.path("reason").asText());
        assertFalse(notice.has("items"), "no Study content on the relay");
        assertFalse(out.getFirst().body().contains("session-secret"), "nothing the device sent is echoed");
    }

    @Test
    @DisplayName("a delta sent over the relay is not merged")
    void relayLegMergesNothing() {
        var out = new ArrayList<Published>();
        // No StudyService at all: a merge attempt would throw.
        var peer = StudySyncPeer.homeNetworkOnly(recording(out), "zone1", "srv-zone1");
        var delta = """
            {"type":"study_delta","deviceId":"phone-B","userDid":"did:key:bob","token":"t",
             "items":[{"id":"n1","userDid":"did:key:bob","itemType":"note","title":"","content":"x",
                       "collection":"notes","timestamp":1,"version":1,"vectorClock":{"phone-B":1},"deleted":false}]}"""
            .getBytes(StandardCharsets.UTF_8);
        peer.onFrame("between.zone1.phone-B.srv-zone1.study.sync", delta);
        assertEquals(1, out.size());
        assertTrue(out.getFirst().body().contains("study_sync_refused"));
    }
}
