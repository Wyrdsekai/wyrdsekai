package org.wyrdsekai.e2e.tier3;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.coding.AuthMode;
import org.wyrdsekai.core.coding.AuthResolver;
import org.wyrdsekai.core.coding.GooseBackend;
import org.wyrdsekai.core.coding.GooseRuntimeConfig;
import org.wyrdsekai.core.recipe.CodingBackendDispatcher;
import org.wyrdsekai.core.recipe.ProcessCommandRunner;
import org.wyrdsekai.core.recipe.RecipeParser;
import org.wyrdsekai.core.recipe.RecipeRunner;
import org.wyrdsekai.core.recipe.StepKind;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * first true live E2E proof that the BACKEND step seam works
 * end-to-end against real goose. Smallest possible cross-section: one BACKEND
 * step that asks goose to write a tiny JSON file, plus a success-contract that
 * asserts the file landed.
 *
 * <p>Wire shape (mirrors live agent dispatch):</p>
 * <pre>
 *   RecipeRunner.run(probeManifest, params)
 *     → BACKEND step
 *       → CodingBackendDispatcher.dispatch
 *         → GooseBackend.submitTask (production wire)
 *           → goose CLI subprocess → local 9B :8200 → file-write tool
 *     → contractHolds("file:&lt;path&gt; exists") → true
 *   Outcome: Status.SUCCESS, 1 outcome, the file is on disk
 * </pre>
 *
 * <p><b>Gated</b> on {@code WYRDSEKAI_LIVE_GOOSE_E2E=1} + {@code goose --version}
 * working + {@code :8200/v1/models} reachable. Skipped silently in CI; safe to
 * leave in the suite. Mirrors {@link GooseLiveInvocationE2ETest} but proves
 * the recipe-runner seam, not the items-as-tools chain.
 *
 * <p>To run on home-server:
 * <pre>
 *   WYRDSEKAI_LIVE_GOOSE_E2E=1 ./gradlew :e2e-test:test \
 *     --tests "org.wyrdsekai.e2e.tier3.RecipeBackendStepLiveTest"
 * </pre>
 *
 * <p><b>What this catches that fixture-based recipe tests don't:</b></p>
 * <ul>
 *   <li>{@code CodingBackendDispatcher} → real {@code GooseBackend.submitTask}
 *       round-trip — the prior {@code CodingBackendDispatcherTest} stubs the
 *       backend. If the dispatcher misroutes BACKEND step prompts (e.g.
 *       loses success-contract semantics, leaks ITEMS_AS_TOOLS preamble in a
 *       way that confuses the model on shell tasks, drops the timeout),
 *       this test surfaces it.</li>
 *   <li>The whole "agents own ML" claim depends on this seam working. Until
 *       this passes there's no evidence it does.</li>
 * </ul>
 */
@Tag("tier3")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_LIVE_GOOSE_E2E", matches = "1|true")
class RecipeBackendStepLiveTest {

    private static final String LLAMA_BASE_URL = "http://localhost:8200";
    private static final String LLAMA_HEALTH_URL = LLAMA_BASE_URL + "/v1/models";
    private static final String MODEL_ID = "wyrdsekai-3.5-9b-v5-q4km.gguf";
    private static final Duration BACKEND_TIMEOUT = Duration.ofMinutes(5);

    private Path workspace;
    private Path probeOutput;
    private String runId;
    private GooseBackend backend;
    private CodingBackendDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        assumeThat(canRunGoose())
            .as("goose --version must succeed; install via `wyrd coding install goose`")
            .isTrue();
        assumeThat(canReachLlamaServer())
            .as("local llama-server :8200 must respond at /v1/models")
            .isTrue();
        // Cold-start fix (#1003): /v1/models returns immediately even when
        // the GGUF hasn't been mmap'd into RAM yet. The first real inference
        // call then takes 20–60s while llama-server loads it, which trips
        // the goose subprocess timeout and surfaces as a flake. Issue an
        // explicit warm-up turn so the model is hot before the recipe
        // dispatches. Cheap and idempotent.
        assumeThat(warmupLlamaServer())
            .as("llama-server :8200 warm-up turn must succeed within 90s; "
              + "cold-mmap of the GGUF can otherwise time out the BACKEND step")
            .isTrue();

        runId = UUID.randomUUID().toString().substring(0, 8);
        workspace = Files.createTempDirectory("wyrd-e2e-recipe-backend-");
        probeOutput = workspace.resolve("probe-result.json");

        var config = new GooseRuntimeConfig(
            true,                   // enabled — defaults() ships disabled
            "goose",
            "openai",
            MODEL_ID,
            LLAMA_BASE_URL,         // sans /v1 — see GooseLiveInvocationE2ETest note + task #982
            Duration.ofMinutes(8),
            List.of());

