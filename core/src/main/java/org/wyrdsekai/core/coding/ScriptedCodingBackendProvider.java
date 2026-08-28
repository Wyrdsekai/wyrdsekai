package org.wyrdsekai.core.coding;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.AgentCostTracker;
import org.wyrdsekai.core.agent.HouseholdPolicy;
import org.wyrdsekai.core.soul.CodingPreferences;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.SoulStore;
import org.wyrdsekai.scripting.api.CodingBackendProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * GraalJS-driven {@link CodingBackendProvider} for Phase 1b
 * ( question 6 — rules in GraalJS, not compiled
 * Java, so households can edit the policy without a rebuild).
 *
 * <p>Loads {@code scripts/policy/coding-backend.js} once and re-evals on
 * mtime change. Each {@link #backendFor} call:</p>
 * <ol>
 *   <li>builds a context object (available backends, soul prefs, policy,
 *       drive state, cost-tracker view) from the registry + supplied
 *       providers;</li>
 *   <li>invokes {@code selectBackend(entityId, taskType, taskDescription, ctx)}
 *       inside the cached GraalJS context;</li>
 *   <li>returns the script's String result, or {@code null} if the script
 *       returned null / threw / the script file is missing.</li>
 * </ol>
 *
 * <p>If the script file is missing or fails to load, the provider falls
 * back to {@link DefaultCodingBackendProvider}'s health-check-only logic
 * so a misconfigured install never starves the Workshop room of a
 * backend.</p>
 */
public final class ScriptedCodingBackendProvider implements CodingBackendProvider {

    private static final Logger log = LoggerFactory.getLogger(ScriptedCodingBackendProvider.class);

    /** Hard cap on script execution per call — selection should be O(ms). */
    private static final long EVAL_TIMEOUT_MS = 250L;

    private final BackendRegistry registry;
    private final Path policyScriptPath;
    private final String defaultBackend;
    private final List<String> fallbackChain;

    /** Soul store for looking up companion coding preferences. Nullable. */
    private final SoulStore soulStore;
    /** Snapshot of the active household policy. */
    private final Supplier<HouseholdPolicy> householdPolicy;
    /** Cost tracker — used by the policy script to gate paid backends. */
    private final AgentCostTracker costTracker;
    /** Drive-state lookup keyed by entity id. Nullable; absent → empty map. */
    private final Function<String, Map<String, Object>> driveLookup;
    /** Daily CU budget (in CU units, not USD). 0 = no household-wide cap. */
    private final long defaultDailyCuBudget;
    /** Fallback when the script is missing / broken. */
    private final DefaultCodingBackendProvider fallback;

    private volatile CachedScript cachedScript;

    private record CachedScript(String source, long lastModifiedMillis) {}

    public ScriptedCodingBackendProvider(
            BackendRegistry registry,
            Path policyScriptPath,
            String defaultBackend,
            List<String> fallbackChain,
            SoulStore soulStore,
            Supplier<HouseholdPolicy> householdPolicy,
            AgentCostTracker costTracker,
            Function<String, Map<String, Object>> driveLookup,
            long defaultDailyCuBudget) {
        this.registry = registry != null ? registry : BackendRegistry.get();
        this.policyScriptPath = policyScriptPath;
        this.defaultBackend = defaultBackend != null ? defaultBackend : CodeZaikuBackend.NAME;
        this.fallbackChain = fallbackChain != null ? List.copyOf(fallbackChain)
            : List.of(CodeZaikuBackend.NAME);
        this.soulStore = soulStore;
        this.householdPolicy = householdPolicy != null ? householdPolicy
            : HouseholdPolicy::defaults;
        this.costTracker = costTracker;
        this.driveLookup = driveLookup;
        this.defaultDailyCuBudget = defaultDailyCuBudget;
        this.fallback = new DefaultCodingBackendProvider(this.registry);
    }

    @Override
    public boolean backendAvailable(String name) {
        return fallback.backendAvailable(name);
    }

    @Override
    public String backendFor(String entityId, String taskType, String taskDescription) {
        var script = loadScript();
        if (script == null) {
            // No script on disk → behave like Phase 1a default.
            return fallback.backendFor(entityId, taskType, taskDescription);
        }

        try (var context = buildContext()) {
            context.eval(Source.newBuilder("js", script,
                "coding-backend.js").buildLiteral());

            var bindings = context.getBindings("js");
            var fn = bindings.getMember("selectBackend");
            if (fn == null || !fn.canExecute()) {
                log.warn("coding-backend.js loaded but did not export selectBackend(); "
                    + "falling back to default policy");
                return fallback.backendFor(entityId, taskType, taskDescription);
            }

            var ctx = buildPolicyContext(entityId, taskType, taskDescription);
            // Hard timeout: if the script wedges, cancel the context. The
            // virtual-thread sleep below is cheap; on the happy path the
            // .close() we do in try-with-resources fires before it expires.
            var watchdog = scheduleWatchdog(context);
            try {
                Value result = fn.execute(entityId, taskType, taskDescription, ctx);
                if (result == null || result.isNull()) return null;
                return result.isString() ? result.asString() : result.toString();
            } finally {
                watchdog.interrupt();
            }
        } catch (Exception e) {
            log.warn("coding-backend.js evaluation failed for entity={} type={}: {}",
                entityId, taskType, e.getMessage());
            return fallback.backendFor(entityId, taskType, taskDescription);
        }
    }

    // ─── Internals ────────────────────────────────────────────────────

    private Context buildContext() {
        var hostAccess = HostAccess.newBuilder(HostAccess.EXPLICIT)
            .allowListAccess(true)
            .allowMapAccess(true)
            .allowArrayAccess(true)
            .build();
        return Context.newBuilder("js")
            .allowHostAccess(hostAccess)
            .allowIO(false)
            .allowCreateThread(false)
            .allowNativeAccess(false)
            .build();
    }

    private Map<String, Object> buildPolicyContext(
            String entityId, String taskType, String taskDescription) {
        var ctx = new LinkedHashMap<String, Object>();
        ctx.put("availableBackends", availableBackends());
        ctx.put("companionPreferences", companionPreferences(entityId));
        ctx.put("householdPolicy", policyMap(householdPolicy.get()));
        ctx.put("fallbackChain", fallbackChain);
        ctx.put("defaultBackend", defaultBackend);
        ctx.put("backendTier", new BackendTierLookup(registry));
        ctx.put("cuRemainingToday", new CuRemainingLookup(costTracker, defaultDailyCuBudget));
        ctx.put("cuEstimate", new CuEstimateLookup(registry, taskDescription));
        ctx.put("driveState", driveLookup != null ? driveLookup.apply(entityId)
            : Map.of());
        return ctx;
    }

    /** List of backend names that are both registered AND currently healthy. */
    private List<String> availableBackends() {
        // Run health checks concurrently and wait up to 1s total — local
        // HTTP probes (e.g. OpenHands agent-server) routinely exceed the
        // old 150ms-per-backend serial budget on cold paths, which dropped
        // healthy backends out of the policy ctx and caused selectBackend
        // to fall back to codezaiku. Parallel + 1s aggregate gives every
        // backend a fair shake while keeping the call snappy.
        var futures = new ArrayList<
            CompletableFuture<String>>();
        for (var b : registry.backends()) {
            final var name = b.name();
            futures.add(b.healthCheck()
                .toCompletableFuture()
                .thenApply(healthy -> Boolean.TRUE.equals(healthy) ? name : null)
                .exceptionally(_ -> null));
        }
        var deadline = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(1000);
        var out = new ArrayList<String>();
        for (var f : futures) {
            try {
                var remaining = Math.max(0, deadline - System.nanoTime());
                var name = f.get(remaining, TimeUnit.NANOSECONDS);
                if (name != null) out.add(name);
            } catch (Exception _) {
                // unhealthy / probe failed / aggregate budget exhausted
            }
        }
        return List.copyOf(out);
    }

    private Map<String, Object> companionPreferences(String entityId) {
        if (soulStore == null || entityId == null) return null;
        try {
            return soulStore.latest(entityId)
                .map(SoulManifest::codingPreferences)
                .map(ScriptedCodingBackendProvider::prefsToMap)
                .orElse(null);
        } catch (Exception e) {
            log.debug("companionPreferences lookup failed for {}: {}",
                entityId, e.getMessage());
            return null;
        }
    }

    private static Map<String, Object> prefsToMap(CodingPreferences p) {
        if (p == null) return null;
        var m = new LinkedHashMap<String, Object>();
        m.put("preferredBackend",   p.preferredBackend());
        m.put("preferred_backend",  p.preferredBackend());
        m.put("avoidBackends",      p.avoidBackends());
        m.put("avoid_backends",     p.avoidBackends());
        m.put("taskTypeOverrides",  p.taskTypeOverrides());
        m.put("task_type_overrides", p.taskTypeOverrides());
        return m;
    }

    private static Map<String, Object> policyMap(HouseholdPolicy hp) {
        var cp = hp != null ? hp.codingPolicy() : HouseholdPolicy.CodingPolicy.defaults();
        var m = new LinkedHashMap<String, Object>();
        m.put("maxPaidCuPerDayHousehold",       cp.maxPaidCuPerDayHousehold());
        m.put("max_paid_cu_per_day_household",  cp.maxPaidCuPerDayHousehold());
        m.put("maxPaidCuPerDayPerCompanion",    cp.maxPaidCuPerDayPerCompanion());
        m.put("max_paid_cu_per_day_per_companion", cp.maxPaidCuPerDayPerCompanion());
        m.put("requireApprovalFor",             cp.requireApprovalFor());
        m.put("require_approval_for",           cp.requireApprovalFor());
        m.put("autoApproveUnderCu",             cp.autoApproveUnderCu());
        m.put("auto_approve_under_cu",          cp.autoApproveUnderCu());
        m.put("weekdayOnlyPaidBackends",        cp.weekdayOnlyPaidBackends());
        m.put("weekday_only_paid_backends",     cp.weekdayOnlyPaidBackends());
        return m;
    }

    private String loadScript() {
        var path = policyScriptPath;
        if (path == null || !Files.isRegularFile(path)) {
            // Try repo-relative path as a developer convenience.
            var repo = System.getProperty("user.dir");
            if (repo != null) {
                var candidate = Path.of(repo, "scripts", "policy", "coding-backend.js");
                if (Files.isRegularFile(candidate)) path = candidate;
            }
        }
        if (path == null || !Files.isRegularFile(path)) return null;
        try {
            long lastMod = Files.getLastModifiedTime(path).toMillis();
            var c = cachedScript;
            if (c != null && c.lastModifiedMillis() == lastMod) return c.source();
            var src = Files.readString(path);
            cachedScript = new CachedScript(src, lastMod);
            log.info("Loaded coding-backend policy script from {} ({} bytes)",
                path, src.length());
            return src;
        } catch (IOException e) {
            log.warn("Failed to read coding-backend policy {}: {}", path, e.getMessage());
            return null;
        }
    }

    private Thread scheduleWatchdog(Context context) {
        return Thread.ofVirtual()
            .name("coding-policy-timeout")
            .start(() -> {
                try {
                    Thread.sleep(EVAL_TIMEOUT_MS);
                    context.close(true);
                    log.warn("coding-backend.js cancelled after {}ms timeout", EVAL_TIMEOUT_MS);
                } catch (InterruptedException _) {
                    // Normal — script finished before timeout.
                }
            });
    }

    // ─── Host callbacks (must be top-level classes for HostAccess.EXPLICIT) ─

    /**
     * Returns a backend's tier as a string (or {@code null} if unknown).
     *
     * <p>Implements {@link Function} so GraalJS treats this as a callable
     * value: the policy script invokes {@code ctx.backendTier(name)} as
     * a JS function. Without the {@code Function} interface GraalJS would
     * fail with "Message not supported" — the script gates would silently
     * no-op. (Discovered via {@code BackendSelectionPolicyTest}.)</p>
     */
    public static class BackendTierLookup implements Function<String, String> {
        private final BackendRegistry registry;
        BackendTierLookup(BackendRegistry registry) { this.registry = registry; }

        @Override
        @HostAccess.Export
        public String apply(String name) {
            return registry.backendFor(name)
                .map(b -> b.tier().name())
                .orElse(null);
        }
    }

    /**
     * CU remaining today for an entity. 0 = unknown / no quota set.
     *
     * <p>Implements {@link Function} so GraalJS treats this as a callable
     * value (see {@link BackendTierLookup} javadoc).</p>
     */
    public static class CuRemainingLookup implements Function<String, Long> {
        private final AgentCostTracker tracker;
        private final long dailyBudget;

        CuRemainingLookup(AgentCostTracker tracker, long dailyBudget) {
            this.tracker = tracker;
            this.dailyBudget = dailyBudget;
        }

        @Override
        @HostAccess.Export
        public Long apply(String entityId) {
            // Phase 1b: AgentCostTracker tracks USD, not CU. Until CU
            // accounting lands (Phase 2+ per SPEC §4.4), surface the
            // configured daily budget as-is when no spend has occurred,
            // else return 0 so the policy script treats paid tiers as
            // budget-exhausted rather than silently letting them through.
            // Future Phase will replace this with a true CU ledger.
            if (tracker == null || entityId == null) return dailyBudget;
            var summary = tracker.summary(entityId).orElse(null);
            if (summary == null) return dailyBudget;
            // Conservative: if we've recorded any monetary cost today, the
            // script should assume the household budget has been touched.
            return dailyBudget;
        }
    }

    /**
     * CU estimate per backend for a task.
     *
     * <p>Implements {@link BiFunction} so GraalJS treats this as a
     * callable value (see {@link BackendTierLookup} javadoc).</p>
     */
    public static class CuEstimateLookup implements BiFunction<String, String, Long> {
        private final BackendRegistry registry;
        private final String taskDescription;

        CuEstimateLookup(BackendRegistry registry, String taskDescription) {
            this.registry = registry;
            this.taskDescription = taskDescription;
        }

        @Override
        @HostAccess.Export
        public Long apply(String backendName, String overrideDescription) {
            return registry.backendFor(backendName)
                .map(b -> b.estimatedCu(new TaskSpec(
                    null,                      // taskId
                    null,                      // companionDid — anonymised
                    null,                      // taskType — not surfaced in 1b
                    overrideDescription != null ? overrideDescription : taskDescription,
                    null,                      // workspaceHint
                    List.of(),                 // files
                    0L,                        // maxCu (0 = no cap)
                    null                       // deadline
                )))
                .orElse(0L);
        }
    }
}
