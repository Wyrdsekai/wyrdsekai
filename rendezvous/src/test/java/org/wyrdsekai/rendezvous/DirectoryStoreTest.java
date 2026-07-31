package org.wyrdsekai.rendezvous;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.naming.ZoneManifestV1;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DirectoryStoreTest {

    private static final String DID_A =
        "did:wyrd:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";
    private static final String DID_B =
        "did:wyrd:z6MkszZtxCmA2Ce4vUV132PCuLQmwnaDD5mUcs8LU6CJr8ad";

    private static ZoneManifestV1 manifest(String did, String label, List<String> tags,
                                            ZoneManifestV1.Capabilities caps) {
        return new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, did, label, "Display", null,
            "tag", "desc", tags, caps, null, null, 0,
            "2026-01-15T00:00:00Z", "2026-04-19T15:30:00Z", null);
    }

    private static ZoneManifestV1 manifest(String did, String label, List<String> tags) {
        return manifest(did, label, tags, null);
    }

    @Test void publish_lookup_roundTrip() {
        var store = new DirectoryStore(1000, 3600);
        store.publish(manifest(DID_A, "kitchen", List.of()));
        assertEquals(DID_A, store.lookup(DID_A).orElseThrow().did());
        assertEquals(1, store.size());
    }

    @Test void publish_replacesExisting() {
        var store = new DirectoryStore(1000, 3600);
        store.publish(manifest(DID_A, "kitchen", List.of("social")));
        store.publish(manifest(DID_A, "kitchen", List.of("quiet")));
        // Old tag should be gone.
        assertTrue(store.discoverByTag("social").isEmpty());
        assertEquals(List.of(DID_A), store.discoverByTag("quiet"));
        assertEquals(1, store.size());
    }

    @Test void discoverByTag_isolatesByKey() {
        var store = new DirectoryStore(1000, 3600);
        store.publish(manifest(DID_A, "kitchen", List.of("social")));
        store.publish(manifest(DID_B, "garage", List.of("work")));
        assertEquals(List.of(DID_A), store.discoverByTag("social"));
        assertEquals(List.of(DID_B), store.discoverByTag("work"));
        assertTrue(store.discoverByTag("nonexistent").isEmpty());
    }

    @Test void discoverByCapability_indexedByRoomAgentSkill() {
        var caps = new ZoneManifestV1.Capabilities(
            null,
            List.of(new ZoneManifestV1.PublicRoom("library", "The Library", "Books.")),
            List.of(new ZoneManifestV1.PublicAgent("kettle", "Kettle", "companion",
                "Helpful.", List.of("recipe-lookup", "meal-planning"))),
            Map.of(), Map.of());
        var store = new DirectoryStore(1000, 3600);
        store.publish(manifest(DID_A, "kitchen", List.of(), caps));

        // Room label.
        assertEquals(List.of(DID_A), store.discoverByCapability("library"));
        // Agent label.
        assertEquals(List.of(DID_A), store.discoverByCapability("kettle"));
        // Agent role.
        assertEquals(List.of(DID_A), store.discoverByCapability("companion"));
        // Agent skill.
        assertEquals(List.of(DID_A), store.discoverByCapability("recipe-lookup"));
        assertEquals(List.of(DID_A), store.discoverByCapability("meal-planning"));
        // Case insensitive.
        assertEquals(List.of(DID_A), store.discoverByCapability("LIBRARY"));
    }

    @Test void unpublish_dropsFromAllIndexes() {
        var caps = new ZoneManifestV1.Capabilities(
            null,
            List.of(new ZoneManifestV1.PublicRoom("library", "The Library", "Books.")),
            List.of(), Map.of(), Map.of());
        var store = new DirectoryStore(1000, 3600);
        store.publish(manifest(DID_A, "kitchen", List.of("social"), caps));

        store.unpublish(DID_A);
        assertTrue(store.lookup(DID_A).isEmpty());
        assertTrue(store.discoverByTag("social").isEmpty());
        assertTrue(store.discoverByCapability("library").isEmpty());
    }

    @Test void lruEviction_whenAtCap() {
        var store = new DirectoryStore(2, 3600);  // cap=2
        store.publish(manifest(DID_A, "kitchen", List.of()));
        // Small sleep to ensure distinct lastSeenAt values.
        try { Thread.sleep(10); } catch (InterruptedException ignore) {}
        store.publish(manifest(DID_B, "garage", List.of()));
        var DID_C = "did:wyrd:z6MkNew1111111111111111111111111111111111";
        try { Thread.sleep(10); } catch (InterruptedException ignore) {}
        store.publish(manifest(DID_C, "office", List.of()));

        // DID_A was oldest — it should have been evicted to make room.
        assertEquals(2, store.size());
        assertTrue(store.lookup(DID_A).isEmpty(),
            "oldest entry evicted when at cap");
        assertTrue(store.lookup(DID_B).isPresent());
        assertTrue(store.lookup(DID_C).isPresent());
    }

    @Test void ttlEviction_dropsStale() throws InterruptedException {
        // Use a 1-second TTL so we can actually test expiry.
        var store = new DirectoryStore(1000, 1);
        store.publish(manifest(DID_A, "kitchen", List.of()));
        assertEquals(1, store.size());

        Thread.sleep(1100);
        store.evictExpired();

        assertEquals(0, store.size());
        assertTrue(store.lookup(DID_A).isEmpty());
    }

    @Test void searchText_scoresAndRanks() {
        var kitchen = new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, DID_A, "kitchen", "Alice Kitchen", null,
            "afternoon tea and crafts",
            "a warm space for cooking with friends",
            List.of("social", "crafts"),
            null, null, null, 0,
            "2026-01-15T00:00:00Z", "2026-04-19T15:30:00Z", null);
        var garage = new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, DID_B, "garage", "Bob Garage", null,
            "woodworking", "tools and workbench",
            List.of("work"),
            null, null, null, 0,
            "2026-01-15T00:00:00Z", "2026-04-19T15:30:00Z", null);

        var store = new DirectoryStore(1000, 3600);
        store.publish(kitchen);
        store.publish(garage);

        var hits = store.searchText("crafts", 10);
        assertEquals(1, hits.size());
        assertEquals(DID_A, hits.get(0).manifest().did());

        // Search tagline content.
        var tea = store.searchText("tea", 10);
        assertEquals(DID_A, tea.get(0).manifest().did());

        // Empty query returns empty.
        assertTrue(store.searchText("", 10).isEmpty());
        assertTrue(store.searchText(null, 10).isEmpty());
    }

    @Test void recent_newestFirst() {
        var m1 = new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, DID_A, "kitchen", "K", null,
            "t", "d", List.of(), null, null, null, 0,
            "2026-01-15T00:00:00Z", "2026-04-10T00:00:00Z", null);
        var m2 = new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, DID_B, "garage", "G", null,
            "t", "d", List.of(), null, null, null, 0,
            "2026-01-15T00:00:00Z", "2026-04-19T00:00:00Z", null);

        var store = new DirectoryStore(1000, 3600);
        store.publish(m1);
        store.publish(m2);
        var list = store.recent(10);
        assertEquals(2, list.size());
        assertEquals(DID_B, list.get(0).did(), "newest refreshedAt first");
    }

    @Test void recent_respectsLimit() {
        var store = new DirectoryStore(1000, 3600);
        store.publish(manifest(DID_A, "kitchen", List.of()));
        store.publish(manifest(DID_B, "garage", List.of()));
        assertEquals(1, store.recent(1).size());
    }
}
