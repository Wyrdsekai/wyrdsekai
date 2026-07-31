package org.wyrdsekai.core.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Wave E: Discovery & Growth (§91).
 */
class McpDiscoveryTest {

    // --- McpRegistrySyncer Tests ---

    @Nested
    class RegistrySyncerTests {

        private McpRegistrySyncer syncer;

        @BeforeEach
        void setup() {
            syncer = new McpRegistrySyncer();
        }

        @Test
        void discovers_new_capabilities() throws Exception {
            String json = """
                [
                    {"id":"weather","name":"Weather MCP","description":"Weather data",
                     "endpoint":"http://weather.local","transport":"http",
                     "source":"mcp-registry","trustScore":0.8,"version":"1.0",
                     "discoveredAt":1709913600000},
                    {"id":"maps","name":"Maps MCP","description":"Map data",
                     "endpoint":"http://maps.local","transport":"http",
                     "source":"smithery","trustScore":0.4,"version":"1.0",
                     "discoveredAt":1709913600000}
                ]""";

            int count = syncer.processDiscoveries(json);
            assertEquals(2, count);
            assertEquals(2, syncer.discoveredCount());
        }

        @Test
        void skips_duplicates() throws Exception {
            String json = """
                [{"id":"weather","name":"Weather","description":"",
                  "endpoint":"http://w","transport":"http",
                  "source":"reg","trustScore":0.8,"version":"1.0",
                  "discoveredAt":0}]""";

            syncer.processDiscoveries(json);
            int count = syncer.processDiscoveries(json); // same again
            assertEquals(0, count);
            assertEquals(1, syncer.discoveredCount());
        }

        @Test
        void skips_blocklisted() throws Exception {
            syncer.block("banned-service");
            String json = """
                [{"id":"banned-service","name":"Banned","description":"",
                  "endpoint":"http://b","transport":"http",
                  "source":"reg","trustScore":0.9,"version":"1.0",
                  "discoveredAt":0}]""";

            int count = syncer.processDiscoveries(json);
            assertEquals(0, count);
        }

        @Test
        void search_finds_by_name_or_description() throws Exception {
            String json = """
                [
                    {"id":"w1","name":"Weather API","description":"Forecasts",
                     "endpoint":"http://w","transport":"http",
                     "source":"reg","trustScore":0.5,"version":"1.0","discoveredAt":0},
                    {"id":"w2","name":"Maps","description":"Geographic weather data",
                     "endpoint":"http://m","transport":"http",
                     "source":"reg","trustScore":0.5,"version":"1.0","discoveredAt":0},
                    {"id":"w3","name":"Calendar","description":"Scheduling",
                     "endpoint":"http://c","transport":"http",
                     "source":"reg","trustScore":0.5,"version":"1.0","discoveredAt":0}
                ]""";
            syncer.processDiscoveries(json);

            var results = syncer.search("weather");
            assertEquals(2, results.size()); // Weather API + "weather data" in description
        }

        @Test
        void filter_by_trust() throws Exception {
            String json = """
                [
                    {"id":"t1","name":"Trusted","description":"",
                     "endpoint":"http://t","transport":"http",
                     "source":"reg","trustScore":0.8,"version":"1.0","discoveredAt":0},
                    {"id":"t2","name":"Low","description":"",
                     "endpoint":"http://l","transport":"http",
                     "source":"reg","trustScore":0.2,"version":"1.0","discoveredAt":0}
                ]""";
            syncer.processDiscoveries(json);

            assertEquals(1, syncer.getDiscovered(0.5).size());
            assertEquals(2, syncer.getDiscovered(0.1).size());
        }

        @Test
        void mark_installed_removes_from_discovered() throws Exception {
            String json = """
                [{"id":"s1","name":"S1","description":"",
                  "endpoint":"http://s","transport":"http",
                  "source":"reg","trustScore":0.5,"version":"1.0","discoveredAt":0}]""";
            syncer.processDiscoveries(json);
            assertEquals(1, syncer.discoveredCount());

            syncer.markInstalled("s1");
            assertEquals(0, syncer.discoveredCount());
        }

