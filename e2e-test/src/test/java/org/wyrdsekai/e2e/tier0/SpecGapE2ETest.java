package org.wyrdsekai.e2e.tier0;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AgentSupervisor;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.item.EquipmentService;
import org.wyrdsekai.core.mcp.ProcessMcpProvisioner;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.WireMockInferenceServer;
import org.wyrdsekai.scripting.api.ScriptHttpServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E tests for the 10 spec gap implementations:
 * visit.md endpoint, OPDS-K catalog, Study sync, ScriptHttpServer.
 */
@Tag("integration")
class SpecGapE2ETest {

    private static WireMockInferenceServer wireMock;
    private static TestServerBootstrap server;
    private static HttpClient http;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Welcome.", 10, 5);

        var client = new InferenceClient(wireMock.baseUrl());
        var backend = new InferenceBackend.LlamaServer(
            "wiremock-gaps", client, 10, List.of(), null);
        server = new TestServerBootstrap(List.of(backend));
        server.start();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    // ── #7 visit.md ──

    @Test
    void visit_md_endpoint_returns_markdown() throws Exception {
        var req = HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/visit.md"))
            .GET().build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("Visit This Household"));
        assertTrue(resp.body().contains("Trust Tiers"));
        assertTrue(resp.body().contains("DockQuarantine"));
    }

    // ── #9 OPDS-K catalog ──

    @Test
    void opds_catalog_endpoint_returns_json() throws Exception {
        var req = HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/library/opds"))
            .GET().build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("metadata"));
        assertTrue(resp.body().contains("OPDS-K Catalog"));
        assertTrue(resp.body().contains("entries"));
    }

    // ── #5 Study sync ──

    @Test
    void legacy_http_study_sync_endpoint_is_removed() throws Exception {
        // Pre-OSS hardening: the GET/POST /api/study/sync HTTP route was a
        // timestamp-LWW merge that trusted a body/query-supplied userDid with NO
        // authentication — a security hole. It was DELETED; phone↔zone Study now
        // converges via the authenticated CRDT-over-NATS peer (StudySyncPeer,
        // token-validated). This test locks in the removal so the insecure route
        // can't silently return. See security-followups / study-sync-auth memory.
        var req = HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/study/sync?user=test-user&since=0"))
            .GET().build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(404, resp.statusCode(),
            "legacy unauthenticated /api/study/sync must stay removed");
    }

    // ── #13 AgentSupervisor ──

    @Test
    void agent_supervisor_lifecycle() {
        var supervisor = AgentSupervisor.init();
        supervisor.register("e2e-agent", "TestBot", "nexus");
        assertEquals(1, supervisor.agentCount());
        assertEquals("nexus", supervisor.getAgent("e2e-agent").roomId());
        supervisor.unregister("e2e-agent");
        assertEquals(0, supervisor.agentCount());
    }

    // ── #15 ScriptHttpServer ──

    @Test
    void script_http_server_starts_and_handles() throws Exception {
        var scriptServer = new ScriptHttpServer(
            PortAllocator.allocate());
        scriptServer.registerHandler("/test",
            (method, path, body, headers) -> "{\"ok\": true}");
        scriptServer.start();

        try {
            var req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + scriptServer.port() + "/test"))
                .GET().build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, resp.statusCode());
            assertTrue(resp.body().contains("ok"));
        } finally {
            scriptServer.stop();
        }
    }

    @Test
    void script_http_server_404_for_unknown_path() throws Exception {
        var scriptServer = new ScriptHttpServer(
            PortAllocator.allocate());
        scriptServer.start();

        try {
            var req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + scriptServer.port() + "/nonexistent"))
                .GET().build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            assertEquals(404, resp.statusCode());
        } finally {
            scriptServer.stop();
        }
    }

    // ── #10 ProcessMcpProvisioner ──

    @Test
    void process_provisioner_manages_instances() {
        var provisioner = new ProcessMcpProvisioner();
        assertTrue(provisioner.list().isEmpty());
        assertFalse(provisioner.isHealthy("none"));
        assertFalse(provisioner.deprovision("none"));
    }

    // ── #12 EquipmentService ward + look-at ──

    @Test
    void equipment_service_singleton_works() {
        var service = EquipmentService.get();
        assertNotNull(service);
        service.equipWard("e2e-agent", "ward-e2e", "Test Ward", "study-ward",
            "Access to test Study", "carrying a test ward");

        var items = service.getEquipped("e2e-agent");
        assertFalse(items.isEmpty());
        assertEquals("carrying a test ward", items.getFirst().selfDescription());

        // Clean up
        service.doff("e2e-agent", "ward-e2e");
    }
}
