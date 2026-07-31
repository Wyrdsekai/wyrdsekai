package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.room.TheSafe;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #31 item 1 (post-restart verify 2137ea49) — the 4-surfaces Safe-binding bug
 * class again: the companion-side provider got {@code setSafe(TheSafe.local())}
 * (second-node #26) and the catalog got all four surfaces, but the PLAYER provider
 * (VisitorItemProvider / HomeOwnerItemProvider via
 * WyrdWebSocket.buildPlayerProvider) never did — {@code world.safe.list/has}
 * answered empty on player-invoked items even with slots stored.
 */
class VisitorItemProviderSafeTest {

    private static final byte[] KEY =
        "test-node-identity-seed-32bytes!".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        TheSafe.resetLocalForTests();
    }

    @Test
    void unwiredProviderKeepsEmptyDenyDefaults() {
        var provider = new VisitorItemProvider("alpha", "alpha");
        assertTrue(provider.safeListSlots().isEmpty());
        assertFalse(provider.safeHas("openweathermap.api_key"));
        assertEquals("safe_not_wired", provider.safeSet("x", "y").get("error"));
        assertEquals("safe_not_wired", provider.safeDelete("x").get("error"));
    }

    @Test
    void wiredProviderSeesStoredSlots() {
        var safe = TheSafe.initLocal(tempDir.resolve("credentials.safe"), KEY);
        safe.storeSlot("openweathermap.api_key", "k-123");

        var provider = new VisitorItemProvider("alpha", "alpha");
        provider.setSafe(safe);

        assertTrue(provider.safeHas("openweathermap.api_key"),
            "world.safe.has must see a stored slot once the safe is wired");
        assertTrue(provider.safeListSlots().contains("openweathermap.api_key"),
            "world.safe.list must include stored slot ids");
        assertFalse(provider.safeHas("missing.slot"));
        assertFalse(provider.safeHas(null));
    }

    @Test
    void homeOwnerProviderInheritsSafeWiring() {
        var safe = TheSafe.initLocal(tempDir.resolve("credentials.safe"), KEY);
        safe.storeSlot("maps.api_key", "abc");

        // HomeOwnerItemProvider extends VisitorItemProvider — the same setSafe
        // covers the homeClient-wired path of buildPlayerProvider. Constructed
        // with null services: only the inherited Safe surface is exercised.
        var provider = new HomeOwnerItemProvider("alpha", "alpha", "did:test:owner", null, null);
        provider.setSafe(safe);
        assertTrue(provider.safeHas("maps.api_key"));
        assertTrue(provider.safeListSlots().contains("maps.api_key"));
    }

    @Test
    void writesStayOnKeychestPathWhenWired() {
        var safe = TheSafe.initLocal(tempDir.resolve("credentials.safe"), KEY);
        var provider = new VisitorItemProvider("alpha", "alpha");
        provider.setSafe(safe);
        // Same policy as the full provider: script surface never writes.
        assertEquals("use_mcp_keychest_for_writes", provider.safeSet("a", "b").get("error"));
        assertEquals("use_mcp_keychest_for_deletes", provider.safeDelete("a").get("error"));
    }
}
