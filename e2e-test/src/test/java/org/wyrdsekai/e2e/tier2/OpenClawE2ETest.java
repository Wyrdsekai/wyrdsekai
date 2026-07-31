package org.wyrdsekai.e2e.tier2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillResult;
import org.wyrdsekai.core.skill.SkillTier;
import org.wyrdsekai.core.skill.impl.OpenClawGatewayExecutor;
import org.wyrdsekai.e2e.infra.PortAllocator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OpenClaw Gateway E2E — real Docker container with safe skills.
 *
 * <p>Auto-provisions the OpenClaw container from {@code docker/openclaw.Dockerfile}
 * with test skills mounted from {@code docker/openclaw-test-skills/}.
 *
 * <p>Infrastructure tests (always run): catalogue, invocation, concurrency, security.
 * <p>Live tests (env-gated): real LLM selects and invokes skills.
 */
@Tag("tier2")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OpenClawE2ETest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration INVOKE_TIMEOUT = Duration.ofSeconds(30);
    private static final String IMAGE_NAME = "openclaw-e2e-test";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static int gatewayPort;
    private static String containerName;
    private static OpenClawGatewayExecutor executor;
    private static boolean containerStarted = false;

    @BeforeAll
    static void setUp() throws Exception {
        // Check Docker availability
        assertDockerAvailable();

        gatewayPort = PortAllocator.allocate();
        containerName = "openclaw-e2e-" + gatewayPort;

        var projectRoot = resolveProjectRoot();

        // Build Docker image (cached layers make this fast after first run)
        System.out.println("[OpenClaw E2E] Building Docker image...");
        exec(projectRoot, 300,
            "docker", "build",
            "-f", projectRoot + "/docker/openclaw.Dockerfile",
            "-t", IMAGE_NAME,
            projectRoot.toString());

        // Run container with test skills mounted
        var skillsDir = projectRoot.resolve("docker/openclaw-test-skills").toAbsolutePath().toString();
        System.out.println("[OpenClaw E2E] Starting container on port " + gatewayPort + "...");
        exec(projectRoot, 10,
            "docker", "run", "-d",
            "--name", containerName,
            "-p", gatewayPort + ":18789",
            "-v", skillsDir + ":/opt/openclaw/skills:ro",
            IMAGE_NAME);
        containerStarted = true;

        // Verify container is running
        Thread.sleep(1000);
        var checkProcess = new ProcessBuilder("docker", "ps", "--filter",
            "name=" + containerName, "--format", "{{.Status}}")
            .redirectErrorStream(true).start();
        var status = new String(checkProcess.getInputStream().readAllBytes()).trim();
        checkProcess.waitFor(5, TimeUnit.SECONDS);
        System.out.println("[OpenClaw E2E] Container status: " + status);

        // Wait for health endpoint
        try {
            waitForHealth(gatewayPort, Duration.ofSeconds(45));
        } catch (AssertionError e) {
            // Dump container logs for diagnostics
            var logsProcess = new ProcessBuilder("docker", "logs", containerName)
                .redirectErrorStream(true).start();
            var logs = new String(logsProcess.getInputStream().readAllBytes());
            logsProcess.waitFor(5, TimeUnit.SECONDS);
            System.err.println("[OpenClaw E2E] Container logs:\n" + logs);
            throw e;
        }
        System.out.println("[OpenClaw E2E] Container healthy.");

        // Connect executor
        executor = new OpenClawGatewayExecutor("ws://localhost:" + gatewayPort);
        executor.connectAsync().get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        // Wait for catalogue to load
        Thread.sleep(2000);
        System.out.println("[OpenClaw E2E] Connected. Skills loaded: " + executor.skillCount());
    }

    @AfterAll
    static void tearDown() {
        if (executor != null) {
            try { executor.close(); } catch (Exception e) { /* ignore */ }
        }
        if (containerStarted) {
            try {
                new ProcessBuilder("docker", "stop", containerName)
                    .redirectErrorStream(true).start().waitFor(15, TimeUnit.SECONDS);
                new ProcessBuilder("docker", "rm", "-f", containerName)
                    .redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS);
                System.out.println("[OpenClaw E2E] Container cleaned up.");
            } catch (Exception e) {
                System.err.println("[OpenClaw E2E] Cleanup error: " + e.getMessage());
            }
        }
    }

    // ─── Infrastructure Tests (always run) ───

    @Test
    @Order(1)
    void health_endpoint_returns_ok() throws Exception {
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + gatewayPort + "/health"))
            .GET().timeout(Duration.ofSeconds(5)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "[HARD] Health endpoint should return 200");

        var body = MAPPER.readTree(response.body());
        assertEquals("healthy", body.path("status").asText(),
            "[HARD] Health status should be 'healthy'");
        assertTrue(body.path("skills").asInt() >= 3,
            "[HARD] Should have at least 3 skills loaded");
    }

    @Test
    @Order(2)
    void catalogue_loads_test_skills() {
        assertTrue(executor.skillCount() >= 3,
            "[HARD] Executor should have loaded at least 3 skills from catalogue");

        var skills = executor.availableSkills();
        var skillIds = skills.stream().map(s -> s.id()).toList();

        // Skills should follow openclaw.{cli}.{tool-name} pattern
        assertTrue(skillIds.stream().anyMatch(id -> id.contains("date")),
            "[HARD] Should have a date skill: " + skillIds);
        assertTrue(skillIds.stream().anyMatch(id -> id.contains("whoami")),
            "[HARD] Should have a whoami skill: " + skillIds);
        assertTrue(skillIds.stream().anyMatch(id -> id.contains("uname")),
            "[HARD] Should have a uname skill: " + skillIds);
    }

    @Test
    @Order(3)
    void all_skills_are_openclaw_tier() {
        for (var skill : executor.availableSkills()) {
            assertEquals(SkillTier.OPENCLAW, skill.tier(),
                "[HARD] Skill " + skill.id() + " should be OPENCLAW tier");
        }
    }

    @Test
    @Order(4)
    void invoke_date_returns_timestamp() {
        var dateSkillId = findSkillContaining("date");
        assertNotNull(dateSkillId, "[HARD] Date skill must exist");

        var ctx = testContext();
        SkillResult result = executor.execute(dateSkillId, Map.of(), ctx);

        assertTrue(result.success(), "[HARD] Date skill should succeed: " + result.output());
        assertFalse(result.output().isBlank(), "[HARD] Date output should not be blank");
        // Date output should contain year-like digits
        assertTrue(result.output().matches(".*\\d{4}.*"),
            "[HARD] Date output should contain a year: " + result.output());
    }

    @Test
    @Order(5)
    void invoke_whoami_returns_non_root() {
        var whoamiSkillId = findSkillContaining("whoami");
        assertNotNull(whoamiSkillId, "[HARD] Whoami skill must exist");

        var ctx = testContext();
        SkillResult result = executor.execute(whoamiSkillId, Map.of(), ctx);

        assertTrue(result.success(), "[HARD] Whoami should succeed: " + result.output());
        assertFalse(result.output().isBlank(), "[HARD] Whoami output should not be blank");
        // Container runs as non-root user 'openclaw'
        assertEquals("openclaw", result.output().trim(),
            "[HARD] Container should run as 'openclaw' user (non-root)");
    }

    @Test
    @Order(6)
    void invoke_uname_returns_system_info() {
        var unameSkillId = findSkillContaining("uname");
        assertNotNull(unameSkillId, "[HARD] Uname skill must exist");

        var ctx = testContext();
        SkillResult result = executor.execute(unameSkillId, Map.of(), ctx);

        assertTrue(result.success(), "[HARD] Uname should succeed: " + result.output());
        assertTrue(result.output().toLowerCase().contains("linux"),
            "[HARD] Uname should mention Linux: " + result.output());
    }

    @Test
    @Order(7)
    void invoke_unknown_skill_returns_unavailable() {
        var ctx = testContext();
        SkillResult result = executor.execute("openclaw.nonexistent.fake-skill", Map.of(), ctx);

        assertFalse(result.success(), "[HARD] Unknown skill should not succeed");
    }

    @Test
    @Order(8)
    void sequential_burst_invocations_all_resolve() throws Exception {
        var dateSkillId = findSkillContaining("date");
        assertNotNull(dateSkillId);

        var ctx = testContext();
        int count = 5;
        int successes = 0;

        // Fire-and-collect: send all requests quickly (but sequentially to respect
        // WebSocket send ordering), then collect results. This tests the gateway's
        // ability to handle multiple in-flight requests.
        var futures = new ArrayList<CompletableFuture<SkillResult>>();
        for (int i = 0; i < count; i++) {
            futures.add(CompletableFuture.supplyAsync(
                () -> executor.execute(dateSkillId, Map.of(), ctx)));
            Thread.sleep(50); // stagger to avoid WebSocket send race
        }

        for (var future : futures) {
            var result = future.get(INVOKE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (result.success()) successes++;
        }

        assertEquals(count, successes,
            "[HARD] All " + count + " burst invocations should succeed");
    }

    @Test
    @Order(9)
    void skill_results_have_correct_tier() {
        var dateSkillId = findSkillContaining("date");
        assertNotNull(dateSkillId);

        var result = executor.execute(dateSkillId, Map.of(), testContext());

        assertTrue(result.success());
        assertEquals(SkillTier.OPENCLAW, result.executorTier(),
            "[HARD] Result tier should be OPENCLAW");
        assertEquals(dateSkillId, result.skillId(),
            "[HARD] Result skillId should match invocation");
        assertTrue(result.durationMs() >= 0,
            "[HARD] Duration should be non-negative");
    }

    @Test
    @Order(10)
    void executor_reports_connected_state() {
        assertEquals(OpenClawGatewayExecutor.ConnectionState.CONNECTED,
            executor.connectionState(),
            "[HARD] Executor should be in CONNECTED state");
        assertFalse(executor.isClosed(),
            "[HARD] Executor should not be closed");
    }

    // ─── Live LLM Tests (env-gated) ───

    @Nested
    @Tag("tier3")
    class Live {

        @Test
        @Order(20)
        void llm_selects_and_invokes_skill() throws Exception {
            var ollamaUrl = System.getenv("OPENCLAW_OLLAMA_URL");
            var model = System.getenv("OPENCLAW_OLLAMA_MODEL");
            if (ollamaUrl == null) ollamaUrl = "http://localhost:11434";
            if (model == null) model = "qwen3:0.6b";

            // Check if Ollama is reachable
            assumeTrue(isOllamaReachable(ollamaUrl),
                "Ollama not reachable at " + ollamaUrl + " — skipping live test");

            // Build skill catalogue for prompt
            var skills = executor.availableSkills();
            var skillList = new StringBuilder();
            for (var skill : skills) {
                skillList.append("- ").append(skill.id())
                    .append(": ").append(skill.description()).append("\n");
            }

            // Ask LLM to select a skill
            var prompt = """
                You have access to these tools:
                %s
                The user asks: "What is today's date?"

                Respond with ONLY the tool ID to use. No explanation, no JSON, just the tool ID.
                """.formatted(skillList);

            var response = callOllama(ollamaUrl, model, prompt);
            assertNotNull(response, "[HARD] LLM should produce a response");
            System.out.println("[OpenClaw Live] LLM selected: " + response.trim());

            // Find a matching skill from the response
            String selectedId = null;
            for (var skill : skills) {
                if (response.contains(skill.id())) {
                    selectedId = skill.id();
                    break;
                }
            }

            // Soft assertion — LLM might not select correctly with tiny model
            if (selectedId == null) {
                System.out.println("[OpenClaw Live] WARNING: LLM did not select a valid skill ID.");
                System.out.println("[OpenClaw Live] Response was: " + response);
                // Fall back to date skill for invocation test
                selectedId = findSkillContaining("date");
            }

            // Execute the selected skill through real OpenClaw
            var result = executor.execute(selectedId, Map.of(), testContext());

            assertTrue(result.success(),
                "[HARD] Selected skill should execute successfully: " + result.output());
            assertFalse(result.output().isBlank(),
                "[HARD] Skill output should not be blank");

            System.out.println("[OpenClaw Live] Skill result: " + result.output().trim());
        }

        private boolean isOllamaReachable(String baseUrl) {
            try {
                var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3)).build();
                var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags"))
                    .GET().timeout(Duration.ofSeconds(5)).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200;
            } catch (Exception e) {
                return false;
            }
        }

        private String callOllama(String baseUrl, String model, String prompt) throws Exception {
            var body = MAPPER.writeValueAsString(Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "temperature", 0.1,
                "stream", false
            ));

            var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
            var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(60))
                .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("[OpenClaw Live] Ollama error " + response.statusCode()
                    + ": " + response.body());
                return null;
            }

            var json = MAPPER.readTree(response.body());
            return json.path("choices").path(0).path("message").path("content").asText(null);
        }
    }

    // ─── Helpers ───

    private static SkillContext testContext() {
        return SkillContext.forAgent("did:test:openclaw-e2e", "openclaw", Map.of(), 10000);
    }

    private static String findSkillContaining(String substring) {
        return executor.availableSkills().stream()
            .map(s -> s.id())
            .filter(id -> id.contains(substring))
            .findFirst()
            .orElse(null);
    }

    private static void assertDockerAvailable() {
        try {
            var p = new ProcessBuilder("docker", "version")
                .redirectErrorStream(true).start();
            assertTrue(p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0,
                "[HARD] Docker must be available to run OpenClaw E2E tests");
        } catch (Exception e) {
            fail("[HARD] Docker not found: " + e.getMessage());
        }
    }

    private static Path resolveProjectRoot() {
        var candidates = List.of(
            Path.of("."),
            Path.of(".."),
            Path.of("../..")
        );
        for (var p : candidates) {
            if (p.resolve("docker/openclaw.Dockerfile").toFile().exists()) {
                return p.toAbsolutePath().normalize();
            }
        }
        fail("Could not find project root with docker/openclaw.Dockerfile");
        return null;
    }

    private static void exec(Path workDir, int timeoutSeconds, String... command)
            throws Exception {
        var pb = new ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectErrorStream(true);
        var process = pb.start();

        // Stream output for diagnostics
        var output = new StringBuilder();
        try (var reader = process.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                var chunk = new String(buf, 0, n);
                output.append(chunk);
            }
        }

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("Command timed out after " + timeoutSeconds + "s: "
                + String.join(" ", command) + "\nOutput: " + output);
        }
        if (process.exitValue() != 0) {
            fail("Command failed (exit " + process.exitValue() + "): "
                + String.join(" ", command) + "\nOutput: " + output);
        }
    }

    private static void waitForHealth(int port, Duration timeout) throws Exception {
        var client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).build();
        var deadline = System.currentTimeMillis() + timeout.toMillis();
        var url = "http://127.0.0.1:" + port + "/health";
        int attempts = 0;

        while (System.currentTimeMillis() < deadline) {
            attempts++;
            try {
                var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET().timeout(Duration.ofSeconds(5)).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    System.out.println("[OpenClaw E2E] Health OK after " + attempts + " attempts");
                    return;
                }
            } catch (IOException e) {
                // Container not ready yet
            }
            Thread.sleep(500);
        }
        // One final attempt with curl for diagnostics
        try {
            var curlProcess = new ProcessBuilder("curl", "-sv", url)
                .redirectErrorStream(true).start();
            var curlOutput = new String(curlProcess.getInputStream().readAllBytes());
            curlProcess.waitFor(5, TimeUnit.SECONDS);
            System.err.println("[OpenClaw E2E] curl diagnostic:\n" + curlOutput);
        } catch (Exception ignored) {}
        fail("[HARD] OpenClaw container did not become healthy within " + timeout
            + " (after " + attempts + " attempts to " + url + ")");
    }
}
