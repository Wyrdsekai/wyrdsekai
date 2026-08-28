package org.wyrdsekai.core.recipe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.coding.BackendRegistry;
import org.wyrdsekai.core.coding.CodingTaskBackend;
import org.wyrdsekai.core.coding.TaskResult;
import org.wyrdsekai.core.coding.TaskSpec;
import org.wyrdsekai.core.coding.TaskStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link BackendDispatcher} that hands a BACKEND / GOOSE_RECIPE / LONG_JOB step
 * to a {@link CodingTaskBackend} (Pi is the configured default) and reports whether it satisfied
 * the step's success contract.
 *
 * <p>Load-bearing boundary (§4): the dispatcher only <em>executes</em> work and may write the
 * backend's output back into the {@link RecipeContext} (so a later GATE can read a metric the
 * backend emitted). It never makes deploy/rollback decisions — those stay in {@link RecipeRunner}.
 *
 * <p>Success contract handling is deliberately tiny:
 * <ul>
 *   <li>Base requirement: {@link TaskStatus#SUCCEEDED}.</li>
 *   <li>{@code "file:<path> exists"} additionally requires the file to be present.</li>
 *   <li>{@code "exit:0"} / anything else: SUCCEEDED is the signal.</li>
 * </ul>
 */
public final class CodingBackendDispatcher implements BackendDispatcher {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(CodingBackendDispatcher.class);

    private final CodingTaskBackend backend;
    private final String companionDid; // attribution; nullable for system runs
    private final Duration timeout;
    /** Workspace the backend runs in; null → the backend's own per-task scratch.
     *  A recipe's BACKEND steps reference project-relative paths ("run
     *  scripts/classifier/expand_corpus.py …"), exactly like its SHELL steps —
     *  which already run in the project via ProcessCommandRunner. Without this
     *  hint the backend gets an EMPTY sandbox, finds neither script nor seeds,
     *  and a small model then fabricates both (release bake, 2026-08-24: a
     *  104-line toy expand_corpus.py and three "quick brown fox" seeds). The
     *  sandbox default is right for companion item builds; it is wrong for
     *  recipe steps that name files in a tree. */
    private final String workspaceHint;

    public CodingBackendDispatcher(CodingTaskBackend backend, String companionDid, Duration timeout) {
        this(backend, companionDid, timeout, null);
    }

    public CodingBackendDispatcher(CodingTaskBackend backend, String companionDid, Duration timeout,
                                   String workspaceHint) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.companionDid = companionDid;
        this.timeout = timeout == null ? Duration.ofMinutes(10) : timeout;
        this.workspaceHint = workspaceHint;
    }

    /**
     * Build a dispatcher around a registered backend (e.g. {@code "pi"}). Returns empty if no
     * such backend is registered — callers then construct the runner without a dispatcher, so
     * BACKEND steps short-circuit to {@code NEEDS_BACKEND} rather than failing.
     */
    public static Optional<CodingBackendDispatcher> usingRegistered(
            String backendName, String companionDid, Duration timeout) {
        return BackendRegistry.get().backendFor(backendName)
                .map(b -> new CodingBackendDispatcher(b, companionDid, timeout));
    }

    /**
     * Build a dispatcher around the first registered backend in {@code backendNames}, in
     * priority order. Recipe BACKEND steps need a backend that <em>truthfully</em> tool-uses
     * the local model — Goose drives the local 9B correctly where Pi fabricates results — so
     * the call site passes {@code ["goose", "pi"]} to match the coding-backend default chain
     * (SPEC §2.6). Returns empty if none are registered → BACKEND steps stay
     * {@code NEEDS_BACKEND} rather than feeding a gate a fabricated verdict.
     */
    public static Optional<CodingBackendDispatcher> usingPreferred(
            List<String> backendNames, String companionDid, Duration timeout) {
        var registry = BackendRegistry.get();
        for (var name : backendNames) {
            var backend = registry.backendFor(name);
            if (backend.isPresent()) {
                return Optional.of(new CodingBackendDispatcher(backend.get(), companionDid, timeout));
            }
        }
        return Optional.empty();
    }

    /**
     * Sentinel {@link TaskSpec#taskType()} value the dispatcher sets on a
     * recipe BACKEND step whose declared {@code tools} list is
     * {@code [shell]} (and only shell). Backends that wrap prompts with
     * the items-as-tools preamble (Goose, OpenCode, OpenHands) check for
     * this tag and skip the wrap — the recipe author has named shell
     * execution as the contract, not scripted-item generation. See
     * §3.3 + B2 live-verify findings.
     */
    public static final String TASK_TYPE_SHELL_EXEC = "shell-exec";

    @Override
    public boolean dispatch(RecipeStep step, RecipeContext ctx) {
        return dispatchInternal(step, ctx, timeout).ok();
    }

    /**
     * Per-call timeout override (#1012). The recipe runner passes the effective per-step
     * timeout here so a long-running BACKEND step (e.g. retrain-classifier-head) can exceed
     * the dispatcher's default-15min budget by declaring {@code timeout: 30m} in the manifest.
     * Returns a {@link DispatchOutcome} so the runner can distinguish transient timeouts
     * (retry-eligible) from logical contract misses (halt the recipe).
     */
    @Override
    public DispatchOutcome dispatchWith(RecipeStep step, RecipeContext ctx, Duration timeoutOverride) {
        return dispatchInternal(step, ctx, timeoutOverride == null ? timeout : timeoutOverride);
    }

    private DispatchOutcome dispatchInternal(RecipeStep step, RecipeContext ctx, Duration effectiveTimeout) {
        String prompt;
        String successContract;
        String taskType = "code";
        switch (step) {
            case RecipeStep.Backend b -> {
                prompt = ctx.resolve(b.prompt());
                // Templates in the contract too — {{head}} in
                // `file:.../{{head}}/expanded.jsonl exists` would
                // otherwise check a literal "{{head}}" directory and
                // always miss. Caught during release-bake B2.
                successContract = ctx.resolve(b.successContract());
                // Recipe BACKEND step whose tools list is exactly [shell]
                // → signal the adapter to skip the items-as-tools wrap.
                // Items-as-tools preamble biases the local 9B toward
                // emitting a scripted-item .js file instead of executing
                // a shell command; a shell-only recipe step wants the
                // opposite of that.
                if (b.tools() != null
                        && b.tools().size() == 1
                        && "shell".equalsIgnoreCase(b.tools().get(0))) {
                    taskType = TASK_TYPE_SHELL_EXEC;
                }
            }
            case RecipeStep.GooseRecipeRef g -> {
                prompt = "Run the Goose recipe '" + g.recipeRef() + "' with params " + g.params() + ".";
                successContract = "exit:0";
            }
            case RecipeStep.LongJob lj -> { prompt = ctx.resolve(lj.command()); successContract = "exit:0"; }
            default -> { return DispatchOutcome.logicalFail(); } // SHELL / GATE / DECISION never reach a dispatcher
        }
        if (prompt == null || prompt.isBlank()) return DispatchOutcome.logicalFail();

        var spec = workspaceHint == null
            ? TaskSpec.create(companionDid, taskType, prompt)
            : new TaskSpec(UUID.randomUUID(), companionDid, taskType, prompt,
                workspaceHint, List.of(), 0L, null);
        TaskResult result;
        try {
            result = backend.submitTask(spec).get(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Wall-clock exceeded — infrastructural, runner may retry per recipe.retry_count.
            ctx.put(step.id() + ".error", "timeout after " + effectiveTimeout.toSeconds() + "s");
            ctx.put(step.id() + ".transient", true);
            return DispatchOutcome.transientFail();
        } catch (ExecutionException e) {
            // Unwrap to see if the underlying cause is a network timeout / IOException.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            boolean isTransient = cause instanceof IOException
                    || cause instanceof TimeoutException
                    || (cause.getMessage() != null
                        && cause.getMessage().toLowerCase().contains("timeout"));
            ctx.put(step.id() + ".error", String.valueOf(cause.getMessage()));
            if (isTransient) ctx.put(step.id() + ".transient", true);
            return isTransient ? DispatchOutcome.transientFail() : DispatchOutcome.logicalFail();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ctx.put(step.id() + ".error", "interrupted");
            return DispatchOutcome.transientFail();
        } catch (Exception e) {
            ctx.put(step.id() + ".error", String.valueOf(e.getMessage()));
            return DispatchOutcome.logicalFail();
        }
        if (result == null) return DispatchOutcome.logicalFail();

        ctx.put(step.id() + ".status", result.status().name());
        if (result.summary() != null) ctx.put(step.id() + ".summary", result.summary());
        // Let the backend feed a value to a later gate, exactly like a SHELL step's stdout —
        // only when the summary IS a JSON object (prose summaries are left untouched).
        mergeJsonSummary(result.summary(), ctx);

        if (contractHolds(successContract, result)) return DispatchOutcome.success();
        // Say WHY in the log, not just "backend reported failure" in the evidence.
        // The 2026-08-24 bake failed here twice and the backend's summary — which
        // carried the subprocess's stderr tail — was written into ctx and read by
        // nothing; each diagnosis cost a 7-minute blind rerun.
        log.warn("BACKEND step '{}' failed its contract '{}': status={} summary={}",
            step.id(), successContract, result.status(),
            result.summary() == null ? "(none)"
                : result.summary().substring(0, Math.min(result.summary().length(), 500)));
        return DispatchOutcome.logicalFail();
    }

    private static boolean contractHolds(String contract, TaskResult r) {
        if (contract == null || contract.isBlank()) {
            // No contract declared: subprocess success is the signal.
            return r.status() == TaskStatus.SUCCEEDED;
        }
        String c = contract.trim();
        if (c.startsWith("file:")) {
            // The recipe author has *named the artifact* as the contract.
            // Treat the artifact as authoritative: if the file exists,
            // the BACKEND step succeeded even if goose/opencode exited
            // non-zero on a spurious follow-up tool call (the items-as-
            // tools preamble can make the local 9B emit one extra tool
            // call that 404s after producing the desired file). The
            // recipe writer's intent is "this file must be here when I
            // look" — honour it.
            String rest = c.substring("file:".length()).trim();
            if (rest.endsWith("exists")) rest = rest.substring(0, rest.length() - "exists".length()).trim();
            return !rest.isEmpty() && Files.exists(Path.of(rest));
        }
        // "exit:0" and free-form contracts: SUCCEEDED is the signal.
        return r.status() == TaskStatus.SUCCEEDED;
    }

    private static void mergeJsonSummary(String summary, RecipeContext ctx) {
        if (summary == null) return;
        String t = summary.trim();
        if (!t.startsWith("{")) return;
        try {
            JsonNode node = JSON.readTree(t);
            if (node.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = node.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    JsonNode v = e.getValue();
                    ctx.put(e.getKey(), v.isNumber() ? v.numberValue()
                            : v.isBoolean() ? v.booleanValue() : v.asText());
                }
            }
        } catch (Exception ignored) {
            // not JSON — nothing to merge
        }
    }
}
