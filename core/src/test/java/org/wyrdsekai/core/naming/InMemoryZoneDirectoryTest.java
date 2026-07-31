package org.wyrdsekai.core.naming;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryZoneDirectoryTest {

    private static final String DID_A =
        "did:wyrd:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";
    private static final String DID_B =
        "did:wyrd:z6MkszZtxCmA2Ce4vUV132PCuLQmwnaDD5mUcs8LU6CJr8ad";

    private static ZoneManifestV1 manifest(String did, String label, List<String> tags) {
        return new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, did, label, "Name", null,
            "tag", "desc", tags, null, null, null, 0,
            "2026-01-15T00:00:00Z", "2026-04-19T15:30:00Z", null);
    }

    @Test void lookup_emptyWhenUnpublished() {
        var dir = new InMemoryZoneDirectory();
        assertTrue(dir.lookup(DID_A).isEmpty());
    }

    @Test void publishAndLookup_roundTrip() {
        var dir = new InMemoryZoneDirectory();
        var m = manifest(DID_A, "kitchen", List.of("social"));
        dir.publish(m);
        assertEquals(m, dir.lookup(DID_A).orElseThrow());
    }

    @Test void publish_replacesExisting() {
        var dir = new InMemoryZoneDirectory();
        dir.publish(manifest(DID_A, "kitchen", List.of("social")));
        var m2 = manifest(DID_A, "kitchen", List.of("quiet"));
        dir.publish(m2);
        assertEquals(m2, dir.lookup(DID_A).orElseThrow());
    }

    @Test void unpublish_removes() {
        var dir = new InMemoryZoneDirectory();
        dir.publish(manifest(DID_A, "kitchen", List.of("social")));
        dir.unpublish(DID_A);
        assertTrue(dir.lookup(DID_A).isEmpty());
    }

    @Test void discoverByTag_matchesOnlyTaggedZones() {
        var dir = new InMemoryZoneDirectory();
        dir.publish(manifest(DID_A, "kitchen", List.of("social", "quiet")));
        dir.publish(manifest(DID_B, "garage", List.of("work")));
        assertEquals(List.of(DID_A), dir.discoverByTag("social"));
        assertEquals(List.of(DID_A), dir.discoverByTag("quiet"));
        assertEquals(List.of(DID_B), dir.discoverByTag("work"));
        assertTrue(dir.discoverByTag("nonexistent").isEmpty());
    }

    @Test void discoverByTag_reflectsRepublishedTags() {
        var dir = new InMemoryZoneDirectory();
        dir.publish(manifest(DID_A, "kitchen", List.of("social")));
        assertEquals(List.of(DID_A), dir.discoverByTag("social"));
        // Republish with different tags — old tag index must drop.
        dir.publish(manifest(DID_A, "kitchen", List.of("work")));
        assertTrue(dir.discoverByTag("social").isEmpty(),
            "old tag index must drop on republish");
        assertEquals(List.of(DID_A), dir.discoverByTag("work"));
    }

    @Test void discoverByTag_dropsAfterUnpublish() {
        var dir = new InMemoryZoneDirectory();
        dir.publish(manifest(DID_A, "kitchen", List.of("social")));
        dir.unpublish(DID_A);
        assertTrue(dir.discoverByTag("social").isEmpty());
    }

    @Test void publish_rejectsMalformedManifest() {
        var dir = new InMemoryZoneDirectory();
        var bad = manifest("not-a-did", "kitchen", List.of());
        assertThrows(IllegalStateException.class, () -> dir.publish(bad));
    }

    @Test void recent_respectsLimit() {
        var dir = new InMemoryZoneDirectory();
        dir.publish(manifest(DID_A, "kitchen", List.of()));
        dir.publish(manifest(DID_B, "garage", List.of()));
        assertEquals(2, dir.recent(10).size());
        assertEquals(1, dir.recent(1).size());
    }

    @Test void sizeMatchesPublishCount() {
        var dir = new InMemoryZoneDirectory();
        assertEquals(0, dir.size());
        dir.publish(manifest(DID_A, "kitchen", List.of()));
        assertEquals(1, dir.size());
        dir.publish(manifest(DID_B, "garage", List.of()));
        assertEquals(2, dir.size());
        dir.unpublish(DID_A);
        assertEquals(1, dir.size());
    }
}
