package org.wyrdsekai.core.mcp;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §86.3 hot-reload (McpServiceRegistry) and §91.1/§91.4
 * multi-source sync + template marketplace (McpRegistrySyncer).
 */
class McpRegistryExtensionTest {

    // ── §86.3: Hot-Reload (McpServiceRegistry) ──

    @Nested
    class HotReloadTests {

        private McpServiceRegistry registry;

        @BeforeEach
        void setup() throws IOException {
            registry = new McpServiceRegistry();
            registry.loadFromJson("""
                {"mcp_services": [
                    {"id":"searxng","name":"Searxng","transport":"http",
                     "endpoint":"http://localhost:8888","tier":"local",
                     "auth":null,"rate_limit_override":null,"enabled":true},
                    {"id":"home-assistant","name":"Home Assistant","transport":"http",
                     "endpoint":"http://ha.local","tier":"keyed",
                     "auth":{"type":"bearer","safe_key":"ha-token","header":"Authorization"},
                     "rate_limit_override":null,"enabled":true}
                ]}""");
        }

        @Test
        void reload_detects_additions() throws IOException {
            var result = registry.reload("""
                {"mcp_services": [
                    {"id":"searxng","name":"Searxng","transport":"http",
                     "endpoint":"http://localhost:8888","tier":"local",
                     "auth":null,"rate_limit_override":null,"enabled":true},
                    {"id":"home-assistant","name":"Home Assistant","transport":"http",
                     "endpoint":"http://ha.local","tier":"keyed",
                     "auth":{"type":"bearer","safe_key":"ha-token","header":"Authorization"},
                     "rate_limit_override":null,"enabled":true},
                    {"id":"ollama","name":"Ollama","transport":"http",
                     "endpoint":"http://localhost:11434","tier":"local",
                     "auth":null,"rate_limit_override":null,"enabled":true}
                ]}""");

            assertTrue(result.hasChanges());
            assertEquals(1, result.added());
            assertEquals(0, result.removed());
            assertEquals(0, result.updated());
            assertEquals(3, registry.size());
            assertTrue(registry.isAvailable("ollama"));
        }

        @Test
        void reload_detects_removals() throws IOException {
            var result = registry.reload("""
                {"mcp_services": [
                    {"id":"searxng","name":"Searxng","transport":"http",
                     "endpoint":"http://localhost:8888","tier":"local",
                     "auth":null,"rate_limit_override":null,"enabled":true}
                ]}""");

            assertTrue(result.hasChanges());
            assertEquals(0, result.added());
            assertEquals(1, result.removed());
            assertEquals(1, registry.size());
            assertFalse(registry.isAvailable("home-assistant"));
        }

        @Test
        void reload_detects_updates() throws IOException {
            var result = registry.reload("""
                {"mcp_services": [
                    {"id":"searxng","name":"Searxng","transport":"http",
                     "endpoint":"http://localhost:9999","tier":"local",
                     "auth":null,"rate_limit_override":null,"enabled":true},
                    {"id":"home-assistant","name":"Home Assistant","transport":"http",
                     "endpoint":"http://ha.local","tier":"keyed",
                     "auth":{"type":"bearer","safe_key":"ha-token","header":"Authorization"},
                     "rate_limit_override":null,"enabled":true}
                ]}""");

            assertTrue(result.hasChanges());
            assertEquals(0, result.added());
            assertEquals(0, result.removed());
            assertEquals(1, result.updated());
            assertEquals("http://localhost:9999", registry.get("searxng").orElseThrow().endpoint());
        }

        @Test
        void reload_no_changes() throws IOException {
            var result = registry.reload("""
                {"mcp_services": [
                    {"id":"searxng","name":"Searxng","transport":"http",
                     "endpoint":"http://localhost:8888","tier":"local",
                     "auth":null,"rate_limit_override":null,"enabled":true},
                    {"id":"home-assistant","name":"Home Assistant","transport":"http",
                     "endpoint":"http://ha.local","tier":"keyed",
                     "auth":{"type":"bearer","safe_key":"ha-token","header":"Authorization"},
                     "rate_limit_override":null,"enabled":true}
                ]}""");

            assertFalse(result.hasChanges());
        }

