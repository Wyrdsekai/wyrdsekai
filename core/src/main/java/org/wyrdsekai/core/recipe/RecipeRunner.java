package org.wyrdsekai.core.recipe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes a {@link RecipeManifest} ( outer runner). Sequential with id-indexed
 * branching (Gate.onFail / Decision). The load-bearing guarantees (§4):
 *
 * <ul>
 *   <li>GATE and DECISION are evaluated <b>in this runtime</b> — never the backend.</li>
 *   <li>A failed step or a stop-gate halts the run and runs rollback compensations in reverse.</li>
 *   <li>BACKEND/GOOSE_RECIPE/LONG_JOB steps go through an injected {@link BackendDispatcher}; with
 *       none configured they short-circuit to {@code NEEDS_BACKEND} (P5 wires the real one).</li>
 *   <li>#1012 — Every SHELL/BACKEND/GOOSE_RECIPE/LONG_JOB step runs under a wall-clock (per-step
 *       override falls back to {@link StepKind#defaultTimeout()}). Transient failures (timeout,
 *       OOM-kill, IOException) consume up to {@link RecipeManifest#retryCount()} retries with
 *       a 30s backoff; logical failures (exit 1, success_contract miss) halt immediately.</li>
 * </ul>
 *
 * <p>This is a thin layer over the existing orchestration primitives — it owns step sequencing,
 * gates, and rollback, and delegates actual work to the command runner / dispatcher.
 */
public final class RecipeRunner {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Default backoff between transient-failure retries (#1012). */
    static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(30);

    private final CommandRunner commands;
    private final BackendDispatcher dispatcher; // nullable
    private final Sleeper sleeper;
    private final ResourceProbe resourceProbe;

    public RecipeRunner(CommandRunner commands) {
        this(commands, null, Sleeper.REAL);
    }

    public RecipeRunner(CommandRunner commands, BackendDispatcher dispatcher) {
        this(commands, dispatcher, Sleeper.REAL);
    }

    /** Test seam: inject a no-op {@link Sleeper} so retry tests don't burn 30s of wall-clock. */
    public RecipeRunner(CommandRunner commands, BackendDispatcher dispatcher, Sleeper sleeper) {
        this(commands, dispatcher, sleeper, ResourceProbe.REAL);
    }

    /** Test seam: inject a fixed {@link ResourceProbe} so requisite-preflight tests are deterministic. */
    public RecipeRunner(CommandRunner commands, BackendDispatcher dispatcher, Sleeper sleeper,
                        ResourceProbe resourceProbe) {
        this.commands = commands;
        this.dispatcher = dispatcher;
        this.sleeper = sleeper == null ? Sleeper.REAL : sleeper;
        this.resourceProbe = resourceProbe == null ? ResourceProbe.REAL : resourceProbe;
    }

    /** Backoff strategy (#1012). Production uses {@link Sleeper#REAL}; tests use {@link Sleeper#NOOP}. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
        Sleeper REAL = d -> Thread.sleep(d.toMillis());
        Sleeper NOOP = d -> {};
    }

    public enum Status { SUCCESS, GATE_FAILED, STEP_FAILED, NEEDS_BACKEND, RESOURCE_DENIED, ERROR }

    public record StepOutcome(String id, StepKind kind, boolean ok, String detail) {}

    public record RecipeRun(Status status, String message, List<StepOutcome> outcomes, RecipeContext context,
                            ResourceRequisiteGate.Decision resourceDenial) {
        /** Back-compat ctor — no resource denial (the common case). */
        public RecipeRun(Status status, String message, List<StepOutcome> outcomes, RecipeContext context) {
            this(status, message, outcomes, context, null);
        }
        public boolean succeeded() { return status == Status.SUCCESS; }
    }

    /**
     * Probes the live node for the resource preflight. {@code REAL} reads GpuProbe +
     * HardwareProbe + free disk + Files.exists/System.getenv. Tests inject a fixed snapshot.
     */
    @FunctionalInterface
    public interface ResourceProbe {
        ResourceRequisiteGate.Snapshot snapshot(RecipeManifest manifest);
        ResourceProbe REAL = ResourceProbes::detect;
    }

    public RecipeRun run(RecipeManifest manifest, Map<String, Object> params) {
        RecipeContext ctx = new RecipeContext();
        // Seed declared param defaults first, then overlay caller-supplied values.
        manifest.params().forEach((name, p) -> {
            if (p.defaultValue() != null) ctx.put(name, p.defaultValue());
        });
        if (params != null) ctx.putAll(params);
        // Fail fast on missing required params. Without this check the literal
        // "{{name}}" survives substitution (RecipeContext leaves unknown vars
        // as-is) and shell steps cryptically exit 2 against a non-existent
        // path. Discovered 2026-05-27 on mac-node when `wyrd recipes run`
        // omitted --params head=<head>.
        List<String> missing = new ArrayList<>();
        for (var e : manifest.params().entrySet()) {
            if (e.getValue().required() && !ctx.has(e.getKey())) {
                missing.add(e.getKey());
            }
        }
        if (!missing.isEmpty()) {
            return finish(Status.ERROR,
                    "missing required params: " + String.join(", ", missing),
                    new ArrayList<>(), ctx, new ArrayDeque<>());
        }
        // Resource requisites preflight (before any step runs). A recipe that declares
        // hardware/data needs is checked against the live node here so a heavy job never
        // launches on a box that can't satisfy it (then thrashes / monopolizes the GPU).
        // Unmet HARD → block with RESOURCE_DENIED carrying the structured Decision; the
        // dispatching layer turns that into a resource-request (steward-ask, or peer-zone
        // borrow). Unmet SOFT → advisory only, run proceeds. Empty requires → no-op ALLOW.
        if (!manifest.requires().isEmpty()) {
            ResourceRequisiteGate.Snapshot snap;
            try {
                snap = resourceProbe.snapshot(manifest);
            } catch (RuntimeException probeErr) {
                // A probe failure must fail closed for HARD reqs (better to ask than thrash):
                // an empty snapshot makes every hardware req unmet.
                snap = new ResourceRequisiteGate.Snapshot(List.of(), 0, 0, Set.of(), Set.of());
            }
            var rq = ResourceRequisiteGate.evaluate(manifest.requires(), snap);
            for (var soft : rq.unmetSoft()) {
                System.out.println("[recipe " + manifest.recipe() + "] advisory: soft requisite unmet — "
                        + soft.describe() + (soft.note().isBlank() ? "" : " (" + soft.note() + ")"));
            }
            if (!rq.allow()) {
                return new RecipeRun(Status.RESOURCE_DENIED, rq.summary(),
                        new ArrayList<>(), ctx, rq);
            }
        }
        List<StepOutcome> outcomes = new ArrayList<>();
        Deque<String> rollbacks = new ArrayDeque<>(); // resolved compensation commands, LIFO

        Map<String, Integer> idToIndex = new HashMap<>();
        for (int i = 0; i < manifest.steps().size(); i++) {
            idToIndex.put(manifest.steps().get(i).id(), i);
        }

        int pc = 0;
        int budget = manifest.steps().size() * 4 + 8; // loop guard for branch cycles
        int maxRetries = Math.max(0, manifest.retryCount());
        while (pc >= 0 && pc < manifest.steps().size()) {
            if (budget-- <= 0) {
                return finish(Status.ERROR, "step budget exhausted (branch cycle?)", outcomes, ctx, rollbacks);
            }
            RecipeStep step = manifest.steps().get(pc);

            switch (step) {
                case RecipeStep.Shell sh -> {
                    String cmd = ctx.resolve(sh.command());
                    Duration timeout = effectiveTimeout(sh);
                    CommandRunner.Result r = runWithRetry(cmd, timeout, maxRetries, sh.id(), ctx);
                    ctx.put(sh.id() + ".exit", r.exitCode());
                    if (r.ok()) mergeJsonStdout(r.stdout(), ctx);
                    outcomes.add(new StepOutcome(sh.id(), StepKind.SHELL, r.ok(),
                            "exit=" + r.exitCode()
                                    + retryNote(ctx, sh.id())
                                    + (r.ok() ? "" : " :: " + brief(r.stderr()))));
                    if (!r.ok()) {
                        return finish(Status.STEP_FAILED, "shell step '" + sh.id() + "' exit " + r.exitCode(),
                                outcomes, ctx, rollbacks);
                    }
                    if (sh.hasRollback()) rollbacks.push(ctx.resolve(sh.rollback()));
                    pc++;
                }
                case RecipeStep.Gate g -> {
                    boolean pass = GateEvaluator.evaluate(g.condition(), ctx);
                    outcomes.add(new StepOutcome(g.id(), StepKind.GATE, pass,
                            "condition '" + ctx.resolve(g.condition()) + "' " + (pass ? "PASS" : "FAIL")));
                    if (pass) {
                        pc++;
                    } else if (g.stopsOnFail()) {
                        return finish(Status.GATE_FAILED, "gate '" + g.id() + "' failed", outcomes, ctx, rollbacks);
                    } else {
                        Integer target = idToIndex.get(g.onFail());
                        pc = target == null ? -1 : target;
                    }
                }
                case RecipeStep.Decision d -> {
                    Object v = ctx.get(d.reads());
                    String key = v == null ? null : String.valueOf(v);
                    String target = key == null ? null : d.branches().get(key);
                    outcomes.add(new StepOutcome(d.id(), StepKind.DECISION, target != null,
                            "reads '" + d.reads() + "'=" + key + " → " + target));
                    if (target == null) {
                        return finish(Status.STEP_FAILED, "decision '" + d.id() + "' has no branch for '" + key + "'",
                                outcomes, ctx, rollbacks);
                    }
                    pc = idToIndex.getOrDefault(target, -1);
                }
                case RecipeStep.Backend b -> {
                    Status s = dispatchWithRetry(b, ctx, outcomes, StepKind.BACKEND, maxRetries);
                    if (s != null) return finish(s, "backend step '" + b.id() + "' " + s, outcomes, ctx, rollbacks);
                    pc++;
                }
                case RecipeStep.GooseRecipeRef gr -> {
                    Status s = dispatchWithRetry(gr, ctx, outcomes, StepKind.GOOSE_RECIPE, maxRetries);
                    if (s != null) return finish(s, "goose-recipe step '" + gr.id() + "' " + s, outcomes, ctx, rollbacks);
                    pc++;
                }
                case RecipeStep.LongJob lj -> {
                    Status s = dispatchWithRetry(lj, ctx, outcomes, StepKind.LONG_JOB, maxRetries);
                    if (s != null) return finish(s, "long-job step '" + lj.id() + "' " + s, outcomes, ctx, rollbacks);
                    pc++;
                }
            }
        }
        return new RecipeRun(Status.SUCCESS, "ok", outcomes, ctx);
    }

    // -- step execution helpers (#1012) -----------------------------------------

    /** Effective per-step wall-clock — manifest override → step-kind default → null. */
    static Duration effectiveTimeout(RecipeStep step) {
        Duration declared = step.timeout();
        if (declared != null) return declared;
        return step.kind().defaultTimeout();
    }

    /**
     * Retry-aware shell execution (#1012). Re-runs on transient failure up to {@code maxRetries}
     * times with {@link #DEFAULT_RETRY_BACKOFF} between attempts. Logical failures (non-transient)
     * return immediately. Records {@code <stepId>.attempts} in context when retries occur.
     */
    private CommandRunner.Result runWithRetry(String cmd, Duration timeout, int maxRetries,
                                              String stepId, RecipeContext ctx) {
        CommandRunner.Result r = null;
        int attempts = 0;
        for (int i = 0; i <= maxRetries; i++) {
            attempts = i + 1;
            r = commands.run(cmd, timeout);
            if (r.ok() || !r.transientFailure() || i == maxRetries) break;
            backoff();
        }
        if (attempts > 1) ctx.put(stepId + ".attempts", attempts);
        return r;
    }

    /**
     * Retry-aware backend dispatch (#1012). Mirrors {@link #runWithRetry} for BACKEND /
     * GOOSE_RECIPE / LONG_JOB steps. Returns null on success (runner continues); otherwise the
     * terminal status the runner reports.
     */
    private Status dispatchWithRetry(RecipeStep step, RecipeContext ctx,
                                     List<StepOutcome> outcomes, StepKind kind, int maxRetries) {
        if (dispatcher == null) {
            outcomes.add(new StepOutcome(step.id(), kind, false, "no backend dispatcher configured"));
            return Status.NEEDS_BACKEND;
        }
        Duration timeout = effectiveTimeout(step);
        BackendDispatcher.DispatchOutcome outcome = null;
        int attempts = 0;
        for (int i = 0; i <= maxRetries; i++) {
            attempts = i + 1;
            outcome = dispatcher.dispatchWith(step, ctx, timeout);
            if (outcome.ok() || !outcome.transientFailure() || i == maxRetries) break;
            backoff();
        }
        if (attempts > 1) ctx.put(step.id() + ".attempts", attempts);
        boolean ok = outcome != null && outcome.ok();
        outcomes.add(new StepOutcome(step.id(), kind, ok,
                (ok ? "dispatched" : "backend reported failure")
                        + retryNote(ctx, step.id())));
        return ok ? null : Status.STEP_FAILED;
    }

    private void backoff() {
        try {
            sleeper.sleep(DEFAULT_RETRY_BACKOFF);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static String retryNote(RecipeContext ctx, String stepId) {
        Object a = ctx.get(stepId + ".attempts");
        if (!(a instanceof Number n) || n.intValue() <= 1) return "";
        return " (after " + n.intValue() + " attempts)";
    }

    private RecipeRun finish(Status status, String message, List<StepOutcome> outcomes,
                             RecipeContext ctx, Deque<String> rollbacks) {
        // run compensations in reverse order, best-effort.
        while (!rollbacks.isEmpty()) {
            String cmd = rollbacks.pop();
            CommandRunner.Result r = commands.run(cmd);
            outcomes.add(new StepOutcome("rollback", StepKind.SHELL, r.ok(),
                    "rollback exit=" + r.exitCode()));
        }
        return new RecipeRun(status, message, outcomes, ctx);
    }

    private static void mergeJsonStdout(String stdout, RecipeContext ctx) {
        if (stdout == null) return;
        String t = stdout.trim();
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
            // not JSON — fine, nothing to merge
        }
    }

    private static String brief(String s) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }
}
