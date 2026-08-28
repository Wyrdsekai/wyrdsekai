package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Tag;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Selection-policy regression coverage for OpenCode — the Part D companion
 * to {@code OpenCodeE2ETest}. Catches regressions in
 * {@code scripts/policy/coding-backend.js} that pure-Java tests miss.
 *
 * <p>Mirrors the structure of {@link CodingPolicyOpenHandsTest}: load the
 * on-disk policy script into a GraalJS context, build a synthetic ctx,
 * call {@code selectBackend(...)}, assert the result.</p>
 *
 * <p>Each scenario exercises one rule the policy script should honour
 * for OpenCode, framed as a (companion_state × household_config) input
 * pair. The "should" comment on each test states the rule the
 * assertion locks in — when a future tuner edits the script, a
 * failing test here pinpoints which rule they accidentally broke.</p>
 */
@Tag("needs-goose")
class OpenCodePolicyE2ETest {

    private static String policyScript;

    @BeforeAll
    static void loadScript() throws IOException {
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

    // ─── Scenario 1: opencode wins when codezaiku absent ───────────

    @Test void opencode_picked_when_codezaiku_absent() {
        // SPEC §2.5: OpenCode is the default-on local backend that makes
        // "complex items work out of the box". When CodeZaiku isn't
        // available, the chain must fall through to OpenCode rather than
        // bouncing all the way to a paid tier.
        var ctx = baseCtx();
        ctx.put("availableBackends", List.of("opencode", "openhands"));
        ctx.put("fallbackChain", List.of("codezaiku", "opencode", "openhands"));

        assertThat(invoke("did:c", "code", "fix the bug", ctx))
            .isEqualTo("opencode");
    }

    // ─── Scenario 2: codezaiku priority preserved ───────────────────

    @Test void codezaiku_keeps_priority_when_available() {
        // OpenCode is the SECONDARY default — CodeZaiku stays first when
        // both are available. This pins SPEC §2.6 chain order.
        var ctx = baseCtx();
        ctx.put("availableBackends", List.of("codezaiku", "opencode"));
        ctx.put("fallbackChain", List.of("codezaiku", "opencode"));

        assertThat(invoke("did:c", "code", "fix the bug", ctx))
            .isEqualTo("codezaiku");
    }

    // ─── Scenario 3: companion preference overrides chain ──────────

    @Test void companion_preferred_opencode_wins_over_codezaiku() {
        // A companion with preferred_backend=opencode in their soul
        // manifest gets opencode even when codezaiku is available.
        var ctx = baseCtx();
        ctx.put("availableBackends", List.of("codezaiku", "opencode"));
        ctx.put("fallbackChain", List.of("codezaiku", "opencode"));
        ctx.put("companionPreferences",
            prefs("opencode", List.of(), Map.of()));

        assertThat(invoke("did:c", "code", "fix the bug", ctx))
            .isEqualTo("opencode");
    }

    // ─── Scenario 4: avoid_backends excludes opencode ──────────────

    @Test void avoid_opencode_falls_through_to_next_chain_entry() {
        // Companion has avoid_backends=["opencode"] — selection must
        // skip OpenCode even when it's available + reachable, falling
        // through to whatever else is in the chain.
        var ctx = baseCtx();
        ctx.put("availableBackends", List.of("opencode", "openhands"));
        ctx.put("fallbackChain", List.of("opencode", "openhands"));
        ctx.put("companionPreferences",
            prefs(null, List.of("opencode"), Map.of()));

        assertThat(invoke("did:c", "code", "fix the bug", ctx))
            .isEqualTo("openhands");
    }

    // ─── Scenario 5: task-type override pins opencode ──────────────

    @Test void task_type_override_routes_to_opencode() {
        // `code` tasks override to OpenCode via the soul manifest's
        // task_type_overrides map. Even when codezaiku is otherwise
        // available + first in the chain, the override should win.
        var ctx = baseCtx();
        ctx.put("availableBackends", List.of("codezaiku", "opencode"));
        ctx.put("fallbackChain", List.of("codezaiku", "opencode"));
        ctx.put("companionPreferences",
            prefs(null, List.of(), Map.of("code", "opencode")));

        assertThat(invoke("did:c", "code", "fix the bug", ctx))
            .isEqualTo("opencode");
    }

    // ─── Scenario 6: explore heuristic does NOT override opencode ─

    @Test void explore_heuristic_picks_openhands_not_opencode_when_both_present() {
        // The Phase 2c heuristic promotes OpenHands above the chain on
        // explore-shaped tasks. OpenCode is NOT explore-leaning — it's
        // the narrower edit shape — so this test pins the "explore →
        // openhands, not opencode" rule. Catches a future tuner who
        // accidentally widens the heuristic to also catch OpenCode.
        var ctx = baseCtx();
        ctx.put("availableBackends",
            List.of("codezaiku", "opencode", "openhands"));
        ctx.put("fallbackChain",
            List.of("codezaiku", "opencode", "openhands"));

        assertThat(invoke("did:c", "explore", "the foo subsystem", ctx))
            .isEqualTo("openhands");
    }

    // ─── Scenario 7: high autonomy_pressure stays local ────────────

    @Test void high_autonomy_pressure_does_not_demote_opencode() {
        // OpenCode is LOCAL_FREE — no household CU spend. SPEC §4.4 says
        // "under high autonomy_pressure, prefer local over cloud" so
        // OpenCode should NOT be demoted by the autonomy_pressure>0.7
        // gate (only CLOUD_PAID backends are gated). This pins the rule
        // that the gate stays paid-only.
        var ctx = baseCtx();
        ctx.put("availableBackends", List.of("opencode"));
        ctx.put("fallbackChain", List.of("opencode"));
        ctx.put("driveState", Map.of("autonomy_pressure", 0.95));

        assertThat(invoke("did:c", "code", "fix the bug", ctx))
            .isEqualTo("opencode");
    }

    // ─── Scenario 8: opencode unavailable → null fallthrough ──────

    @Test void opencode_not_in_available_chain_returns_null() {
        // OpenCode isn't in availableBackends (binary missing, e.g.).
        // No other backends configured. Policy must return null —
        // the workshop room then narrates "no backend" rather than
        // dispatching to a phantom adapter.
        var ctx = baseCtx();
        ctx.put("availableBackends", List.of());
        ctx.put("fallbackChain", List.of("opencode"));
        ctx.put("defaultBackend", "opencode");

        var result = invoke("did:c", "code", "fix the bug", ctx);
        assertThat(result)
            .as("Policy must return null when no backend is available")
            .isNull();
    }

    // ─── Scenario 9: opencode wins for `test` taskType ─────────────

    @Test void test_task_type_routes_to_opencode_when_codezaiku_absent() {
        // SPEC §4.4 fallback chain applies per task type when no
        // override is set. For `test` tasks with no codezaiku present,
        // OpenCode should win (it's a generic-purpose backend, not
        // type-restricted).
        var ctx = baseCtx();
        ctx.put("availableBackends", List.of("opencode"));
        ctx.put("fallbackChain", List.of("opencode"));

        assertThat(invoke("did:c", "test", "src/foo.java", ctx))
            .isEqualTo("opencode");
    }

    // ─── Scenario 10: paid-tier weekday gate doesn't trip opencode ─

    @Test void weekday_only_gate_does_not_block_opencode() {
        // weekdayOnlyPaidBackends is a CLOUD_PAID-only gate. Even if
        // someone runs the suite on a weekend, OpenCode (LOCAL_FREE)
        // must remain selectable. Pins the tier check in isAllowed().
        var ctx = baseCtx();
        ctx.put("availableBackends", List.of("opencode"));
        ctx.put("fallbackChain", List.of("opencode"));
        var policy = policy();
        policy.put("weekdayOnlyPaidBackends", true);
        policy.put("weekday_only_paid_backends", true);
        ctx.put("householdPolicy", policy);

        assertThat(invoke("did:c", "code", "fix the bug", ctx))
            .isEqualTo("opencode");
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private static Map<String, Object> baseCtx() {
        var ctx = new LinkedHashMap<String, Object>();
        ctx.put("availableBackends", List.of("opencode"));
        ctx.put("companionPreferences", null);
        ctx.put("householdPolicy", policy());
        ctx.put("fallbackChain", List.of("opencode"));
        ctx.put("defaultBackend", "opencode");
        // OpenCode is LOCAL_FREE; default tier closure returns LOCAL_FREE
        // for opencode and LOCAL_HEAVY otherwise so paid-backend gates
        // never trip on the test surface unless the test wires CLOUD_PAID
        // explicitly via a sub-context.
        ctx.put("backendTier", tierProxy(name -> {
            if ("opencode".equals(name)) return "LOCAL_FREE";
            if ("codezaiku".equals(name)) return "LOCAL_HEAVY";
            if ("openhands".equals(name)) return "LOCAL_HEAVY";
            return "CLOUD_PAID";
        }));
        ctx.put("cuRemainingToday", longProxy(eid -> 1_000_000L));
        ctx.put("cuEstimate", (ProxyExecutable) args -> 0L);
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

    private static Map<String, Object> policy() {
        var m = new LinkedHashMap<String, Object>();
        m.put("requireApprovalFor", List.of());
        m.put("require_approval_for", List.of());
        m.put("autoApproveUnderCu", 0L);
        m.put("auto_approve_under_cu", 0L);
        m.put("weekdayOnlyPaidBackends", false);
        m.put("weekday_only_paid_backends", false);
        return m;
    }

    private String invoke(String entityId, String taskType,
                          String taskDescription, Map<String, Object> ctx) {
        var fn = jsContext.getBindings("js").getMember("selectBackend");
        var raw = fn.execute(entityId, taskType, taskDescription, ctx);
        if (raw == null || raw.isNull()) return null;
        return raw.asString();
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

    @FunctionalInterface
    interface SimpleStringFn { String apply(String name); }

    @FunctionalInterface
    interface SimpleLongFn { long apply(String name); }

    private static ProxyExecutable tierProxy(SimpleStringFn fn) {
        return args -> fn.apply(args.length > 0 ? args[0].asString() : null);
    }

    private static ProxyExecutable longProxy(SimpleLongFn fn) {
        return args -> fn.apply(args.length > 0 ? args[0].asString() : null);
    }
}
