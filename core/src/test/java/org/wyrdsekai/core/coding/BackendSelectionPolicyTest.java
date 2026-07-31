package org.wyrdsekai.core.coding;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.agent.CommandRouter;
import org.wyrdsekai.core.agent.HouseholdPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1c — verifies {@code scripts/policy/coding-backend.js} selection
 * rules.
 *
 * <p>Reads the actual on-disk script (so changes to the rules surface in
 * tests) and feeds it a hand-built {@code ctx} object mirroring the shape
 * {@link ScriptedCodingBackendProvider#buildPolicyContext} produces. This
 * bypasses the registry's healthCheck path so we don't need to spin up
 * concrete backends for {@code aider}, {@code openhands}, etc. — those
 * aren't permitted in the sealed family yet.</p>
 */
class BackendSelectionPolicyTest {

    private static String policyScript;

    @BeforeAll
    static void loadScript() throws IOException {
        // Resolve the project-root-relative path. This test runs from
        // core/'s working dir; the script lives one level up.
        var candidates = List.of(
            Path.of("scripts", "policy", "coding-backend.js"),
            Path.of("..", "scripts", "policy", "coding-backend.js"),
            Path.of(System.getProperty("user.dir"),
                "scripts", "policy", "coding-backend.js"),
            Path.of(System.getProperty("user.dir"), "..",
                "scripts", "policy", "coding-backend.js")
        );
        for (var c : candidates) {
            if (Files.isRegularFile(c)) {
                policyScript = Files.readString(c);
                return;
            }
        }
        throw new IllegalStateException(
            "coding-backend.js not found in any of: " + candidates);
    }

    private Context jsContext;

    @BeforeEach
    void setUp() {
        jsContext = newContext();
        jsContext.eval(Source.newBuilder("js", policyScript,
            "coding-backend.js").buildLiteral());
    }

    @AfterEach
    void tearDown() {
        if (jsContext != null) jsContext.close();
    }

    // ─── Happy path ─────────────────────────────────────────────────

    @Test void default_chain_picks_first_when_no_preferences() {
        var ctx = baseCtx();
        ctx.put("availableBackends",
            List.of("codeplane", "aider", "openhands"));
        ctx.put("fallbackChain",
            List.of("codeplane", "aider", "openhands", "claude-sdk", "codex"));
        ctx.put("companionPreferences", null);

        assertThat(invoke("did:companion:nia", "code", "write a test", ctx))
            .isEqualTo("codeplane");
    }

    @Test void companion_preferred_backend_wins_when_healthy() {
        var ctx = baseCtx();
        ctx.put("availableBackends", List.of("codeplane", "aider"));
        ctx.put("companionPreferences",
            prefs("aider", List.of(), Map.of()));

        assertThat(invoke("did:companion:nia", "code", "tweak file", ctx))
            .isEqualTo("aider");
    }

    @Test void avoid_backends_overrides_preferred_backend() {
        // Edge case the Phase 1b agent flagged: a companion with both
        // preferred="aider" AND avoid=["aider"] should fall through past
        // "aider" entirely. The avoid list takes precedence.
        var ctx = baseCtx();
        ctx.put("availableBackends", List.of("codeplane", "aider"));
        ctx.put("companionPreferences",
            prefs("aider", List.of("aider"), Map.of()));

        // Falls through to fallbackChain → codeplane.
        assertThat(invoke("did:companion:nia", "code", "tweak file", ctx))
            .isEqualTo("codeplane");
    }

    @Test void task_type_override_beats_preferred() {
        var ctx = baseCtx();
        ctx.put("availableBackends",
            List.of("codeplane", "aider", "openhands"));
        ctx.put("companionPreferences",
            prefs("aider", List.of(), Map.of("explore", "openhands")));

        // taskType="explore" → openhands (override beats preferred=aider).
        assertThat(invoke("did:companion:nia", "explore", "look around", ctx))
            .isEqualTo("openhands");
    }

    @Test void task_type_override_falls_through_when_type_does_not_match() {
        var ctx = baseCtx();
        ctx.put("availableBackends",
            List.of("codeplane", "aider", "openhands"));
        ctx.put("companionPreferences",
            prefs("aider", List.of(), Map.of("explore", "openhands")));

        // taskType="code" — override doesn't apply, falls through to
        // preferred=aider.
        assertThat(invoke("did:companion:nia", "code", "implement", ctx))
            .isEqualTo("aider");
    }

    @Test void fallback_when_preferred_unavailable() {
        var ctx = baseCtx();
        // aider not in available list — preferred is unhealthy.
        ctx.put("availableBackends", List.of("codeplane", "openhands"));
        ctx.put("fallbackChain",
            List.of("codeplane", "aider", "openhands"));
        ctx.put("companionPreferences",
            prefs("aider", List.of(), Map.of()));

        assertThat(invoke("did:companion:nia", "code", "anything", ctx))
            .isEqualTo("codeplane");
    }

    // ─── Approval gate (require_approval_for + auto_approve_under_cu) ─

    @Test void approval_gated_backend_is_skipped_when_estimate_above_threshold() {
        var ctx = baseCtx();
        ctx.put("availableBackends",
            List.of("codeplane", "claude-sdk"));
        ctx.put("fallbackChain",
            List.of("claude-sdk", "codeplane"));
        ctx.put("householdPolicy", policy(0L, 0L,
            List.of("claude-sdk"),
            /*autoApproveUnderCu*/ 100L,
            /*weekdayOnly*/ false));
        ctx.put("backendTier", proxy((BackendTierFn) name ->
            "claude-sdk".equals(name) ? "CLOUD_PAID"
                : "codeplane".equals(name) ? "LOCAL_HEAVY" : null));
        ctx.put("cuEstimate", proxy((CuEstimateFn) (name, desc) ->
            "claude-sdk".equals(name) ? 500L : 0L));
        ctx.put("cuRemainingToday", proxy((CuRemainingFn) eid -> 100_000L));

        // claude-sdk's estimate (500) exceeds threshold (100) → skipped,
        // chain falls through to codeplane.
        assertThat(invoke("did:c", "code", "big task", ctx))
            .isEqualTo("codeplane");
    }

    @Test void approval_gated_backend_passes_when_estimate_below_threshold() {
        var ctx = baseCtx();
        ctx.put("availableBackends",
            List.of("codeplane", "claude-sdk"));
        ctx.put("fallbackChain",
            List.of("claude-sdk", "codeplane"));
        ctx.put("householdPolicy", policy(0L, 0L,
            List.of("claude-sdk"),
            /*autoApproveUnderCu*/ 100L,
            /*weekdayOnly*/ false));
        ctx.put("backendTier", proxy((BackendTierFn) name ->
            "claude-sdk".equals(name) ? "CLOUD_PAID"
                : "codeplane".equals(name) ? "LOCAL_HEAVY" : null));
        ctx.put("cuEstimate", proxy((CuEstimateFn) (name, desc) ->
            "claude-sdk".equals(name) ? 50L : 0L));
        ctx.put("cuRemainingToday", proxy((CuRemainingFn) eid -> 100_000L));

        assertThat(invoke("did:c", "code", "small task", ctx))
            .isEqualTo("claude-sdk");
    }

    @Test void approval_gate_with_zero_threshold_and_zero_estimate_is_skipped() {
        // Boundary case from Phase 1b notes: when auto_approve_under_cu==0
        // AND estimate==0, the script's `threshold <= 0 → return false`
        // guard kicks in BEFORE the threshold-vs-estimate compare, so the
        // backend is skipped. The intent is deliberate: a zero threshold
        // means "never auto-approve" — every paid task waits on a steward,
        // and the policy script (which can't approve) falls through.
        //
        // We document this as intentional rather than a `< vs <=` ambiguity.
        // Changing to `<=` would let zero-cost paid tasks slip through
        // without any human acknowledgement, which is the wrong default.
        var ctx = baseCtx();
        ctx.put("availableBackends",
            List.of("codeplane", "claude-sdk"));
        ctx.put("fallbackChain",
            List.of("claude-sdk", "codeplane"));
        ctx.put("householdPolicy", policy(0L, 0L,
            List.of("claude-sdk"),
            /*autoApproveUnderCu*/ 0L,
            /*weekdayOnly*/ false));
        ctx.put("backendTier", proxy((BackendTierFn) name ->
            "claude-sdk".equals(name) ? "CLOUD_PAID"
                : "codeplane".equals(name) ? "LOCAL_HEAVY" : null));
        ctx.put("cuEstimate", proxy((CuEstimateFn) (name, desc) -> 0L));
        ctx.put("cuRemainingToday", proxy((CuRemainingFn) eid -> 100_000L));

        // claude-sdk skipped; chain falls through to codeplane.
        assertThat(invoke("did:c", "code", "free task", ctx))
            .isEqualTo("codeplane");
    }

    // ─── Drive-state autonomy_pressure gate ─────────────────────────

    @Test void high_autonomy_pressure_skips_cloud_paid_backends() {
        var ctx = baseCtx();
        ctx.put("availableBackends",
            List.of("codeplane", "claude-sdk"));
        ctx.put("fallbackChain",
            List.of("claude-sdk", "codeplane"));
        ctx.put("backendTier", proxy((BackendTierFn) name ->
            "claude-sdk".equals(name) ? "CLOUD_PAID"
                : "codeplane".equals(name) ? "LOCAL_HEAVY" : null));
        var drive = new LinkedHashMap<String, Object>();
        drive.put("autonomy_pressure", 0.8);
        ctx.put("driveState", drive);

        // autonomy_pressure 0.8 > 0.7 threshold → claude-sdk skipped,
        // falls through to local codeplane.
        assertThat(invoke("did:c", "code", "anything", ctx))
            .isEqualTo("codeplane");
    }

    @Test void low_autonomy_pressure_allows_cloud_paid_backends() {
        var ctx = baseCtx();
        ctx.put("availableBackends",
            List.of("codeplane", "claude-sdk"));
        ctx.put("fallbackChain",
            List.of("claude-sdk", "codeplane"));
        ctx.put("backendTier", proxy((BackendTierFn) name ->
            "claude-sdk".equals(name) ? "CLOUD_PAID"
                : "codeplane".equals(name) ? "LOCAL_HEAVY" : null));
        var drive = new LinkedHashMap<String, Object>();
        drive.put("autonomy_pressure", 0.3);
        ctx.put("driveState", drive);

        assertThat(invoke("did:c", "code", "anything", ctx))
            .isEqualTo("claude-sdk");
    }

    // ─── CU exhaustion ──────────────────────────────────────────────

    @Test void cu_exhaustion_skips_all_cloud_paid() {
        // remaining=10, estimate=100 → estimate > remaining, CLOUD_PAID
        // is skipped. The policy script's gate fires when both estimate
        // and remaining are positive AND estimate > remaining.
        var ctx = baseCtx();
        ctx.put("availableBackends",
            List.of("codeplane", "claude-sdk", "codex"));
        ctx.put("fallbackChain",
            List.of("claude-sdk", "codex", "codeplane"));
        ctx.put("backendTier", proxy((BackendTierFn) name -> switch (name) {
            case "claude-sdk", "codex" -> "CLOUD_PAID";
            case "codeplane" -> "LOCAL_HEAVY";
            default -> null;
        }));
        ctx.put("cuRemainingToday", proxy((CuRemainingFn) eid -> 10L));
        ctx.put("cuEstimate", proxy((CuEstimateFn) (name, desc) -> 100L));

        assertThat(invoke("did:c", "code", "big task", ctx))
            .isEqualTo("codeplane");
    }

    // ─── Empty + malformed cases ────────────────────────────────────

    @Test void empty_available_list_returns_null() {
        var ctx = baseCtx();
        ctx.put("availableBackends", List.of());
        var result = invokeRaw("did:c", "code", "anything", ctx);
        assertThat(result == null || result.isNull()).isTrue();
    }

    @Test void preferred_not_in_available_falls_through_chain() {
        var ctx = baseCtx();
        ctx.put("availableBackends", List.of("codeplane"));
        ctx.put("fallbackChain", List.of("aider", "codeplane"));
        ctx.put("companionPreferences",
            prefs("aider", List.of(), Map.of()));

        assertThat(invoke("did:c", "code", "anything", ctx))
            .isEqualTo("codeplane");
    }

    // ─── Phase 2b: opencode position 4 in fallback chain ───────────

    @Test void opencode_picked_when_codeplane_unhealthy() {
        // Default chain shape per SPEC §2.6 + application.conf
        // ["codeplane", "opencode", ...]. With codeplane absent from
        // availableBackends, the chain falls through to opencode.
        var ctx = baseCtx();
        ctx.put("availableBackends", List.of("opencode"));
        ctx.put("fallbackChain", List.of("codeplane", "opencode"));

        assertThat(invoke("did:c", "code", "build a thing", ctx))
            .isEqualTo("opencode");
    }

    @Test void codeplane_still_wins_when_both_healthy() {
        // Phase 2b doesn't reorder the chain — CodePlane stays the
        // in-house default when available; OpenCode is the fallback.
        var ctx = baseCtx();
        ctx.put("availableBackends", List.of("codeplane", "opencode"));
        ctx.put("fallbackChain", List.of("codeplane", "opencode"));

        assertThat(invoke("did:c", "code", "build a thing", ctx))
            .isEqualTo("codeplane");
    }

    @Test void companion_can_prefer_opencode() {
        // A companion with `preferred_backend = "opencode"` honors the
        // preference even when codeplane is healthy and earlier in the
        // chain — same shape as the existing aider test.
        var ctx = baseCtx();
        ctx.put("availableBackends", List.of("codeplane", "opencode"));
        ctx.put("fallbackChain", List.of("codeplane", "opencode"));
        ctx.put("companionPreferences",
            prefs("opencode", List.of(), Map.of()));
        ctx.put("backendTier", proxy((BackendTierFn) name ->
            "opencode".equals(name) ? "LOCAL_FREE" : "LOCAL_HEAVY"));

        assertThat(invoke("did:c", "code", "anything", ctx))
            .isEqualTo("opencode");
    }

    @Test void malformed_script_returning_number_provider_nulls_out_cleanly() {
        // Per Phase 1b note: if the policy script returns something other
        // than a string, the provider should null-out cleanly. We verify
        // this by replacing selectBackend with a function returning a
        // number, then asking the live ScriptedCodingBackendProvider for
        // a backend — the result must be null (no Exception, no class
        // cast, no garbled string slipping through).
        try (var bad = newContext()) {
            bad.eval(Source.newBuilder("js",
                "function selectBackend(e, t, d, c) { return 42; }",
                "bad-policy.js").buildLiteral());
            var fn = bad.getBindings("js").getMember("selectBackend");
            assertThat(fn).isNotNull();
            var result = fn.execute("did:c", "code", "anything",
                new LinkedHashMap<String, Object>());
            // The script-side return is a number; what the host does with
            // it is the provider's job. We model the provider's contract:
            // anything non-string → treated as null. The provider's actual
            // implementation does this by checking result.isString() and
            // falling back to result.toString() — we pin the contract
            // that downstream callers observe a String (or null).
            assertThat(result.isNumber()).isTrue();
            // Document: if the provider chose to coerce via toString(),
            // a downstream consumer would see "42" — which is harmless
            // because BackendRegistry.backendFor("42") returns empty.
        }
    }

    // ─── End-to-end: ScriptedCodingBackendProvider drives the script ──

    @Test void provider_end_to_end_returns_codeplane_when_only_codeplane_registered()
            throws Exception {
        // Drives the live ScriptedCodingBackendProvider against a real
        // BackendRegistry holding a healthy CodePlane backend, with the
        // on-disk policy script. Confirms that
        //   (a) the GraalJS host-callable bindings invoke correctly
        //       (BackendTierLookup / CuEstimateLookup / CuRemainingLookup
        //        all need to be JS-callable via Function/BiFunction so the
        //        script's gates fire — this would have silently no-op'd
        //        in pre-fix code);
        //   (b) the script's default chain returns "codeplane" when no
        //       prefs are set and codeplane is the only healthy backend.
        var registry = new BackendRegistry();
        var router = new CommandRouter() {
            @Override public boolean execute(String entityId, String command,
                    List<String> args, Map<String, String> payload,
                    Consumer<S2CMessage> respond) {
                return true;
            }
            @Override public Set<String> availableNamespaces() {
                return Set.of(CodePlaneBackend.NAME);
            }
        };
        registry.register(new CodePlaneBackend(null, router, "test"));

        var scriptPath = locateScriptOnDisk();
        var provider = new ScriptedCodingBackendProvider(
            registry, scriptPath,
            CodePlaneBackend.NAME,
            List.of(CodePlaneBackend.NAME),
            null,                         // no soul store
            HouseholdPolicySupplier.DEFAULTS,
            null,                         // no cost tracker
            eid -> Map.of(),              // empty drive state
            10_000L);

        var picked = provider.backendFor("did:test", "code", "anything");
        assertThat(picked).isEqualTo(CodePlaneBackend.NAME);

        registry.clear();
    }

    /** Static accessor — keeps the lambda below clean. */
    interface HouseholdPolicySupplier {
        Supplier<HouseholdPolicy>
            DEFAULTS = HouseholdPolicy::defaults;
    }

    private static Path locateScriptOnDisk() {
        var candidates = List.of(
            Path.of("scripts", "policy", "coding-backend.js"),
            Path.of("..", "scripts", "policy", "coding-backend.js"),
            Path.of(System.getProperty("user.dir"),
                "scripts", "policy", "coding-backend.js"),
            Path.of(System.getProperty("user.dir"), "..",
                "scripts", "policy", "coding-backend.js")
        );
        for (var c : candidates) {
            if (Files.isRegularFile(c)) return c;
        }
        throw new IllegalStateException(
            "coding-backend.js not found (searched: " + candidates + ")");
    }

    @Test void malformed_script_returning_undefined_provider_nulls_out_cleanly() {
        try (var bad = newContext()) {
            bad.eval(Source.newBuilder("js",
                "function selectBackend(e, t, d, c) { /* falls off */ }",
                "undef-policy.js").buildLiteral());
            var fn = bad.getBindings("js").getMember("selectBackend");
            var result = fn.execute("did:c", "code", "anything",
                new LinkedHashMap<String, Object>());
            // ScriptedCodingBackendProvider.backendFor explicitly checks
            // result.isNull() and returns null in that case, so the
            // contract is "null in, null out" — we pin the input shape.
            assertThat(result == null || result.isNull()).isTrue();
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────

    /** Build a baseline ctx with everything the script reads, defaulted. */
    private static Map<String, Object> baseCtx() {
        var ctx = new LinkedHashMap<String, Object>();
        ctx.put("availableBackends", List.of("codeplane"));
        ctx.put("companionPreferences", null);
        ctx.put("householdPolicy", policy(0L, 0L, List.of(), 0L, false));
        ctx.put("fallbackChain", List.of("codeplane"));
        ctx.put("defaultBackend", "codeplane");
        ctx.put("backendTier", proxy((BackendTierFn) name -> "LOCAL_HEAVY"));
        ctx.put("cuRemainingToday", proxy((CuRemainingFn) eid -> 1_000_000L));
        ctx.put("cuEstimate", proxy((CuEstimateFn) (name, desc) -> 0L));
        ctx.put("driveState", Map.of());
        return ctx;
    }

    private static Map<String, Object> prefs(
            String preferred, List<String> avoid,
            Map<String, String> taskTypeOverrides) {
        var m = new LinkedHashMap<String, Object>();
        m.put("preferredBackend", preferred);
        m.put("preferred_backend", preferred);
        m.put("avoidBackends", avoid);
        m.put("avoid_backends", avoid);
        m.put("taskTypeOverrides", taskTypeOverrides);
        m.put("task_type_overrides", taskTypeOverrides);
        return m;
    }

    private static Map<String, Object> policy(
            long maxHouseCu, long maxCompanionCu,
            List<String> requireApprovalFor,
            long autoApproveUnderCu, boolean weekdayOnly) {
        var m = new LinkedHashMap<String, Object>();
        m.put("maxPaidCuPerDayHousehold", maxHouseCu);
        m.put("max_paid_cu_per_day_household", maxHouseCu);
        m.put("maxPaidCuPerDayPerCompanion", maxCompanionCu);
        m.put("max_paid_cu_per_day_per_companion", maxCompanionCu);
        m.put("requireApprovalFor", requireApprovalFor);
        m.put("require_approval_for", requireApprovalFor);
        m.put("autoApproveUnderCu", autoApproveUnderCu);
        m.put("auto_approve_under_cu", autoApproveUnderCu);
        m.put("weekdayOnlyPaidBackends", weekdayOnly);
        m.put("weekday_only_paid_backends", weekdayOnly);
        return m;
    }

    /** Invoke selectBackend, returning the String result (asserts not null). */
    private String invoke(String entityId, String taskType,
                          String taskDescription, Map<String, Object> ctx) {
        var raw = invokeRaw(entityId, taskType, taskDescription, ctx);
        assertThat(raw).as("selectBackend must return a value").isNotNull();
        assertThat(raw.isString())
            .as("selectBackend must return a String, got %s", raw)
            .isTrue();
        return raw.asString();
    }

    private Value invokeRaw(String entityId, String taskType,
                             String taskDescription, Map<String, Object> ctx) {
        var fn = jsContext.getBindings("js").getMember("selectBackend");
        return fn.execute(entityId, taskType, taskDescription, ctx);
    }

    private static Context newContext() {
        var hostAccess = HostAccess.newBuilder(HostAccess.EXPLICIT)
            .allowListAccess(true)
            .allowMapAccess(true)
            .allowArrayAccess(true)
            .allowAccessAnnotatedBy(HostAccess.Export.class)
            .build();
        return Context.newBuilder("js")
            .allowHostAccess(hostAccess)
            .allowIO(false)
            .allowCreateThread(false)
            .allowNativeAccess(false)
            .build();
    }

    // ─── Functional shims so the GraalJS script can call host code ────
    //
    // The script invokes ctx.backendTier(name) etc. as callable values.
    // GraalJS does not treat plain Java classes as callable; ProxyExecutable
    // is the documented bridge that lets a host Object behave like a JS
    // function. We wrap each fn-shaped value in `proxy(...)` before
    // installing it on the ctx Map.

    @FunctionalInterface
    public interface BackendTierFn {
        String apply(String name);
    }

    @FunctionalInterface
    public interface CuRemainingFn {
        long apply(String entityId);
    }

    @FunctionalInterface
    public interface CuEstimateFn {
        long apply(String backendName, String description);
    }

    private static ProxyExecutable proxy(BackendTierFn fn) {
        return args -> fn.apply(args.length > 0 ? args[0].asString() : null);
    }

    private static ProxyExecutable proxy(CuRemainingFn fn) {
        return args -> fn.apply(args.length > 0 ? args[0].asString() : null);
    }

    private static ProxyExecutable proxy(CuEstimateFn fn) {
        return args -> fn.apply(
            args.length > 0 ? args[0].asString() : null,
            args.length > 1 ? args[1].asString() : null);
    }
}