        AuthResolver auth = name -> new AuthMode.ApiKey("not-required");
        backend = new GooseBackend(config, auth);
        dispatcher = new CodingBackendDispatcher(
            backend, "did:wyrd:e2e-test-recipe", BACKEND_TIMEOUT);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (workspace != null) {
            System.out.println("[RecipeBackendStepLiveTest] workspace preserved at "
                + workspace + " for post-mortem inspection");
        }
        if (probeOutput != null && Files.exists(probeOutput)) {
            Files.delete(probeOutput);
        }
    }

    @Test
    void backend_step_dispatches_to_live_goose_and_success_contract_holds() {
        // Probe recipe — one BACKEND step. The success contract MUST be the
        // signal of truth, not the model's text. Goose writes the file at the
        // path we name; contractHolds("file:<path> exists") checks the file
        // is actually on disk before declaring success.
        //
        // The prompt is deliberately blunt: "use file-write tool, write this
        // exact content to this exact path, then stop." Avoids the model
        // talking instead of acting (which was the failure mode in the prior
        // GooseLiveInvocationE2ETest debugging).
        var yaml = """
            recipe: probe-backend-step-{0}
            version: 0.1.0
            description: One BACKEND step probe — proves dispatcher → goose → contract.
            deploys: false
            ownership: run
            steps:
              - id: write-probe-file
                kind: BACKEND
                prompt: |
                  Use the file-write tool to create {1}. The file content must be
                  exactly this JSON (no extra fields, no markdown fence):
                  {"ok":true,"run_id":"{0}"}
                  Use the file-write tool. Then stop — no follow-up, no other files.
                tools: [shell]
                success_contract: "file:{1} exists"
            """
            .replace("{0}", runId)
            .replace("{1}", probeOutput.toString());

        var manifest = RecipeParser.parseManifest(yaml);

        var commands = new ProcessCommandRunner(
            workspace.toFile(), Duration.ofSeconds(30));
        var runner = new RecipeRunner(commands, dispatcher);

        System.out.println("=== RecipeBackendStepLiveTest — dispatching probe ===");
        System.out.println("=== workspace: " + workspace + " ===");
        System.out.println("=== expected output: " + probeOutput + " ===");
        var startedAt = Instant.now();

        var result = runner.run(manifest, Map.of());

        var elapsedS = Duration.between(startedAt, Instant.now()).toSeconds();
        System.out.println("=== Recipe completed in " + elapsedS + "s — status="
            + result.status() + " ===");
        result.outcomes().forEach(o -> System.out.println(
            "  step " + o.id() + " (" + o.kind() + ") ok=" + o.ok() + " :: " + o.detail()));

        // Dump the RecipeContext — CodingBackendDispatcher writes the goose
        // result + any error into ctx under "<step-id>.status" / ".summary" /
        // ".error". Without this dump, "backend reported failure" outcomes are
        // opaque.
        System.out.println("=== RecipeContext after run ===");
        // RecipeContext exposes view() returning a Map snapshot
        result.context().snapshot().forEach((k, v) -> {
            String vs = v == null ? "null" : v.toString();
            if (vs.length() > 500) vs = vs.substring(0, 500) + "…[truncated]";
            System.out.println("  " + k + " = " + vs);
        });
        System.out.println("=== End context ===");

        // ─── Primary assertion: the recipe runner reports SUCCESS ─────────
        assertThat(result.status())
            .as("recipe must SUCCEED; got: %s :: %s\nOutcomes:\n  %s",
                result.status(), result.message(),
                result.outcomes().stream().map(Object::toString).reduce("", (a, b) -> a + "\n  " + b))
            .isEqualTo(RecipeRunner.Status.SUCCESS);

        // The single BACKEND step must have succeeded
        var backendOutcome = result.outcomes().stream()
            .filter(o -> o.kind() == StepKind.BACKEND)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no BACKEND outcome found in: " + result.outcomes()));
        assertThat(backendOutcome.ok())
            .as("BACKEND step must report ok; outcome: %s", backendOutcome)
            .isTrue();

        // ─── Ground-truth assertion: the file goose was asked to write is on disk ─
        assertThat(Files.exists(probeOutput))
            .as("goose must have written %s — if missing, dispatcher → goose → "
                + "file-write chain is broken even when status says SUCCESS",
                probeOutput)
            .isTrue();

        try {
            var content = Files.readString(probeOutput);
            System.out.println("=== Probe file content ===\n" + content + "\n=== End ===");
            // Don't strict-assert content shape — goose's model may add comments
            // or reorder. The file existing + recipe SUCCESS is the load-bearing
            // signal. The prior GooseLiveInvocationE2ETest already proves the
            // model can produce structured output; here we only need to prove
            // the dispatcher seam.
            assertThat(content).contains("ok");
        } catch (Exception e) {
            throw new AssertionError("probe file unreadable: " + e.getMessage(), e);
        }

        System.out.println("=== Live verify PASSED — recipe → goose → contract chain green ===");
    }

    // -- helpers --------------------------------------------------------------

    private static boolean canRunGoose() {
        try {
            var proc = new ProcessBuilder("goose", "--version")
                .redirectErrorStream(true).start();
            var ok = proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0;
            if (!ok) proc.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean canReachLlamaServer() {
        try {
            var resp = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build()
                .send(HttpRequest.newBuilder(URI.create(LLAMA_HEALTH_URL))
                    .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Send a single 5-token completion to llama-server so the GGUF is hot
     * before the BACKEND step dispatches. Without this, the first
     * inference call inside goose can take 20–60s on the cold-mmap path,
     * which has caused the failed-then-passed flake under the
     * 5-minute BACKEND_TIMEOUT (#1003).
     */
    private static boolean warmupLlamaServer() {
        try {
            var body = "{\"model\":\"" + MODEL_ID + "\","
                + "\"max_tokens\":5,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
            var resp = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build()
                .send(HttpRequest.newBuilder(URI.create(LLAMA_BASE_URL + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                    HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
