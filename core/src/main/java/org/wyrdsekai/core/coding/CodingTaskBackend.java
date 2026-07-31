package org.wyrdsekai.core.coding;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Sealed abstraction over a coding-task backend (CodePlane, OpenCode, OpenHands,
 * Goose, Cline, Continue, Claude Code SDK, Codex CLI, Gemini CLI, Devin).
 *
 * <p>Mirrors the {@link org.wyrdsekai.core.inference.InferenceBackend} pattern:
 * a sealed interface with one record per concrete backend. Each Phase 2
 * subphase extends the {@code permits} clause as new backends land.</p>
 *
 * <p>.</p>
 */
public sealed interface CodingTaskBackend
        permits CodePlaneBackend, OpenCodeBackend, OpenHandsBackend,
                GooseBackend, ClineBackend, ContinueBackend,
                ClaudeSdkBackend, CodexCliBackend, GeminiCliBackend, DevinBackend,
                PiCodingBackend,
                TestCodingTaskBackend {

    /** Stable identifier ({@code "codeplane"}, {@code "opencode"}, …). */
    String name();

    /** Display tier for cost / policy decisions. */
    BackendTier tier();

    /**
     * Submit a coding task. Returns a future that completes when the task
     * finishes, fails, is cancelled, or times out (see {@link TaskStatus}).
     *
     * <p>The future MUST always complete — backends translate transport
     * errors into {@link TaskStatus#FAILED} results rather than completing
     * the future exceptionally, so callers don't have to bolt on retry
     * logic for every infrastructure failure mode.</p>
     */
    CompletableFuture<TaskResult> submitTask(TaskSpec spec);

    /**
     * Stream the artifacts produced by a previously-completed task.
     *
     * <p>Empty stream is a valid answer (task produced no artifacts, or
     * artifacts have aged out of the backend's retention window).</p>
     */
    Stream<CodingArtifact> artifactsFor(String taskId);

    /**
     * Probe — is this backend currently usable?
     *
     * <p>Selection policy uses this to skip down-backends in the fallback
     * chain. Implementations should be cheap (a TCP probe / HTTP HEAD /
     * subprocess --version) — this gets called frequently.</p>
     */
    CompletableFuture<Boolean> healthCheck();

    /**
     * Per-task-type CU estimate, used by {@link
     * org.wyrdsekai.core.protection.ActionPolicy} to pre-gate work against
     * the agent's daily budget.
     *
     * <p>Free-tier backends ({@link BackendTier#LOCAL_FREE}) typically
     * return {@code 0L}. Paid-tier backends estimate based on description
     * length and recent average task cost.</p>
     */
    long estimatedCu(TaskSpec spec);

    // ─── Phase C — invocable artifacts ──────────────────────────────
    //
    // the methods below let the
    // namespace handler dispatch `<backend>.run` / `.examine` /
    // `.test` / `.diff` / `.log` / `.deploy` / `.destroy` against a
    // produced artifact. Default impls return "unsupported" so a
    // backend can opt in incrementally without breaking existing
    // permits — handlers narrate the unsupported case distinctly from
    // a real failure.

    /**
     * Locate the on-host workspace directory for an artifact, when
     * the backend keeps the workspace materialised on disk. Returns
     * empty when the artifact is unknown or the backend does not
     * surface workspaces (e.g. cloud-only).
     */
    default Optional<Path> workspacePathFor(UUID artifactId) {
        return Optional.empty();
    }

    /**
     * Execute the artifact's primary entrypoint. Stdout / stderr go
     * into the returned {@link ExecResult}; the entrypoint string
     * (e.g. {@code "python3 main.py"}) is captured for narration.
     *
     * @param artifactId  the artifact (codex or build) to run
     * @param args        argv passed after the entrypoint (never null)
     * @param env         additional environment variables. Backends
     *                    MUST NOT inherit the parent process env
     *                    wholesale; only the keys provided here plus
     *                    a minimal hygiene set (PATH, HOME, LANG)
     *                    should reach the child.
     */
    default CompletableFuture<ExecResult> runArtifact(UUID artifactId,
                                                       List<String> args,
                                                       Map<String, String> env) {
        return CompletableFuture.completedFuture(ExecResult.unsupported(name(), "run"));
    }

    /**
     * Run the artifact's tests (e.g. {@code pytest}, {@code make
     * test}, {@code cargo test}). Default: unsupported.
     */
    default CompletableFuture<ExecResult> testArtifact(UUID artifactId) {
        return CompletableFuture.completedFuture(ExecResult.unsupported(name(), "test"));
    }

    /**
     * Surface a richer view than {@link #artifactsFor}: file list
     * with previews of each file's content (truncated). Default:
     * unsupported (callers fall back to the cached metadata stub).
     */
    default CompletableFuture<ExamineResult> examineArtifact(UUID artifactId) {
        return CompletableFuture.completedFuture(ExamineResult.unsupported(name()));
    }

    /**
     * Destroy the workspace + drop the cached artifact entry. Returns
     * {@code true} on success, {@code false} when the artifact is
     * unknown or the backend can't reclaim the workspace.
     */
    default CompletableFuture<Boolean> destroyArtifact(UUID artifactId) {
        return CompletableFuture.completedFuture(false);
    }
}
