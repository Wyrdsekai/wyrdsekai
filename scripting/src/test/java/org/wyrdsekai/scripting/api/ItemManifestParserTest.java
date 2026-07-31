package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemManifestParserTest {

    @Test
    void parses_minimal_manifest() {
        var script = """
            exports.manifest = {
              name: "compass",
              version: "1.0.0",
              description: "Shows the time.",
              author: "did:wyrd:test",
              capabilities: []
            };
            """;
        var m = ItemManifestParser.parse(script);
        assertNotNull(m);
        assertEquals("compass", m.name());
        assertEquals("1.0.0", m.version());
        assertEquals("did:wyrd:test", m.author());
        assertTrue(m.capabilities().isEmpty());
    }

    @Test
    void parses_manifest_with_capabilities_and_rate_limits() {
        var script = """
            exports.manifest = {
              name: "research_clipper",
              version: "1.0.0",
              description: "Research item.",
              author: "did:wyrd:abc",
              capabilities: ["library.search", "library.add", "drive.mark"],
              rate_limits: {
                "library.add": { per_minute: 5, per_day: 100 },
                "drive.mark": { per_hour: 4 }
              },
              data_sensitivity: "bonded",
              install_warnings: ["Marks the seeking drive on success."]
            };
            function invoke(p) { return p; }
            """;
        var m = ItemManifestParser.parse(script);
        assertNotNull(m);
        assertEquals(3, m.capabilities().size());
        assertTrue(m.capabilities().contains("drive.mark"));
        var rl = m.rateLimitFor("library.add");
        assertNotNull(rl);
        assertEquals(5, rl.perMinute());
        assertEquals(100, rl.perDay());
        assertEquals("bonded", m.dataSensitivity());
        assertEquals(1, m.installWarnings().size());
    }

    @Test
    void returns_null_when_manifest_absent() {
        var script = """
            function invoke(p) { return p; }
            """;
        assertNull(ItemManifestParser.parse(script));
    }

    @Test
    void returns_null_on_blank() {
        assertNull(ItemManifestParser.parse(""));
        assertNull(ItemManifestParser.parse(null));
    }

    @Test
    void parses_external_domains_and_safe_slots() {
        var script = """
            exports.manifest = {
              name: "poster",
              version: "1.0.0",
              description: "Posts.",
              author: "did:wyrd:x",
              capabilities: ["web.post", "safe.get"],
              external_domains: ["api.example.com", "*.example.com"],
              safe_slots: ["EXAMPLE_TOKEN"]
            };
            """;
        var m = ItemManifestParser.parse(script);
        assertNotNull(m);
        assertEquals(2, m.externalDomains().size());
        assertEquals(1, m.safeSlots().size());
    }
}