        @Test
        void listener_notified_on_add() throws IOException {
            var lastId = new AtomicReference<String>();
            var lastType = new AtomicReference<McpServiceRegistry.ChangeType>();

            registry.addListener((id, type) -> {
                lastId.set(id);
                lastType.set(type);
            });

            registry.reload("""
                {"mcp_services": [
                    {"id":"searxng","name":"Searxng","transport":"http",
                     "endpoint":"http://localhost:8888","tier":"local",
                     "auth":null,"rate_limit_override":null,"enabled":true},
                    {"id":"home-assistant","name":"Home Assistant","transport":"http",
                     "endpoint":"http://ha.local","tier":"keyed",
                     "auth":{"type":"bearer","safe_key":"ha-token","header":"Authorization"},
                     "rate_limit_override":null,"enabled":true},
                    {"id":"nats","name":"NATS","transport":"websocket",
                     "endpoint":"nats://localhost:4222","tier":"local",
                     "auth":null,"rate_limit_override":null,"enabled":true}
                ]}""");

            assertEquals("nats", lastId.get());
            assertEquals(McpServiceRegistry.ChangeType.REGISTERED, lastType.get());
        }

        @Test
        void listener_notified_on_remove() throws IOException {
            var lastType = new AtomicReference<McpServiceRegistry.ChangeType>();

            registry.addListener((id, type) -> {
                if ("home-assistant".equals(id)) lastType.set(type);
            });

            registry.reload("""
                {"mcp_services": [
                    {"id":"searxng","name":"Searxng","transport":"http",
                     "endpoint":"http://localhost:8888","tier":"local",
                     "auth":null,"rate_limit_override":null,"enabled":true}
                ]}""");

            assertEquals(McpServiceRegistry.ChangeType.UNREGISTERED, lastType.get());
        }

        @Test
        void listener_notified_on_update() throws IOException {
            var lastType = new AtomicReference<McpServiceRegistry.ChangeType>();

            registry.addListener((id, type) -> {
                if ("searxng".equals(id)) lastType.set(type);
            });

            registry.reload("""
                {"mcp_services": [
                    {"id":"searxng","name":"Searxng UPDATED","transport":"http",
                     "endpoint":"http://localhost:8888","tier":"local",
                     "auth":null,"rate_limit_override":null,"enabled":true},
                    {"id":"home-assistant","name":"Home Assistant","transport":"http",
                     "endpoint":"http://ha.local","tier":"keyed",
                     "auth":{"type":"bearer","safe_key":"ha-token","header":"Authorization"},
                     "rate_limit_override":null,"enabled":true}
                ]}""");

            assertEquals(McpServiceRegistry.ChangeType.UPDATED, lastType.get());
        }

        @Test
        void listener_error_does_not_crash_reload() throws IOException {
            registry.addListener((id, type) -> {
                throw new RuntimeException("Listener crash");
            });

            // Should not throw
            var result = registry.reload("""
                {"mcp_services": [
                    {"id":"new-service","name":"New","transport":"http",
                     "endpoint":"http://n","tier":"local",
                     "auth":null,"rate_limit_override":null,"enabled":true}
                ]}""");

            assertTrue(result.hasChanges());
        }

        @Test
        void remove_listener() throws IOException {
            var count = new AtomicInteger(0);
            McpServiceRegistry.RegistryListener listener = (id, type) -> count.incrementAndGet();

            registry.addListener(listener);
            registry.reload("""
                {"mcp_services": [
                    {"id":"a","name":"A","transport":"http","endpoint":"http://a",
                     "tier":"local","auth":null,"rate_limit_override":null,"enabled":true}
                ]}""");
            int after1 = count.get();
            assertTrue(after1 > 0);

            registry.removeListener(listener);
            registry.reload("""
                {"mcp_services": [
                    {"id":"b","name":"B","transport":"http","endpoint":"http://b",
                     "tier":"local","auth":null,"rate_limit_override":null,"enabled":true}
                ]}""");

            // Listener count should not increase after removal
            assertEquals(after1, count.get());
        }

        @Test
        void reload_complex_scenario() throws IOException {
            // Add 1, remove 1, update 1
            var result = registry.reload("""
                {"mcp_services": [
                    {"id":"searxng","name":"Searxng v2","transport":"http",
                     "endpoint":"http://localhost:9999","tier":"local",
                     "auth":null,"rate_limit_override":null,"enabled":true},
                    {"id":"ollama","name":"Ollama","transport":"http",
                     "endpoint":"http://localhost:11434","tier":"local",
                     "auth":null,"rate_limit_override":null,"enabled":true}
                ]}""");

            assertEquals(1, result.added());   // ollama
            assertEquals(1, result.removed()); // home-assistant
            assertEquals(1, result.updated()); // searxng (name + endpoint changed)
            assertEquals(2, registry.size());
        }
    }

    // ── §91.1: Multi-Source Sync (McpRegistrySyncer) ──

    @Nested
    class MultiSourceSyncTests {

        private McpRegistrySyncer syncer;

        @BeforeEach
        void setup() {
            syncer = new McpRegistrySyncer();
        }

        @Test
        void default_sources_configured() {
            var sources = syncer.allSources();
            assertEquals(3, sources.size());

            var enabled = syncer.enabledSources();
            assertEquals(3, enabled.size());
        }

