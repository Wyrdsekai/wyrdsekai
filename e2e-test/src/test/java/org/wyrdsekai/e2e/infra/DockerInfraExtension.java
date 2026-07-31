package org.wyrdsekai.e2e.infra;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JUnit 5 extension that auto-launches Docker Compose services for E2E tests.
 *
 * <p>Activated by {@link DockerProfile @DockerProfile} on a test class.
 * On {@code beforeAll}:
 * <ol>
 *   <li>Reads the required profile from the annotation
 *   <li>Checks if Docker is available (skips via assumption if not)
 *   <li>Checks if all required services are already healthy
 *   <li>If not, runs {@code docker compose up -d} with the appropriate profile
 *   <li>Waits for all services to pass health checks
 * </ol>
 *
 * <p>Services are shared across test classes in the same JVM — once a profile
 * is started, subsequent test classes with the same profile reuse the running
 * containers. Cleanup happens via JVM shutdown hook unless
 * {@code WYRDSEKAI_E2E_DOCKER_PERSIST=true} is set.
 *
 * <p>Pre-existing containers (healthy before the extension runs) are never
 * torn down, even on JVM exit.
 */
public class DockerInfraExtension implements BeforeAllCallback {

    private static final Logger log = LoggerFactory.getLogger(DockerInfraExtension.class);

    // ─── Service health definitions ──────────────────────────────────────────

