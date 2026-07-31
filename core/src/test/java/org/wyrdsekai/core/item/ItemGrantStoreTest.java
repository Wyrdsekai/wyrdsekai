package org.wyrdsekai.core.item;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ItemGrantStoreTest {

    @TempDir Path tmp;
    private ItemGrantStore store;

    @BeforeEach
    void setup() {
        var jdbcUrl = "jdbc:sqlite:" + tmp.resolve("grants.db");
        store = new ItemGrantStore(jdbcUrl);
        store.initSchema();
    }

    @Test
    void issue_then_list() {
        store.issue("research_clipper", "library.add", "did:wyrd:steward", null);
        var caps = store.capabilitiesFor("research_clipper");
        assertEquals(1, caps.size());
        assertTrue(caps.contains("library.add"));
    }

    @Test
    void issue_idempotent_via_upsert() {
        store.issue("clipper", "drive.mark", "did:wyrd:s", null);
        store.issue("clipper", "drive.mark", "did:wyrd:s", "{\"limit\":4}");
        var grants = store.listForItem("clipper");
        assertEquals(1, grants.size(), "second issue replaces, not appends");
        assertEquals("{\"limit\":4}", grants.getFirst().scopeJson());
    }

    @Test
    void revoke_single_capability() {
        store.issue("item_a", "library.add", "did:wyrd:s", null);
        store.issue("item_a", "drive.mark", "did:wyrd:s", null);
        assertTrue(store.revoke("item_a", "library.add"));
        var caps = store.capabilitiesFor("item_a");
        assertEquals(1, caps.size());
        assertTrue(caps.contains("drive.mark"));
    }

    @Test
    void revoke_nonexistent_returns_false() {
        assertFalse(store.revoke("ghost", "anything"));
    }

    @Test
    void revoke_all_clears_all_for_item() {
        store.issue("item_b", "library.add", "did:wyrd:s", null);
        store.issue("item_b", "drive.mark", "did:wyrd:s", null);
        store.issue("item_b", "memory.add", "did:wyrd:s", null);
        var removed = store.revokeAll("item_b");
        assertEquals(3, removed);
        assertTrue(store.capabilitiesFor("item_b").isEmpty());
    }

    @Test
    void list_for_item_returns_metadata() {
        store.issue("item_c", "library.add", "did:wyrd:steward1", "scope-json");
        var grants = store.listForItem("item_c");
        assertEquals(1, grants.size());
        var g = grants.getFirst();
        assertEquals("library.add", g.capability());
        assertEquals("did:wyrd:steward1", g.grantedByDid());
        assertEquals("scope-json", g.scopeJson());
        assertNotNull(g.grantedAt());
    }

    @Test
    void capabilities_for_nonexistent_item_empty() {
        assertTrue(store.capabilitiesFor("does-not-exist").isEmpty());
    }

    @Test
    void grants_segregated_by_item() {
        store.issue("item_x", "library.add", "did:wyrd:s", null);
        store.issue("item_y", "drive.mark", "did:wyrd:s", null);
        assertEquals(1, store.capabilitiesFor("item_x").size());
        assertEquals(1, store.capabilitiesFor("item_y").size());
        assertFalse(store.capabilitiesFor("item_x").contains("drive.mark"));
    }
}