        @Test
        void add_and_remove_source() {
            syncer.addSource(new McpRegistrySyncer.RegistrySource(
                "custom", "Custom", "https://custom.example", 0.6, true, 0));
            assertEquals(4, syncer.allSources().size());

            syncer.removeSource("custom");
            assertEquals(3, syncer.allSources().size());
        }

        @Test
        void process_from_source_applies_trust_weight() throws IOException {
            String json = """
                [{"id":"weather","name":"Weather","description":"Weather data",
                  "endpoint":"http://w","transport":"http",
                  "source":"mcp-registry","trustScore":0.8,"version":"1.0",
                  "discoveredAt":0}]""";

            // MCP Registry has weight 1.0, so 0.8 * 1.0 = 0.8
            var result = syncer.processFromSource("mcp-registry", json);
            assertEquals(1, result.newCapabilities());
            assertEquals(0, result.errors());

            var cap = syncer.getDiscovered().getFirst();
            assertEquals(0.8, cap.trustScore(), 0.01);
            assertEquals("mcp-registry", cap.source());
        }

        @Test
        void process_from_smithery_applies_lower_weight() throws IOException {
            String json = """
                [{"id":"weather","name":"Weather","description":"Weather data",
                  "endpoint":"http://w","transport":"http",
                  "source":"smithery","trustScore":0.8,"version":"1.0",
                  "discoveredAt":0}]""";

            // Smithery has weight 0.8, so 0.8 * 0.8 = 0.64
            syncer.processFromSource("smithery", json);
            var cap = syncer.getDiscovered().getFirst();
            assertEquals(0.64, cap.trustScore(), 0.01);
        }

        @Test
        void higher_trust_from_second_source_updates() throws IOException {
            // First: PulseMCP (weight 0.7), score 0.8 → 0.56
            syncer.processFromSource("pulsemcp", """
                [{"id":"weather","name":"Weather","description":"",
                  "endpoint":"http://w","transport":"http",
                  "source":"pulsemcp","trustScore":0.8,"version":"1.0",
                  "discoveredAt":0}]""");

            assertEquals(0.56, syncer.getDiscovered().getFirst().trustScore(), 0.01);

            // Second: MCP Registry (weight 1.0), score 0.8 → 0.8 (higher)
            var result = syncer.processFromSource("mcp-registry", """
                [{"id":"weather","name":"Weather","description":"",
                  "endpoint":"http://w","transport":"http",
                  "source":"mcp-registry","trustScore":0.8,"version":"1.0",
                  "discoveredAt":0}]""");

            assertEquals(0, result.newCapabilities());
            assertEquals(1, result.updatedCapabilities());
            assertEquals(0.8, syncer.getDiscovered().getFirst().trustScore(), 0.01);
            assertEquals("mcp-registry", syncer.getDiscovered().getFirst().source());
        }

        @Test
        void lower_trust_from_second_source_does_not_update() throws IOException {
            // First: MCP Registry (weight 1.0), score 0.8 → 0.8
            syncer.processFromSource("mcp-registry", """
                [{"id":"weather","name":"Weather","description":"",
                  "endpoint":"http://w","transport":"http",
                  "source":"mcp-registry","trustScore":0.8,"version":"1.0",
                  "discoveredAt":0}]""");

            // Second: PulseMCP (weight 0.7), score 0.8 → 0.56 (lower)
            var result = syncer.processFromSource("pulsemcp", """
                [{"id":"weather","name":"Weather","description":"",
                  "endpoint":"http://w","transport":"http",
                  "source":"pulsemcp","trustScore":0.8,"version":"1.0",
                  "discoveredAt":0}]""");

            assertEquals(0, result.newCapabilities());
            assertEquals(0, result.updatedCapabilities());
            assertEquals("mcp-registry", syncer.getDiscovered().getFirst().source());
        }

        @Test
        void unknown_source_returns_error() throws IOException {
            var result = syncer.processFromSource("nonexistent", "[]");
            assertEquals(1, result.errors());
            assertEquals(0, result.newCapabilities());
        }

        @Test
        void blocklisted_skipped_during_source_sync() throws IOException {
            syncer.block("banned");
            var result = syncer.processFromSource("mcp-registry", """
                [{"id":"banned","name":"Banned","description":"",
                  "endpoint":"http://b","transport":"http",
                  "source":"","trustScore":0.9,"version":"1.0","discoveredAt":0}]""");

            assertEquals(0, result.newCapabilities());
        }