    record ServiceHealth(String name, String healthUrl, Duration timeout) {
        boolean isHealthy() {
            try {
                var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3)).build();
                var req = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .timeout(Duration.ofSeconds(3)).GET().build();
                var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                return resp.statusCode() == 200;
            } catch (Exception e) {
                return false;
            }
        }
    }

    /**
     * Port resolution honors {@code WYRDSEKAI_E2E_<SVC>_PORT} env vars so the
     * harness can run alongside a live wyrdsekai mesh on the same host (home-server).
     * docker/.env is not consulted — it's for live-mesh ops, not e2e.
     */
    private static int envPort(String name, int dflt) {
        var v = System.getenv(name);
        if (v == null || v.isBlank()) return dflt;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return dflt; }
    }

    private static final int NATS_PORT = envPort("WYRDSEKAI_E2E_NATS_PORT", 4222);
    private static final int NATS_MONITOR_PORT = envPort("WYRDSEKAI_E2E_NATS_MONITOR_PORT", 8222);
    private static final int SGLANG_PORT = envPort("WYRDSEKAI_E2E_SGLANG_PORT", 8000);
    private static final int VLLM_PORT = envPort("WYRDSEKAI_E2E_VLLM_PORT", 8100);
    private static final int LLAMA_PORT = envPort("WYRDSEKAI_E2E_LLAMA_PORT", 8080);
    private static final int LLAMA_PHONE_PORT = envPort("WYRDSEKAI_E2E_LLAMA_PHONE_PORT", 8081);
    private static final int LLAMA_LAPTOP_PORT = envPort("WYRDSEKAI_E2E_LLAMA_LAPTOP_PORT", 8082);
    private static final int LLAMA_DRIVE_PORT = envPort("WYRDSEKAI_E2E_LLAMA_DRIVE_PORT", 8083);

    static final Map<String, ServiceHealth> HEALTH_CHECKS = Map.of(
        "nats", new ServiceHealth("NATS", "http://localhost:" + NATS_MONITOR_PORT + "/healthz", Duration.ofSeconds(30)),
        "sglang", new ServiceHealth("SGLang", "http://localhost:" + SGLANG_PORT + "/health", Duration.ofSeconds(600)),
        "vllm", new ServiceHealth("vLLM", "http://localhost:" + VLLM_PORT + "/health", Duration.ofSeconds(600)),
        "llama-server", new ServiceHealth("llama-server", "http://localhost:" + LLAMA_PORT + "/health", Duration.ofSeconds(180)),
        "llama-phone", new ServiceHealth("llama-phone", "http://localhost:" + LLAMA_PHONE_PORT + "/health", Duration.ofSeconds(180)),
        "llama-laptop", new ServiceHealth("llama-laptop", "http://localhost:" + LLAMA_LAPTOP_PORT + "/health", Duration.ofSeconds(180))
    );

    // ─── Profile → compose profiles + required services ──────────────────────

    record ProfileDef(List<String> composeProfiles, List<String> services) {}

    static final Map<String, ProfileDef> PROFILES = Map.of(
        "nats", new ProfileDef(List.of(), List.of("nats")),
        "relay", new ProfileDef(List.of("relay"), List.of("nats", "llama-phone", "llama-laptop")),
        "sglang", new ProfileDef(List.of("sglang"), List.of("nats", "sglang")),
        "vllm", new ProfileDef(List.of("vllm"), List.of("nats", "vllm")),
        "llama", new ProfileDef(List.of("llama"), List.of("nats", "llama-server"))
    );

    // ─── Global state (shared across test classes in one JVM) ────────────────

    /** Profiles whose services are confirmed healthy (pre-existing or started). */
    private static final Set<String> activeProfiles = ConcurrentHashMap.newKeySet();
    /** Profiles we started (not pre-existing) — candidates for shutdown cleanup. */
    private static final Set<String> startedByUs = ConcurrentHashMap.newKeySet();
    private static volatile boolean shutdownHookRegistered = false;

    // ─── Static service URL accessors ────────────────────────────────────────

    /** NATS client URL (default compose port). */
    public static String natsUrl() { return "nats://localhost:" + NATS_PORT; }
    /** NATS monitor URL (for health checks). */
    public static String natsMonitorUrl() { return "http://localhost:" + NATS_MONITOR_PORT; }
    /** SGLang URL. */
    public static String sglangUrl() { return "http://localhost:" + SGLANG_PORT; }
    /** vLLM URL. */
    public static String vllmUrl() { return "http://localhost:" + VLLM_PORT; }
    /** llama-server URL (single model). */
    public static String llamaServerUrl() { return "http://localhost:" + LLAMA_PORT; }
    /** llama-phone URL (0.6B Qwen3, relay profile). */
    public static String llamaPhoneUrl() { return "http://localhost:" + LLAMA_PHONE_PORT; }
    /** llama-laptop URL (4B Qwen3, relay profile). */
    public static String llamaLaptopUrl() { return "http://localhost:" + LLAMA_LAPTOP_PORT; }
    /** llama-drive URL (9B drive, drive profile). */
    public static String llamaDriveUrl() { return "http://localhost:" + LLAMA_DRIVE_PORT; }

    // ─── Extension entry point ───────────────────────────────────────────────

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        var testClass = context.getRequiredTestClass();
        var annotation = testClass.getAnnotation(DockerProfile.class);
        if (annotation == null) {
            throw new IllegalStateException(
                "@DockerProfile annotation missing on " + testClass.getSimpleName() +
                " — add @DockerProfile(\"profile\") to the test class");
        }

        var profile = annotation.value();
        var profileDef = PROFILES.get(profile);
        if (profileDef == null) {
            throw new IllegalArgumentException(
                "Unknown Docker profile: '" + profile +
                "'. Available: " + PROFILES.keySet());
        }

        // Already ensured for this JVM run
        if (activeProfiles.contains(profile)) {
            log.debug("Docker profile '{}' already active — skipping", profile);
            return;
        }

        // Docker must be available
        DockerComposeFixture.assumeDockerAvailable();

        // Check if all required services are already healthy
        var allHealthy = profileDef.services().stream().allMatch(svc -> {
            var health = HEALTH_CHECKS.get(svc);
            if (health == null) return true; // no health check defined — assume ok
            var healthy = health.isHealthy();
            if (healthy) {
                log.info("{} already healthy at {} — reusing", health.name(), health.healthUrl());
            }
            return healthy;
        });

        if (allHealthy) {
            log.info("All services for profile '{}' already running — reusing pre-existing containers",
                profile);
            activeProfiles.add(profile);
            return;
        }

        // Start services via docker compose (without --force-recreate to preserve running ones)
        log.info("Starting Docker profile '{}': services={}, composeProfiles={}",
            profile, profileDef.services(), profileDef.composeProfiles());

        var compose = new DockerComposeFixture();
        // Set WYRDSEKAI_DATA to project root's data/ dir — compose file is in docker/
        // so ./data resolves wrong without this
        compose.env("WYRDSEKAI_DATA", resolveDataDir());
        for (var cp : profileDef.composeProfiles()) {
            compose.profile(cp);
        }
        compose.startIfNeeded(profileDef.services().toArray(String[]::new));

        // Wait for each service to become healthy
        for (var svc : profileDef.services()) {
            var health = HEALTH_CHECKS.get(svc);
            if (health == null) continue;

            log.info("Waiting for {} at {} (timeout: {}s)...",
                health.name(), health.healthUrl(), health.timeout().toSeconds());

            if (!compose.waitForHealth(health.name(), health.healthUrl(), health.timeout())) {
                throw new IllegalStateException(
                    health.name() + " failed health check after " +
                    health.timeout().toSeconds() + "s at " + health.healthUrl());
            }
        }

        activeProfiles.add(profile);
        startedByUs.add(profile);
        registerShutdownHook();

        log.info("Docker profile '{}' ready — all {} services healthy",
            profile, profileDef.services().size());
    }

    // ─── Shutdown cleanup ────────────────────────────────────────────────────

    private static synchronized void registerShutdownHook() {
        if (shutdownHookRegistered) return;

        var persist = "true".equalsIgnoreCase(
            System.getenv("WYRDSEKAI_E2E_DOCKER_PERSIST"));

        if (!persist) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (startedByUs.isEmpty()) return;

                log.info("Tearing down Docker profiles started by tests: {}", startedByUs);
                try {
                    var compose = new DockerComposeFixture();
                    for (var profile : startedByUs) {
                        var def = PROFILES.get(profile);
                        if (def != null) {
                            for (var cp : def.composeProfiles()) {
                                compose.profile(cp);
                            }
                        }
                    }
                    compose.down();
                } catch (Exception e) {
                    log.warn("Error tearing down Docker infra: {}", e.getMessage());
                }
            }, "docker-infra-cleanup"));
        } else {
            log.info("WYRDSEKAI_E2E_DOCKER_PERSIST=true — containers will persist after JVM exit");
        }

        shutdownHookRegistered = true;
    }

    /**
     * Resolve the project data directory.
     * The compose file uses {@code ${WYRDSEKAI_DATA:-./data}} which resolves relative
     * to the compose file's directory (docker/), not the project root.
     * This finds the project root's data/ directory so volume mounts work correctly.
     */
    private static String resolveDataDir() {
        // Honor explicit env var
        var envData = System.getenv("WYRDSEKAI_DATA");
        if (envData != null && !envData.isBlank()) return envData;

        // Search upward from CWD for data/ directory
        var candidates = List.of(
            Path.of("data"),
            Path.of("../data"),
            Path.of("../../data")
        );
        for (var p : candidates) {
            if (p.toFile().isDirectory()) return p.toAbsolutePath().toString();
        }
        return "./data"; // fallback — let Docker fail with clear error
    }
}