        @Test
        void block_and_unblock() {
            syncer.block("bad");
            assertTrue(syncer.isBlocked("bad"));
            syncer.unblock("bad");
            assertFalse(syncer.isBlocked("bad"));
        }

        @Test
        void trust_level_labels() {
            var high = new McpRegistrySyncer.DiscoveredCapability(
                "h", "H", "", "", "", "", 0.8, "1.0", 0);
            assertEquals("TRUSTED", high.trustLevel());

            var low = new McpRegistrySyncer.DiscoveredCapability(
                "l", "L", "", "", "", "", 0.2, "1.0", 0);
            assertEquals("UNTRUSTED", low.trustLevel());
        }
    }

    // --- RoomTemplate Tests ---

    @Nested
    class RoomTemplateTests {

        @Test
        void can_install_checks_registry() {
            var registry = new McpServiceRegistry();
            registry.register(new McpServiceConfig("searxng", "Searxng", "http",
                "http://localhost:8888", "local", null, null, true));

            var template = new RoomTemplate("scrying-pool", "Scrying Pool",
                "Web search room", List.of("searxng"),
                List.of(new RoomTemplate.TemplateObject("pool", "scrying-pool", "The pool", false)),
                "rooms/scrying-pool.js", "world-interface", 0.5, "1.0", "built-in");

            assertTrue(template.canInstall(registry));
        }

        @Test
        void cannot_install_missing_service() {
            var registry = new McpServiceRegistry();

            var template = new RoomTemplate("hearth", "Hearth",
                "Home automation", List.of("home-assistant"),
                List.of(), "rooms/hearth.js", "world-interface", 0.5, "1.0", "built-in");

            assertFalse(template.canInstall(registry));
        }

        @Test
        void can_install_with_no_requirements() {
            var registry = new McpServiceRegistry();
            var template = new RoomTemplate("test", "Test",
                "No deps", List.of(), List.of(),
                "rooms/test.js", "test", 0.0, "1.0", "built-in");

            assertTrue(template.canInstall(registry));
        }

        @Test
        void is_built_in() {
            var builtIn = new RoomTemplate("t1", "T1", "", List.of(), List.of(),
                "", "", 0, "1.0", "built-in");
            assertTrue(builtIn.isBuiltIn());

            var community = new RoomTemplate("t2", "T2", "", List.of(), List.of(),
                "", "", 0, "1.0", "community");
            assertFalse(community.isBuiltIn());
        }

        @Test
        void load_templates_from_json() throws Exception {
            var syncer = new McpRegistrySyncer();
            String json = """
                [
                    {"id":"hearth-template","name":"Hearth",
                     "description":"Home automation room",
                     "requires_services":["home-assistant"],
                     "objects":[{"id":"hearthstone","name":"hearthstone",
                                 "description":"Control crystal","takeable":false}],
                     "script":"rooms/hearth.js","category":"world-interface",
                     "trust_minimum":0.5,"version":"1.0","source":"built-in"},
                    {"id":"pool-template","name":"Scrying Pool",
                     "description":"Web search room",
                     "requires_services":["searxng"],
                     "objects":[{"id":"pool","name":"scrying-pool",
                                 "description":"The pool","takeable":false}],
                     "script":"rooms/scrying-pool.js","category":"world-interface",
                     "trust_minimum":0.5,"version":"1.0","source":"built-in"}
                ]""";

            int count = syncer.loadTemplates(json);
            assertEquals(2, count);
            assertEquals(2, syncer.templateCount());
        }

        @Test
        void find_template_for_service() throws Exception {
            var syncer = new McpRegistrySyncer();
            String json = """
                [
                    {"id":"t1","name":"Hearth","description":"",
                     "requires_services":["home-assistant"],"objects":[],
                     "script":"","category":"","trust_minimum":0,"version":"1.0","source":""},
                    {"id":"t2","name":"Pool","description":"",
                     "requires_services":["searxng"],"objects":[],
                     "script":"","category":"","trust_minimum":0,"version":"1.0","source":""}
                ]""";
            syncer.loadTemplates(json);

            var haTemplates = syncer.findTemplatesForService("home-assistant");
            assertEquals(1, haTemplates.size());
            assertEquals("t1", haTemplates.getFirst().id());
        }
    }
}
