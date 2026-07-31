package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.coding.BackendRegistry;
import org.wyrdsekai.core.coding.BackendTier;
import org.wyrdsekai.core.coding.CodingArtifact;
import org.wyrdsekai.core.coding.TaskResult;
import org.wyrdsekai.core.coding.TaskSpec;
import org.wyrdsekai.core.coding.TaskStatus;
import org.wyrdsekai.core.coding.TestCodingTaskBackend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** — BACKEND step dispatch to a coding backend + success-contract handling. */
class CodingBackendDispatcherTest {

    /** Fake coding backend returning a fixed status + summary (no LLM, no GPU). */
    private static TestCodingTaskBackend fake(TaskStatus status, String summary) {
        return new TestCodingTaskBackend() {
            @Override public String name() { return "fake"; }
            @Override public BackendTier tier() { return BackendTier.LOCAL_FREE; }
            @Override public CompletableFuture<TaskResult> submitTask(TaskSpec spec) {
                return CompletableFuture.completedFuture(
                        new TaskResult(spec.taskId(), "fake", status, summary, List.of(), 0L, 1L));
            }
            @Override public Stream<CodingArtifact> artifactsFor(String taskId) { return Stream.empty(); }
            @Override public CompletableFuture<Boolean> healthCheck() { return CompletableFuture.completedFuture(true); }
            @Override public long estimatedCu(TaskSpec spec) { return 0L; }
        };
    }

    private static RecipeStep.Backend backendStep() {
        return new RecipeStep.Backend("probe", "do the thing", List.of("shell"), "exit:0");
    }

    @Test void succeeded_backend_satisfies_exit_contract() {
        var d = new CodingBackendDispatcher(fake(TaskStatus.SUCCEEDED, "done"), "did:c", Duration.ofSeconds(5));
        var ctx = new RecipeContext();
        assertTrue(d.dispatch(backendStep(), ctx));
        assertEquals("SUCCEEDED", ctx.get("probe.status"));
    }

    @Test void failed_backend_fails_the_step() {
        var d = new CodingBackendDispatcher(fake(TaskStatus.FAILED, "nope"), "did:c", Duration.ofSeconds(5));
        assertFalse(d.dispatch(backendStep(), new RecipeContext()));
    }

    @Test void file_contract_requires_the_file_to_exist(@TempDir Path tmp) throws Exception {
        Path produced = tmp.resolve("expanded.jsonl");
        var present = new RecipeStep.Backend("b", "make it", List.of(), "file:" + produced + " exists");
        var d = new CodingBackendDispatcher(fake(TaskStatus.SUCCEEDED, ""), null, Duration.ofSeconds(5));

        assertFalse(d.dispatch(present, new RecipeContext()), "missing file → contract not met");
        Files.writeString(produced, "{}\n");
        assertTrue(d.dispatch(present, new RecipeContext()), "file present → contract met");
    }

    @Test void shell_only_backend_step_tags_taskType_for_preamble_skip() {
        // Recipe BACKEND steps whose tools list is exactly [shell] are
        // tagged taskType=shell-exec so Goose/OpenCode/OpenHands skip the
        // items-as-tools preamble wrap (which biases the model toward
        // scripted-item generation when the recipe wants raw shell).
        // + B2 live-verify findings; #1009 closure.
        AtomicReference<String> seenTaskType =
                new AtomicReference<>();
        var capturingBackend = new TestCodingTaskBackend() {
            @Override public String name() { return "capture"; }
            @Override public BackendTier tier() { return BackendTier.LOCAL_FREE; }
            @Override public CompletableFuture<TaskResult> submitTask(TaskSpec spec) {
                seenTaskType.set(spec.taskType());
                return CompletableFuture.completedFuture(
                        new TaskResult(spec.taskId(), "capture", TaskStatus.SUCCEEDED,
                                "", List.of(), 0L, 1L));
            }
            @Override public Stream<CodingArtifact> artifactsFor(String taskId) { return Stream.empty(); }
            @Override public CompletableFuture<Boolean> healthCheck() { return CompletableFuture.completedFuture(true); }
            @Override public long estimatedCu(TaskSpec spec) { return 0L; }
        };
        var d = new CodingBackendDispatcher(capturingBackend, "did:c", Duration.ofSeconds(5));

        // tools=[shell] → shell-exec
        var shellStep = new RecipeStep.Backend("s", "run a shell command", List.of("shell"), "exit:0");
        d.dispatch(shellStep, new RecipeContext());
        assertEquals(CodingBackendDispatcher.TASK_TYPE_SHELL_EXEC, seenTaskType.get(),
                "single-shell tools list should tag taskType=shell-exec");

        // tools=[] (default scripted-item path) → default "code"
        var emptyToolsStep = new RecipeStep.Backend("e", "do the thing", List.of(), "exit:0");
        d.dispatch(emptyToolsStep, new RecipeContext());
        assertEquals("code", seenTaskType.get(),
                "no-tools step should stay on default taskType=code (items-as-tools path)");

        // tools=[shell, web] (mixed) → default "code" (not pure shell)
        var mixedStep = new RecipeStep.Backend("m", "mixed", List.of("shell", "web"), "exit:0");
        d.dispatch(mixedStep, new RecipeContext());
        assertEquals("code", seenTaskType.get(),
                "mixed tools should keep default taskType (only pure-shell short-circuits)");
    }

