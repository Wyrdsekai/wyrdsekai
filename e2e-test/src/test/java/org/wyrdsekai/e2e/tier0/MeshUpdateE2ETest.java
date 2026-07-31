package org.wyrdsekai.e2e.tier0;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.wyrdsekai.e2e.infra.*;
import org.wyrdsekai.common.model.AppVersion;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.update.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E tests for the mesh update protocol:
 * - Version advertisement via /health and /api/update/status
 * - Release manifest serving via /api/update/manifest
 * - Package serving via /api/update/package
 * - Post-update health check via /api/update/health
 * - Update engine: stage, swap, rollback
 * - Channel poller with file:// channel
 * - Config and policy enforcement
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MeshUpdateE2ETest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static TestServerBootstrap server;
    private static WireMockInferenceServer wireMock;
    private static HttpClient http;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Acknowledged.", 30, 20);

        server = new TestServerBootstrap(List.of(
            new InferenceBackend.LlamaServer("wiremock",
                new InferenceClient(wireMock.baseUrl()), 10, List.of(), null)));
        server.start();

        http = HttpClient.newHttpClient();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    // ---- helpers ----

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + path))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    // ==================================================================
    // /health includes version
    // ==================================================================

    @Test @Order(1)
    void health_includes_version_info() throws Exception {
        var resp = get("/health");
        assertEquals(200, resp.statusCode());
        var json = mapper.readTree(resp.body());

        assertTrue(json.has("version"), "Health should include version");
        assertTrue(json.has("buildHash"), "Health should include buildHash");
        assertTrue(json.has("wireProtocol"), "Health should include wireProtocol");

        var version = json.get("version").asText();
        assertFalse(version.isEmpty(), "Version should not be empty");
        assertTrue(json.get("wireProtocol").asInt() >= 1, "Wire protocol should be >= 1");
    }

    // ==================================================================
    // /api/update/status
    // ==================================================================

    @Test @Order(2)
    void update_status_returns_version_and_config() throws Exception {
        var resp = get("/api/update/status");
        assertEquals(200, resp.statusCode());
        var json = mapper.readTree(resp.body());

        // Version fields
        assertTrue(json.has("version"));
        assertTrue(json.has("buildHash"));
        assertTrue(json.has("wireProtocol"));
        assertTrue(json.has("buildTimestamp"));

        // Config section
        assertTrue(json.has("config"), "Should include config section");
        var config = json.get("config");
        assertTrue(config.has("policy"));
        assertTrue(config.has("nodeRole"));
        assertTrue(config.has("enabled"));
    }

    // ==================================================================
    // /api/update/manifest
    // ==================================================================

    @Test @Order(3)
    void update_manifest_returns_current_version() throws Exception {
        var resp = get("/api/update/manifest");
        assertEquals(200, resp.statusCode());
        var json = mapper.readTree(resp.body());

        assertTrue(json.has("version"));
        assertTrue(json.has("wireProtocol"));
        assertTrue(json.has("buildHash"));
        assertEquals(AppVersion.get().version(), json.get("version").asText());
    }

    // ==================================================================
    // /api/update/health
    // ==================================================================

    @Test @Order(4)
    void update_health_reports_healthy() throws Exception {
        var resp = get("/api/update/health");
        assertEquals(200, resp.statusCode());
        var json = mapper.readTree(resp.body());

        assertTrue(json.get("healthy").asBoolean(), "Running server should be healthy");
        assertTrue(json.get("uptimeMs").asLong() > 0, "Uptime should be positive");
        assertTrue(json.has("version"));
        assertTrue(json.has("wireProtocol"));
    }

    // ==================================================================
    // /api/update/package (no package built)
    // ==================================================================

    @Test @Order(5)
    void update_package_404_when_not_built() throws Exception {
        var resp = get("/api/update/package");
        assertEquals(404, resp.statusCode());
        assertTrue(resp.body().contains("No package available"));
    }

    // ==================================================================
    // ReleaseManifest signing and verification
    // ==================================================================

    @Test @Order(10)
    void manifest_sign_and_verify() throws Exception {
        // Generate an Ed25519 keypair
        var keyPairGen = KeyPairGenerator.getInstance("Ed25519");
        var keyPair = keyPairGen.generateKeyPair();

        var manifest = new ReleaseManifest("1.0.0", 1, "abc123",
            Instant.now(), "0.1.0",
            Map.of("universal", new ReleaseManifest.PackageInfo(
                "http://example.com/pkg.tar.gz", "sha256hash", 45000000)),
            "Test release", false, null);

        // Sign
        var signed = manifest.sign(keyPair.getPrivate());
        assertNotNull(signed.signature());

        // Verify with correct key
        var pubKeyBytes = keyPair.getPublic().getEncoded();
        // Ed25519 public key is the last 32 bytes of the encoded key
        var rawPubKey = new byte[32];
        System.arraycopy(pubKeyBytes, pubKeyBytes.length - 32, rawPubKey, 0, 32);
        var pubKeyBase64 = Base64.getEncoder().encodeToString(rawPubKey);

        assertTrue(signed.verify(pubKeyBase64), "Signature should verify with correct key");

        // Verify with wrong key fails
        var wrongKeyPair = keyPairGen.generateKeyPair();
        var wrongPubKeyBytes = wrongKeyPair.getPublic().getEncoded();
        var wrongRawPubKey = new byte[32];
        System.arraycopy(wrongPubKeyBytes, wrongPubKeyBytes.length - 32, wrongRawPubKey, 0, 32);
        var wrongPubKeyBase64 = Base64.getEncoder().encodeToString(wrongRawPubKey);

        assertFalse(signed.verify(wrongPubKeyBase64), "Signature should fail with wrong key");
    }

    // ==================================================================
    // UpdateEngine: full stage + swap + rollback cycle
    // ==================================================================

    @Test @Order(20)
    void engine_full_update_and_rollback_cycle() throws Exception {
        var tempDir = Files.createTempDirectory("mesh-update-e2e-");

        try {
            // Create fake "current installation"
            var installDir = tempDir.resolve("install");
            var libDir = installDir.resolve("lib");
            Files.createDirectories(libDir);
            Files.writeString(libDir.resolve("wyrdsekai-core-0.1.0.jar"), "original core jar");
            Files.writeString(libDir.resolve("wyrdsekai-server-0.1.0.jar"), "original server jar");

            // Build a "new version" package
            var pkgSrc = tempDir.resolve("new-version");
            var newLib = pkgSrc.resolve("lib");
            Files.createDirectories(newLib);
            Files.writeString(newLib.resolve("wyrdsekai-core-0.2.0.jar"), "updated core jar");
            Files.writeString(newLib.resolve("wyrdsekai-server-0.2.0.jar"), "updated server jar");

            var builder = new UpdatePackageBuilder(pkgSrc);
            var buildResult = builder.build(tempDir.resolve("output"));

            // Apply update
            var config = UpdateConfig.fromEnv();
            var engine = new UpdateEngine(installDir, config);

            var manifest = new ReleaseManifest("9.0.0", 1, "newbuild",
                Instant.now(), null,
                Map.of("universal", new ReleaseManifest.PackageInfo(
                    buildResult.packagePath().toUri().toString(),
                    buildResult.sha256(), buildResult.size())),
                "Test update", false, null);

            var result = engine.apply(manifest, buildResult.packagePath().toUri().toString());
            assertTrue(result.success(), "Update should succeed: " + result.message());

            // Verify state after update
            assertTrue(Files.exists(installDir.resolve("lib.prev/wyrdsekai-core-0.1.0.jar")),
                "Old JARs should be preserved in lib.prev/");
            assertTrue(Files.exists(installDir.resolve("lib/wyrdsekai-core-0.2.0.jar")),
                "New JARs should be in lib/");
            assertFalse(Files.exists(installDir.resolve("lib/wyrdsekai-core-0.1.0.jar")),
                "Old JARs should NOT be in lib/ anymore");

            // Verify history
            assertFalse(engine.history().isEmpty());
            assertTrue(engine.history().getLast().success());

            // Now rollback
            var rollbackResult = engine.rollback();
            assertTrue(rollbackResult.success(), "Rollback should succeed: " + rollbackResult.message());

            // Verify state after rollback
            assertTrue(Files.exists(installDir.resolve("lib/wyrdsekai-core-0.1.0.jar")),
                "Original JARs should be restored in lib/");
            assertTrue(Files.exists(installDir.resolve("lib.failed/wyrdsekai-core-0.2.0.jar")),
                "Failed version should be in lib.failed/");

        } finally {
            // Cleanup
            try (var walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
            }
        }
    }

    // ==================================================================
    // Channel poller with file:// channel
    // ==================================================================

    @Test @Order(30)
    void channel_poller_reads_file_manifest() throws Exception {
        var tempDir = Files.createTempDirectory("mesh-channel-e2e-");

        try {
            var manifestJson = """
                {"version":"5.0.0","wireProtocol":1,"buildHash":"e2etest",
                 "buildTimestamp":"2026-03-27T10:00:00Z","changelog":"E2E test release"}
                """;
            var manifestFile = tempDir.resolve("latest.json");
            Files.writeString(manifestFile, manifestJson);

            var poller = new UpdateChannelPoller(
                manifestFile.toUri().toString(), Duration.ofHours(1), null);

            var result = poller.check();
            assertTrue(result.isPresent(), "Should detect newer version");
            assertEquals("5.0.0", result.get().version());
            assertNull(poller.lastError());

            poller.close();
        } finally {
            try (var walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
            }
        }
    }

    // ==================================================================
    // Config + policy
    // ==================================================================

    @Test @Order(40)
    void config_effective_policy_patch_auto() {
        var config = new UpdateConfig("https://x", UpdateConfig.UpdatePolicy.AUTO,
            Duration.ofHours(6), Duration.ofMinutes(5), null, "secondary", null, null, 3);
        var manifest = new ReleaseManifest("0.1.1", 1, "x", null, null, null, null, false, null);
        assertEquals(UpdateConfig.UpdatePolicy.AUTO,
            config.effectivePolicy("0.1.0", manifest));
    }

    @Test @Order(41)
    void config_effective_policy_major_prompts() {
        var config = new UpdateConfig("https://x", UpdateConfig.UpdatePolicy.AUTO,
            Duration.ofHours(6), Duration.ofMinutes(5), null, "secondary", null, null, 3);
        var manifest = new ReleaseManifest("2.0.0", 1, "x", null, null, null, null, false, null);
        assertEquals(UpdateConfig.UpdatePolicy.PROMPT,
            config.effectivePolicy("0.1.0", manifest));
    }

    @Test @Order(42)
    void config_effective_policy_breaking_always_prompts() {
        var config = new UpdateConfig("https://x", UpdateConfig.UpdatePolicy.AUTO,
            Duration.ofHours(6), Duration.ofMinutes(5), null, "secondary", null, null, 3);
        var manifest = new ReleaseManifest("0.1.1", 1, "x", null, null, null, null, true, null);
        assertEquals(UpdateConfig.UpdatePolicy.PROMPT,
            config.effectivePolicy("0.1.0", manifest));
    }

    @Test @Order(43)
    void config_pinned_version_disables() {
        var config = new UpdateConfig("https://x", UpdateConfig.UpdatePolicy.AUTO,
            Duration.ofHours(6), Duration.ofMinutes(5), null, "secondary", null, "0.1.0", 3);
        assertFalse(config.enabled());
    }

    // ==================================================================
    // Version comparison edge cases
    // ==================================================================

    @Test @Order(50)
    void version_comparison_major_minor_patch() {
        assertTrue(ReleaseManifest.compareVersions("1.0.0", "0.9.9") > 0);
        assertTrue(ReleaseManifest.compareVersions("0.2.0", "0.1.9") > 0);
        assertTrue(ReleaseManifest.compareVersions("0.1.2", "0.1.1") > 0);
        assertEquals(0, ReleaseManifest.compareVersions("1.2.3", "1.2.3"));
    }

    @Test @Order(51)
    void version_comparison_snapshot_stripped() {
        assertEquals(0, ReleaseManifest.compareVersions("0.1.0-SNAPSHOT", "0.1.0"));
        assertTrue(ReleaseManifest.compareVersions("0.2.0-SNAPSHOT", "0.1.0") > 0);
    }

    @Test @Order(52)
    void manifest_upgrade_compatibility() {
        var m = new ReleaseManifest("0.5.0", 1, "x", null, "0.3.0",
            null, null, false, null);
        assertTrue(m.canUpgradeFrom("0.3.0"));
        assertTrue(m.canUpgradeFrom("0.4.0"));
        assertFalse(m.canUpgradeFrom("0.2.0"));
    }
}
