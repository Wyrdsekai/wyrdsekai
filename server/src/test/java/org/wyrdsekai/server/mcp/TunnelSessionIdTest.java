package org.wyrdsekai.server.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit F1 residual (2026-07-25): the tunnel session id is a CAPABILITY.
 * Household phones share one relay NATS account and static NATS ACLs cannot
 * express "only the sessions you own", so an id that is guessable — or that
 * the zone accepts in any shape at all — lets a sibling device inject `.up`
 * frames into someone else's session or read their `.down` stream. Clients
 * mint 128 CSPRNG bits; the zone refuses anything that isn't plausibly one.
 */
class TunnelSessionIdTest {

    @Test
    @DisplayName("accepts a client-minted 128-bit hex session id")
    void acceptsRealSessionIds() {
        assertTrue(TunnelSessionHandler.isWellFormedSession("0f8a1c2d3e4b5a69708192a3b4c5d6e7"));
        assertTrue(TunnelSessionHandler.isWellFormedSession("A1B2C3D4E5F60718293A4B5C6D7E8F90"));
        // Hyphen/underscore stay legal — older builds and other clients may use them.
        assertTrue(TunnelSessionHandler.isWellFormedSession("session-with_separators-01"));
    }

    @Test
    @DisplayName("rejects short/low-entropy ids (the pre-fix millis-hex form)")
    void rejectsShortIds() {
        assertFalse(TunnelSessionHandler.isWellFormedSession("197c2f3a1b-9f2e"), "old 15-char id");
        assertFalse(TunnelSessionHandler.isWellFormedSession("abc"));
        assertFalse(TunnelSessionHandler.isWellFormedSession(""));
        assertFalse(TunnelSessionHandler.isWellFormedSession(null));
    }

    @Test
    @DisplayName("rejects oversized ids so a flood can't grow the maps with long keys")
    void rejectsOversizedIds() {
        assertFalse(TunnelSessionHandler.isWellFormedSession("a".repeat(65)));
        assertTrue(TunnelSessionHandler.isWellFormedSession("a".repeat(64)));
    }

    @Test
    @DisplayName("rejects ids carrying subject/log-smuggling characters")
    void rejectsOddCharacters() {
        assertFalse(TunnelSessionHandler.isWellFormedSession("0f8a1c2d3e4b5a69708192a3b4c5d6e7*"));
        assertFalse(TunnelSessionHandler.isWellFormedSession("0f8a1c2d3e4b5a697081.92a3b4c5d6e7"));
        assertFalse(TunnelSessionHandler.isWellFormedSession("0f8a1c2d3e4b5a6970819 2a3b4c5d6e7"));
        assertFalse(TunnelSessionHandler.isWellFormedSession("0f8a1c2d3e4b5a6970819\n2a3b4c5d6e7"));
    }

    @Test
    @DisplayName("live-session cap is bounded")
    void sessionCapIsBounded() {
        assertTrue(TunnelSessionHandler.MAX_LIVE_SESSIONS > 0
            && TunnelSessionHandler.MAX_LIVE_SESSIONS <= 1024);
    }
}
