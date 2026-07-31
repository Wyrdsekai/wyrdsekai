package org.wyrdsekai.core.naming;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CompositeZoneDirectoryTest {

    private static final String DID_A =
        "did:wyrd:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";
    private static final String DID_B =
        "did:wyrd:z6MkszZtxCmA2Ce4vUV132PCuLQmwnaDD5mUcs8LU6CJr8ad";

    private static ZoneManifestV1 manifest(String did, String label, String refreshed,
                                            String signature, List<String> tags) {
        return new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, did, label, "Name", null,
            "tag", "desc", tags, null, null, null, 0,
            "2026-01-15T00:00:00Z", refreshed, signature);
    }

    @Test void requires_atLeastOneBackend() {
        assertThrows(IllegalArgumentException.class,
            () -> new CompositeZoneDirectory(List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new CompositeZoneDirectory(null));
    }

    @Test void lookup_newerRefreshedAtWins() {
        var older = new InMemoryZoneDirectory();
        older.publish(manifest(DID_A, "kitchen", "2026-01-01T00:00:00Z", null, List.of()));
        var newer = new InMemoryZoneDirectory();
        newer.publish(manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", null, List.of()));

        var composite = new CompositeZoneDirectory(List.of(older, newer));
        var m = composite.lookup(DID_A).orElseThrow();
        assertEquals("2026-04-19T00:00:00Z", m.refreshedAt());
    }

    @Test void lookup_signedWinsOverUnsignedOnTie() {
        var unsigned = new InMemoryZoneDirectory();
        unsigned.publish(manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", null, List.of()));
        var signed = new InMemoryZoneDirectory();
        signed.publish(manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", "ed25519:abc", List.of()));

        var composite = new CompositeZoneDirectory(List.of(unsigned, signed));
        var m = composite.lookup(DID_A).orElseThrow();
        assertEquals("ed25519:abc", m.signature());
    }

    @Test void lookup_emptyAcrossAllBackends_returnsEmpty() {
        var a = new InMemoryZoneDirectory();
        var b = new InMemoryZoneDirectory();
        var composite = new CompositeZoneDirectory(List.of(a, b));
        assertTrue(composite.lookup(DID_A).isEmpty());
    }

    @Test void publish_fansOutToAll() {
        var a = new InMemoryZoneDirectory();
        var b = new InMemoryZoneDirectory();
        var composite = new CompositeZoneDirectory(List.of(a, b));
        composite.publish(manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", null, List.of()));
        assertTrue(a.lookup(DID_A).isPresent());
        assertTrue(b.lookup(DID_A).isPresent());
    }

    @Test void unpublish_fansOutToAll() {
        var a = new InMemoryZoneDirectory();
        var b = new InMemoryZoneDirectory();
        a.publish(manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", null, List.of()));
        b.publish(manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", null, List.of()));
        var composite = new CompositeZoneDirectory(List.of(a, b));
        composite.unpublish(DID_A);
        assertTrue(a.lookup(DID_A).isEmpty());
        assertTrue(b.lookup(DID_A).isEmpty());
    }

    @Test void discoverByTag_unionsAcrossBackends() {
        var a = new InMemoryZoneDirectory();
        a.publish(manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", null, List.of("social")));
        var b = new InMemoryZoneDirectory();
        b.publish(manifest(DID_B, "garage", "2026-04-19T00:00:00Z", null, List.of("social")));

        var composite = new CompositeZoneDirectory(List.of(a, b));
        var dids = composite.discoverByTag("social");
        assertTrue(dids.contains(DID_A));
        assertTrue(dids.contains(DID_B));
        assertEquals(2, dids.size());
    }

    @Test void discoverByTag_dedupesSameDidAcrossBackends() {
        var a = new InMemoryZoneDirectory();
        a.publish(manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", null, List.of("social")));
        var b = new InMemoryZoneDirectory();
        b.publish(manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", null, List.of("social")));

        var composite = new CompositeZoneDirectory(List.of(a, b));
        assertEquals(1, composite.discoverByTag("social").size());
    }

    @Test void recent_dedupesAndPrefersNewer() {
        var old = new InMemoryZoneDirectory();
        old.publish(manifest(DID_A, "kitchen", "2026-01-01T00:00:00Z", null, List.of()));
        var fresh = new InMemoryZoneDirectory();
        fresh.publish(manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", null, List.of()));
        fresh.publish(manifest(DID_B, "garage", "2026-04-18T00:00:00Z", null, List.of()));

        var composite = new CompositeZoneDirectory(List.of(old, fresh));
        var all = composite.recent(10);
        assertEquals(2, all.size(), "dedupes DID_A across backends");
        assertEquals("2026-04-19T00:00:00Z", all.get(0).refreshedAt(), "newest first");
        assertEquals(DID_A, all.get(0).did());
    }

    @Test void recent_respectsLimit() {
        var a = new InMemoryZoneDirectory();
        a.publish(manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", null, List.of()));
        a.publish(manifest(DID_B, "garage", "2026-04-18T00:00:00Z", null, List.of()));

        var composite = new CompositeZoneDirectory(List.of(a));
        assertEquals(1, composite.recent(1).size());
    }

    @Test void backend_throwing_doesNotBlockOthers() {
        // A misbehaving backend must not take down the composite.
        var throwing = new ZoneDirectory() {
            @Override public void publish(ZoneManifestV1 m) { throw new RuntimeException("boom"); }
            @Override public void unpublish(String did) { throw new RuntimeException("boom"); }
            @Override public Optional<ZoneManifestV1> lookup(String d) { throw new RuntimeException("boom"); }
            @Override public List<String> discoverByTag(String t) { throw new RuntimeException("boom"); }
            @Override public List<ZoneManifestV1> recent(int n) { throw new RuntimeException("boom"); }
        };
        var healthy = new InMemoryZoneDirectory();
        healthy.publish(manifest(DID_A, "kitchen", "2026-04-19T00:00:00Z", null, List.of("x")));

        var composite = new CompositeZoneDirectory(List.of(throwing, healthy));
        // All methods should still return usable results, sourced from healthy.
        composite.publish(manifest(DID_B, "garage", "2026-04-19T00:00:00Z", null, List.of()));
        assertTrue(composite.lookup(DID_A).isPresent());
        assertEquals(List.of(DID_A), composite.discoverByTag("x"));
        assertFalse(composite.recent(10).isEmpty());
    }

    @Test void backendNames_returnsConfigured() {
        var a = new InMemoryZoneDirectory();
        var composite = new CompositeZoneDirectory(List.of(a));
        assertEquals(List.of("InMemoryZoneDirectory"), composite.backendNames());
        assertEquals(1, composite.backendCount());
    }
}