        @Test
        void by_source_filter() throws IOException {
            syncer.processFromSource("mcp-registry", """
                [{"id":"w1","name":"Weather","description":"",
                  "endpoint":"http://w","transport":"http",
                  "source":"","trustScore":0.8,"version":"1.0","discoveredAt":0}]""");
            syncer.processFromSource("smithery", """
                [{"id":"m1","name":"Maps","description":"",
                  "endpoint":"http://m","transport":"http",
                  "source":"","trustScore":0.8,"version":"1.0","discoveredAt":0}]""");

            assertEquals(1, syncer.bySource("mcp-registry").size());
            assertEquals(1, syncer.bySource("smithery").size());
            assertEquals(0, syncer.bySource("pulsemcp").size());
        }

        @Test
        void source_last_sync_updated() throws IOException {
            syncer.processFromSource("mcp-registry", """
                [{"id":"w","name":"W","description":"","endpoint":"http://w",
                  "transport":"http","source":"","trustScore":0.5,
                  "version":"1.0","discoveredAt":0}]""");

            var source = syncer.allSources().stream()
                .filter(s -> "mcp-registry".equals(s.id()))
                .findFirst().orElseThrow();
            assertTrue(source.lastSyncEpoch() > 0);
        }

        @Test
        void sync_result_includes_duration() throws IOException {
            var result = syncer.processFromSource("mcp-registry", """
                [{"id":"w","name":"W","description":"","endpoint":"http://w",
                  "transport":"http","source":"","trustScore":0.5,
                  "version":"1.0","discoveredAt":0}]""");

            assertTrue(result.durationMs() >= 0);
        }
    }

    // ── §91.4: Template Marketplace ──

    @Nested
    class TemplateMarketplaceTests {

        private McpRegistrySyncer syncer;
        private McpServiceRegistry registry;

        @BeforeEach
        void setup() throws IOException {
            syncer = new McpRegistrySyncer();
            registry = new McpServiceRegistry();

            syncer.loadTemplates("""
                [
                    {"id":"hearth","name":"Hearth","description":"Home automation room",
                     "requires_services":["home-assistant"],"objects":[],
                     "script":"rooms/hearth.js","category":"world-interface",
                     "trust_minimum":0.5,"version":"1.0","source":"built-in"},
                    {"id":"scrying-pool","name":"Scrying Pool","description":"Web search room",
                     "requires_services":["searxng"],"objects":[],
                     "script":"rooms/scrying-pool.js","category":"world-interface",
                     "trust_minimum":0.5,"version":"1.0","source":"built-in"},
                    {"id":"forge","name":"The Forge","description":"Soul maintenance room",
                     "requires_services":[],"objects":[],
                     "script":"rooms/forge.js","category":"kokoro",
                     "trust_minimum":0.0,"version":"1.0","source":"built-in"}
                ]""");
        }

        @Test
        void can_install_all_deps_met() {
            registry.register(new McpServiceConfig("searxng", "Searxng", "http",
                "http://localhost:8888", "local", null, null, true));

            var result = syncer.canInstall("scrying-pool", registry);
            assertTrue(result.success());
            assertTrue(result.missingServices().isEmpty());
        }

        @Test
        void can_install_missing_service() {
            var result = syncer.canInstall("hearth", registry);
            assertFalse(result.success());
            assertEquals(List.of("home-assistant"), result.missingServices());
            assertTrue(result.reason().contains("home-assistant"));
        }

        @Test
        void can_install_no_deps() {
            var result = syncer.canInstall("forge", registry);
            assertTrue(result.success());
            assertEquals("No dependencies", result.reason());
        }

        @Test
        void can_install_unknown_template() {
            var result = syncer.canInstall("nonexistent", registry);
            assertFalse(result.success());
            assertTrue(result.reason().contains("not found"));
        }

        @Test
        void search_templates_by_name() {
            var results = syncer.searchTemplates("hearth");
            assertEquals(1, results.size());
            assertEquals("hearth", results.getFirst().id());
        }

        @Test
        void search_templates_by_description() {
            var results = syncer.searchTemplates("search");
            assertEquals(1, results.size());
            assertEquals("scrying-pool", results.getFirst().id());
        }

        @Test
        void search_templates_by_category() {
            var results = syncer.searchTemplates("kokoro");
            assertEquals(1, results.size());
            assertEquals("forge", results.getFirst().id());
        }

        @Test
        void search_templates_blank_returns_all() {
            assertEquals(3, syncer.searchTemplates("").size());
            assertEquals(3, syncer.searchTemplates(null).size());
        }

        @Test
        void templates_by_category() {
            var worldTemplates = syncer.templatesByCategory("world-interface");
            assertEquals(2, worldTemplates.size());

            var kokoroTemplates = syncer.templatesByCategory("kokoro");
            assertEquals(1, kokoroTemplates.size());
        }

        @Test
        void find_templates_for_service() {
            var templates = syncer.findTemplatesForService("searxng");
            assertEquals(1, templates.size());
            assertEquals("scrying-pool", templates.getFirst().id());
        }
    }
}
