package org.wyrdsekai.core.naming;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the script-facing directory surface that {@code atrium.js}
 * hits through {@code world.discoverZones()}. {@link ZoneDirectoryService#renderDiscover}
 * is the single contract — same JSON shape regardless of backend —
 * so a focused unit test here also covers the in-world Atrium room's
 * data path without needing to spin up Pekko + ScriptLoader.
 */
class ZoneDirectoryServiceTest {

    private static final String DID_A =
        "did:wyrd:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";
    private static final String DID_B =
        "did:wyrd:z6MkszZtxCmA2Ce4vUV132PCuLQmwnaDD5mUcs8LU6CJr8ad";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach void reset() {
        ZoneDirectoryService.resetForTests();
    }

    @AfterEach void cleanup() {
        ZoneDirectoryService.resetForTests();
    }

    private static ZoneManifestV1 manifest(String did, String label, List<String> tags,
                                            ZoneManifestV1.Capabilities caps,
                                            String refreshedAt) {
        return new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, did, label, "Display-" + label, null,
            "tag", "description", tags, caps, null, null, 0,
            "2026-01-15T00:00:00Z", refreshedAt, null);
    }

    @Test void renderDiscover_noService_returnsEmptyArray() throws Exception {
        // No init() called — should degrade to "[]" rather than NPE.
        var json = ZoneDirectoryService.renderDiscover("recent", "10");
        var arr = MAPPER.readTree(json);
        assertTrue(arr.isArray());
        assertEquals(0, arr.size());
    }

    @Test void renderDiscover_recent_returnsOrderedList() throws Exception {
        var dir = new InMemoryZoneDirectory();
        dir.publish(manifest(DID_A, "kitchen", List.of("social"), null, "2026-04-20T00:00:00Z"));
        dir.publish(manifest(DID_B, "garage", List.of("work"), null, "2026-04-19T00:00:00Z"));
        ZoneDirectoryService.init(dir);

        var json = ZoneDirectoryService.renderDiscover("recent", "10");
        var arr = MAPPER.readTree(json);
        assertEquals(2, arr.size());
        // Both present; InMemoryZoneDirectory's recent() ordering isn't
        // guaranteed so we just check both DIDs are in the output.
        var dids = new HashSet<String>();
        arr.forEach(n -> dids.add(n.path("did").asText()));
        assertTrue(dids.contains(DID_A));
        assertTrue(dids.contains(DID_B));
    }

    @Test void renderDiscover_summarisesManifestForScript() throws Exception {
        var dir = new InMemoryZoneDirectory();
        dir.publish(manifest(DID_A, "kitchen", List.of("social", "craft"),
            null, "2026-04-20T00:00:00Z"));
        ZoneDirectoryService.init(dir);

        var json = ZoneDirectoryService.renderDiscover("recent", "10");
        var arr = MAPPER.readTree(json);
        var entry = arr.get(0);
        assertEquals(DID_A, entry.path("did").asText());
        assertEquals("kitchen", entry.path("zoneLabel").asText());
        assertEquals("Display-kitchen", entry.path("displayName").asText());
        assertEquals("tag", entry.path("tagline").asText());
        assertTrue(entry.path("tags").isArray());
        assertEquals(2, entry.path("tags").size());
        assertEquals("2026-04-20T00:00:00Z", entry.path("refreshedAt").asText());
    }

    @Test void renderDiscover_tag_returnsOnlyMatchingZones() throws Exception {
        var dir = new InMemoryZoneDirectory();
        dir.publish(manifest(DID_A, "kitchen", List.of("social"), null, "2026-04-20T00:00:00Z"));
        dir.publish(manifest(DID_B, "garage", List.of("work"), null, "2026-04-20T00:00:00Z"));
        ZoneDirectoryService.init(dir);

        var json = ZoneDirectoryService.renderDiscover("tag:social", null);
        var arr = MAPPER.readTree(json);
        assertEquals(1, arr.size());
        assertEquals(DID_A, arr.get(0).path("did").asText());
    }

    @Test void renderDiscover_capability_returnsMatching() throws Exception {
        var caps = new ZoneManifestV1.Capabilities(
            null,
            List.of(new ZoneManifestV1.PublicRoom("library", "The Library", "Books.")),
            List.of(), Map.of(), Map.of());
        var dir = new InMemoryZoneDirectory();
        dir.publish(manifest(DID_A, "kitchen", List.of(), caps, "2026-04-20T00:00:00Z"));
        dir.publish(manifest(DID_B, "garage", List.of(), null, "2026-04-20T00:00:00Z"));
        ZoneDirectoryService.init(dir);

        var json = ZoneDirectoryService.renderDiscover("capability:library", null);
        var arr = MAPPER.readTree(json);
        assertEquals(1, arr.size());
        assertEquals(DID_A, arr.get(0).path("did").asText());
    }

    @Test void renderDiscover_searchModeIsNoOpAtInterfaceLayer() throws Exception {
        var dir = new InMemoryZoneDirectory();
        dir.publish(manifest(DID_A, "kitchen", List.of(), null, "2026-04-20T00:00:00Z"));
        ZoneDirectoryService.init(dir);

        // search: lives on rendezvous, not the base interface. Should
        // return empty rather than throw.
        var json = ZoneDirectoryService.renderDiscover("search:kitchen", null);
        var arr = MAPPER.readTree(json);
        assertEquals(0, arr.size());
    }

    @Test void renderDiscover_invalidLimit_defaultsToTwenty() throws Exception {
        var dir = new InMemoryZoneDirectory();
        for (int i = 0; i < 25; i++) {
            var did = "did:wyrd:z6MkPad111111111111111111111111111111111" + i;
            dir.publish(manifest(did, "zone-" + i, List.of(), null, "2026-04-20T00:00:00Z"));
        }
        ZoneDirectoryService.init(dir);

        var json = ZoneDirectoryService.renderDiscover("recent", "not-a-number");
        var arr = MAPPER.readTree(json);
        assertEquals(20, arr.size(), "invalid limit falls back to default 20");
    }

    @Test void renderDiscover_limitClampedToFifty() throws Exception {
        var dir = new InMemoryZoneDirectory();
        for (int i = 0; i < 100; i++) {
            var did = "did:wyrd:z6MkPad2222222222222222222222222222222222" + i;
            dir.publish(manifest(did, "zone-" + i, List.of(), null, "2026-04-20T00:00:00Z"));
        }
        ZoneDirectoryService.init(dir);

        var json = ZoneDirectoryService.renderDiscover("recent", "999");
        var arr = MAPPER.readTree(json);
        assertTrue(arr.size() <= 50, "limit must be clamped to 50 max");
    }

    @Test void init_thenGet_returnsSameInstance() {
        var dir = new InMemoryZoneDirectory();
        ZoneDirectoryService.init(dir);
        assertSame(dir, ZoneDirectoryService.get());
    }

    @Test void get_beforeInit_returnsNull() {
        assertNull(ZoneDirectoryService.get());
    }

    @Test void resetForTests_clearsInstance() {
        ZoneDirectoryService.init(new InMemoryZoneDirectory());
        assertNotNull(ZoneDirectoryService.get());
        ZoneDirectoryService.resetForTests();
        assertNull(ZoneDirectoryService.get());
    }
}
