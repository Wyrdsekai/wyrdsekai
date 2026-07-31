package org.wyrdsekai.e2e.tier2;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.coding.AuthMode;
import org.wyrdsekai.core.coding.AuthResolver;
import org.wyrdsekai.core.coding.CodexCliBackend;
import org.wyrdsekai.core.coding.CodexCliRuntimeConfig;
import org.wyrdsekai.core.coding.SourceArtifact;
import org.wyrdsekai.core.coding.TaskSpec;
import org.wyrdsekai.core.coding.TaskStatus;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tier 2 E2E for the OpenAI Codex CLI coding backend
 * ({@link CodexCliBackend}, / Phase 2e).
 * Mirrors {@link ClaudeSdkE2ETest}'s shape — health, simple submit,
 * items-as-tools.
 *
 * <p>Two env gates:
 * <ul>
 *   <li>{@code WYRDSEKAI_E2E_CODEX_CLI=1} — opts the suite in.
 *       Set by {@code scripts/training/coding/run_codex_cli_e2e.sh}.</li>
 *   <li>{@code OPENAI_API_KEY} OR
 *       {@code WYRDSEKAI_E2E_CODEX_USE_OAUTH=1} — picks the auth path.
 *       Without either, individual tests {@code Assumptions.assumeTrue}
 *       skip with a clean reason.</li>
 * </ul></p>
 *
 * <p>Run: {@code WYRDSEKAI_E2E_CODEX_CLI=1 OPENAI_API_KEY=sk-…
 * ./gradlew :e2e-test:test -PincludeTags=e2e --tests "*CodexCliE2ETest"}</p>
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_CODEX_CLI", matches = "1|true|yes")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodexCliE2ETest {

    private static final Duration TASK_TIMEOUT = Duration.ofMinutes(3);

    /**
     * Provider override — null lets codex pick its default
     * (typically OpenAI). Override via {@code WYRDSEKAI_CODEX_PROVIDER}.
     */
    private static final String PROVIDER = System.getenv()
        .getOrDefault("WYRDSEKAI_CODEX_PROVIDER", "");

    static AuthResolver envAuthResolver() {
        return name -> {
            var apiKey = System.getenv("OPENAI_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                apiKey = System.getenv("CODEX_API_KEY");
            }
            if (apiKey != null && !apiKey.isBlank()) {
                return new AuthMode.ApiKey(apiKey);
            }
            var oauth = System.getenv("WYRDSEKAI_E2E_CODEX_USE_OAUTH");
            if ("1".equals(oauth) || "true".equalsIgnoreCase(oauth)) {
                return new AuthMode.OAuthSession();
            }
            return new AuthMode.AuthMissing(name,
                "set OPENAI_API_KEY or WYRDSEKAI_E2E_CODEX_USE_OAUTH=1",
                "no auth env wired for CodexCliE2ETest");
        };
    }

    private static boolean authAvailable() {
        var k = System.getenv("OPENAI_API_KEY");
        if (k != null && !k.isBlank()) return true;
        k = System.getenv("CODEX_API_KEY");
        if (k != null && !k.isBlank()) return true;
        var o = System.getenv("WYRDSEKAI_E2E_CODEX_USE_OAUTH");
        return "1".equals(o) || "true".equalsIgnoreCase(o);
    }

    private static CodexCliBackend liveBackend() {
        var cfg = new CodexCliRuntimeConfig(
            true,
            CodexCliRuntimeConfig.DEFAULT_EXECUTABLE,
            PROVIDER.isBlank() ? null : PROVIDER,
            (int) TASK_TIMEOUT.toMinutes(),
            List.of()
        );
        return new CodexCliBackend(cfg, envAuthResolver());
    }

    // ── Task 1 ──────────────────────────────────────────────────────

    @Test @Order(1)
    void task1_backend_health() throws Exception {
        var backend = liveBackend();
        var healthy = backend.healthCheck()
            .get(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS);
        assertTrue(healthy,
            "codex --version probe must succeed before running E2E. "
                + "Install: see https://github.com/openai/codex (Rust binary). "
                + "WYRDSEKAI_E2E_CODEX_CLI=1 was set but the binary is missing.");
    }

    // ── Task 2 ──────────────────────────────────────────────────────

    @Test @Order(2)
    void task2_simple_submit() throws Exception {
        assumeTrue(authAvailable(),
            "Skipping live submit — no OPENAI_API_KEY / CODEX_API_KEY / "
                + "WYRDSEKAI_E2E_CODEX_USE_OAUTH=1 in env.");

        var backend = liveBackend();
        var spec = TaskSpec.create("did:key:codex-e2e", "code",
            "Reply with exactly the string OK and nothing else.");
        var fut = backend.submitTask(spec);
        var result = fut.get(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        assertNotNull(result, "submitTask must produce a TaskResult");
        System.out.println("[codex task2] status=" + result.status()
            + "  durationMs=" + result.durationMs()
            + "  cu=" + result.cuConsumed()
            + "  summary=" + result.summary());

        if (result.status() == TaskStatus.FAILED
                && result.summary() != null
                && result.summary().contains("LOGIN_REQUIRED")) {
            throw new AssertionError("Auth resolver returned LOGIN_REQUIRED "
                + "even though authAvailable() said true. " + result.summary());
        }
        assertEquals(TaskStatus.SUCCEEDED, result.status(),
            "Expected SUCCEEDED, got " + result.status() + " — " + result.summary());
        assertNotNull(result.summary(), "result.summary must be non-null");
        assertTrue(result.summary().toUpperCase().contains("OK"),
            "Expected 'OK' in summary, got: " + result.summary());
    }

    // ── Task 3 ──────────────────────────────────────────────────────

    @Test @Order(3)
    void task3_items_as_tools_shape() throws Exception {
        assumeTrue(authAvailable(),
            "Skipping items-as-tools task — no auth env wired.");

        var taskPrompt = "Write a single Wyrdsekai item-as-tool named "
            + "`echo_item` (version 1.0.0, no capabilities, author "
            + "did:key:e2e) whose `invoke(params)` returns "
            + "{ ok: true, summary: \"echoed: \" + (params.text || \"\") }. "
            + "Output ONLY the .js file contents — no commentary.";

        var backend = liveBackend();
        var spec = TaskSpec.create("did:key:codex-e2e", "code", taskPrompt);
        var fut = backend.submitTask(spec);
        var result = fut.get(TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        System.out.println("[codex task3] status=" + result.status()
            + "  durationMs=" + result.durationMs()
            + "  artifacts=" + result.artifactIds().size());
        assertEquals(TaskStatus.SUCCEEDED, result.status(),
            "Expected SUCCEEDED, got " + result.status() + " — " + result.summary());

        var artifacts = backend.artifactsFor(result.taskId().toString())
            .toList();
        if (!artifacts.isEmpty() && artifacts.get(0) instanceof SourceArtifact src) {
            System.out.println("[codex task3] artifact workspacePath="
                + src.workspacePath() + "  files=" + src.files());
        }
        var body = result.summary() == null ? "" : result.summary();
        System.out.println("[codex task3] summary chars=" + body.length());
        assertTrue(body.contains("exports.manifest"),
            "Items-as-tools output must declare exports.manifest. Got:\n" + body);
        assertTrue(body.contains("function invoke") || body.contains("invoke ="),
            "Items-as-tools output must define invoke. Got:\n" + body);
    }
}