    @Test void file_contract_holds_even_when_backend_exits_nonzero(@TempDir Path tmp) throws Exception {
        // authoring intent: when the recipe's success_contract
        // names a specific file as the deliverable, the file's existence is
        // authoritative. Items-as-tools-preamble adapters (goose/opencode)
        // periodically emit a spurious follow-up tool call after producing
        // the expected file, exiting non-zero; that must NOT discard the
        // real artifact the recipe asked for.
        Path produced = tmp.resolve("evolved.jsonl");
        Files.writeString(produced, "{}\n");
        var step = new RecipeStep.Backend("b", "make it", List.of(),
                "file:" + produced + " exists");
        var d = new CodingBackendDispatcher(fake(TaskStatus.FAILED, "spurious follow-up tool 404"),
                null, Duration.ofSeconds(5));
        assertTrue(d.dispatch(step, new RecipeContext()),
                "FAILED + file-present must hold for file: contracts (release-bake B2 fix)");
    }

    @Test void backend_can_feed_a_value_to_a_later_gate() {
        // The backend's JSON summary merges into the context so the GATE can read it (in-runtime).
        var dispatcher = new CodingBackendDispatcher(
                fake(TaskStatus.SUCCEEDED, "{\"overrouting_probe_passes\": true}"), "did:c", Duration.ofSeconds(5));
        String yaml = """
            recipe: t-backend-gate
            steps:
              - { id: probe, kind: BACKEND, prompt: "run the probe", success_contract: "exit:0" }
              - { id: gate, kind: GATE, condition: "overrouting_probe_passes == true", on_fail: STOP }
            """;
        var run = new RecipeRunner(c -> new CommandRunner.Result(0, "", ""), dispatcher)
                .run(RecipeParser.parseManifest(yaml), Map.of());
        assertEquals(RecipeRunner.Status.SUCCESS, run.status(),
                "backend output should satisfy the downstream gate; outcomes=" + run.outcomes());
        assertEquals(Boolean.TRUE, run.context().get("overrouting_probe_passes"));
    }

    @Test void using_preferred_picks_first_registered_in_priority_order() {
        var reg = BackendRegistry.get();
        String hi = "pref-hi-" + UUID.randomUUID();
        String lo = "pref-lo-" + UUID.randomUUID();
        reg.register(named(lo));   // only the lower-priority one is registered
        // hi not registered → falls through to lo
        assertTrue(CodingBackendDispatcher.usingPreferred(List.of(hi, lo), "did:c", Duration.ofSeconds(5)).isPresent());
        // both absent → empty (BACKEND steps then stay NEEDS_BACKEND)
        assertFalse(CodingBackendDispatcher.usingPreferred(
                List.of("definitely-absent-" + UUID.randomUUID()), "did:c", Duration.ofSeconds(5)).isPresent());
    }

    private static TestCodingTaskBackend named(String name) {
        return new TestCodingTaskBackend() {
            @Override public String name() { return name; }
            @Override public BackendTier tier() { return BackendTier.LOCAL_FREE; }
            @Override public CompletableFuture<TaskResult> submitTask(TaskSpec spec) {
                return CompletableFuture.completedFuture(
                        new TaskResult(spec.taskId(), name, TaskStatus.SUCCEEDED, "", List.of(), 0L, 0L));
            }
            @Override public Stream<CodingArtifact> artifactsFor(String taskId) { return Stream.empty(); }
            @Override public CompletableFuture<Boolean> healthCheck() { return CompletableFuture.completedFuture(true); }
            @Override public long estimatedCu(TaskSpec spec) { return 0L; }
        };
    }

    @Test void backend_failure_halts_recipe_before_gate() {
        var dispatcher = new CodingBackendDispatcher(fake(TaskStatus.FAILED, ""), "did:c", Duration.ofSeconds(5));
        String yaml = """
            recipe: t-backend-fail
            steps:
              - { id: probe, kind: BACKEND, prompt: "run the probe", success_contract: "exit:0" }
              - { id: after, kind: SHELL, command: "echo should-not-run" }
            """;
        var run = new RecipeRunner(c -> new CommandRunner.Result(0, "", ""), dispatcher)
                .run(RecipeParser.parseManifest(yaml), Map.of());
        assertEquals(RecipeRunner.Status.STEP_FAILED, run.status());
    }
}
