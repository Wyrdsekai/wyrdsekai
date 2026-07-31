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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2c — verifies the new "explore-leaning" heuristic in
 * {@code scripts/policy/coding-backend.js} routes explore/survey/research
 * tasks to {@code openhands} when it is available.
 *
 * <p>This test is a sibling of {@link BackendSelectionPolicyTest} and uses
 * the same harness (load the on-disk script, build a synthetic ctx,
 * exercise selectBackend). Kept in its own file so the heuristic's tests
 * stay grouped and easy to find when tuning the rule later.</p>
 */
class CodingPolicyOpenHandsTest {

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

    // ─── Task type → openhands ──────────────────────────────────────

    @Test void explore_task_type_picks_openhands_when_available() {
        var ctx = baseCtx();
        ctx.put("availableBackends",
            List.of("codeplane", "opencode", "openhands"));
        ctx.put("fallbackChain",
            List.of("codeplane", "opencode", "openhands"));

        // codeplane would normally win (first in chain). The new
        // heuristic promotes openhands above the chain when the task
        // type screams "explore".
        assertThat(invoke("did:c", "explore", "anything", ctx))
            .isEqualTo("openhands");
    }

    @Test void explore_unknown_repo_task_type_picks_openhands() {
        var ctx = baseCtx();
        ctx.put("availableBackends",
            List.of("codeplane", "opencode", "openhands"));
        ctx.put("fallbackChain",
            List.of("codeplane", "opencode", "openhands"));

        assertThat(invoke("did:c", "explore_unknown_repo", "anything", ctx))
            .isEqualTo("openhands");
    }

    @Test void survey_task_type_picks_openhands() {
        var ctx = baseCtx();
        ctx.put("availableBackends",
            List.of("codeplane", "opencode", "openhands"));
        ctx.put("fallbackChain",
            List.of("codeplane", "opencode", "openhands"));

        assertThat(invoke("did:c", "survey", "anything", ctx))
            .isEqualTo("openhands");
    }

    @Test void research_task_type_picks_openhands() {
        var ctx = baseCtx();
        ctx.put("availableBackends",
            List.of("codeplane", "opencode", "openhands"));
        ctx.put("fallbackChain",
            List.of("codeplane", "opencode", "openhands"));

        assertThat(invoke("did:c", "research", "anything", ctx))
            .isEqualTo("openhands");
    }

    // ─── Task description → openhands ──────────────────────────────

    @Test void description_starting_with_explore_picks_openhands() {
        var ctx = baseCtx();
        ctx.put("availableBackends",
            List.of("codeplane", "opencode", "openhands"));
        ctx.put("fallbackChain",
            List.of("codeplane", "opencode", "openhands"));

        assertThat(invoke("did:c", "code", "explore the foo subsystem", ctx))
            .isEqualTo("openhands");
    }

    @Test void description_starting_with_survey_picks_openhands() {
        var ctx = baseCtx();
        ctx.put("availableBackends",
            List.of("codeplane", "opencode", "openhands"));
        ctx.put("fallbackChain",
            List.of("codeplane", "opencode", "openhands"));

        assertThat(invoke("did:c", "code", "survey the dependency graph", ctx))
            .isEqualTo("openhands");
    }

    @Test void description_research_the_codebase_picks_openhands() {
        var ctx = baseCtx();
        ctx.put("availableBackends",
            List.of("codeplane", "opencode", "openhands"));
        ctx.put("fallbackChain",
            List.of("codeplane", "opencode", "openhands"));

        assertThat(invoke("did:c", "code", "research the codebase before we change things", ctx))
            .isEqualTo("openhands");
    }

    @Test void description_explore_the_codebase_picks_openhands() {
        var ctx = baseCtx();
        ctx.put("availableBackends",
            List.of("codeplane", "opencode", "openhands"));
        ctx.put("fallbackChain",
            List.of("codeplane", "opencode", "openhands"));

        assertThat(invoke("did:c", "code", "explore the codebase first", ctx))
            .isEqualTo("openhands");
    }

    // ─── Falls through when openhands is unavailable ───────────────

    @Test void explore_falls_through_when_openhands_not_available() {
        // OpenHands not installed — heuristic shouldn't override the
        // fallback chain to a backend that isn't there.
        var ctx = baseCtx();
        ctx.put("availableBackends", List.of("codeplane", "opencode"));
        ctx.put("fallbackChain",
            List.of("codeplane", "opencode", "openhands"));

        assertThat(invoke("did:c", "explore", "anything", ctx))
            .isEqualTo("codeplane");
    }

    @Test void normal_code_task_does_not_pick_openhands() {
        // Sanity: non-explore tasks must NOT route to openhands by this
        // heuristic. They follow the normal fallback chain.
        var ctx = baseCtx();
        ctx.put("availableBackends",
            List.of("codeplane", "opencode", "openhands"));
        ctx.put("fallbackChain",
            List.of("codeplane", "opencode", "openhands"));

        assertThat(invoke("did:c", "code", "fix the bug", ctx))
            .isEqualTo("codeplane");
    }

    @Test void mid_sentence_explore_does_not_match() {
        // The heuristic deliberately doesn't match mid-sentence "explore"
        // — too noisy. Pin that behaviour so a future tuner doesn't
        // accidentally widen the regex into a false-positive trap.
        var ctx = baseCtx();
        ctx.put("availableBackends",
            List.of("codeplane", "opencode", "openhands"));
        ctx.put("fallbackChain",
            List.of("codeplane", "opencode", "openhands"));

        assertThat(invoke("did:c", "code",
            "we should explore some refactoring options", ctx))
            .isEqualTo("codeplane");
    }

    // ─── Companion preferences override the heuristic ──────────────

    @Test void companion_preferred_backend_beats_openhands_heuristic() {
        // A companion with preferred_backend=opencode should still get
        // opencode even on an explore task. The heuristic kicks in only
        // when no companion preference applies.
        var ctx = baseCtx();
        ctx.put("availableBackends",
            List.of("codeplane", "opencode", "openhands"));
        ctx.put("fallbackChain",
            List.of("codeplane", "opencode", "openhands"));
        ctx.put("companionPreferences",
            prefs("opencode", List.of(), Map.of()));

        assertThat(invoke("did:c", "explore", "the foo subsystem", ctx))
            .isEqualTo("opencode");
    }

    @Test void avoid_openhands_overrides_explore_heuristic() {
        // Steward pinned avoid_backends=["openhands"] — the heuristic
        // must respect that. Falls through to codeplane.
        var ctx = baseCtx();
        ctx.put("availableBackends",
            List.of("codeplane", "opencode", "openhands"));
        ctx.put("fallbackChain",
            List.of("codeplane", "opencode", "openhands"));
        ctx.put("companionPreferences",
            prefs(null, List.of("openhands"), Map.of()));

        assertThat(invoke("did:c", "explore", "the foo subsystem", ctx))
            .isEqualTo("codeplane");
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private static Map<String, Object> baseCtx() {
        var ctx = new LinkedHashMap<String, Object>();
        ctx.put("availableBackends", List.of("codeplane"));
        ctx.put("companionPreferences", null);
        ctx.put("householdPolicy", policy());
        ctx.put("fallbackChain", List.of("codeplane"));
        ctx.put("defaultBackend", "codeplane");
        ctx.put("backendTier", tierProxy(name -> "LOCAL_HEAVY"));
        ctx.put("cuRemainingToday", longProxy(eid -> 1_000_000L));
        ctx.put("cuEstimate", (ProxyExecutable) (Value... args) -> 0L);
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
        assertThat(raw).as("selectBackend must return a value").isNotNull();
        assertThat(raw.isString())
            .as("selectBackend must return a String, got %s", raw)
            .isTrue();
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
