package org.wyrdsekai.core.agent.affordance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** — the mechanism, the seed, the agent-owned override, the
 *  instrument, the bounded tuner, and the relevance-tunable/permission-fixed boundary. */
class ToolAffordanceTest {

    private static final Function<String, ToolAffordance> SEED = AffordanceSeed::forTool;

    // ── Mechanism: a tool serving the currently-high drive rises ─────────────
    @Test
    void high_drive_promotes_the_tool_that_serves_it() {
        // generativity high, equanimity low → shape_recipe (serves generativity)
        // must outrank introspect (serves equanimity).
        var pressures = Map.of("generativity", 0.9, "equanimity", 0.1);
        var ranked = ToolAffordanceRanker.rank(pressures, null,
            List.of("introspect", "remember", "shape_recipe"), SEED, 0);
        assertThat(ranked.get(0)).isEqualTo("shape_recipe");
    }

    @Test
    void low_generativity_lets_reflection_win() {
        // equanimity high, generativity low → introspect rises, shape_recipe sinks.
        var pressures = Map.of("generativity", 0.05, "equanimity", 0.9);
        var ranked = ToolAffordanceRanker.rank(pressures, null,
            List.of("shape_recipe", "introspect"), SEED, 0);
        assertThat(ranked.get(0)).isEqualTo("introspect");
    }

    @Test
    void forced_verb_is_pinned_first() {
        // Even with no drive pressure, the OODA-decided verb leads.
        var ranked = ToolAffordanceRanker.rank(Map.of(), "shape_recipe",
            List.of("introspect", "remember", "shape_recipe"), SEED, 0);
        assertThat(ranked.get(0)).isEqualTo("shape_recipe");
    }

    @Test
    void topK_truncates_to_a_focused_menu() {
        var ranked = ToolAffordanceRanker.rank(Map.of("generativity", 0.9), null,
            List.of("a", "b", "c", "d", "shape_recipe"), SEED, 3);
        assertThat(ranked).hasSize(3).contains("shape_recipe");
    }

    // ── Boundary (§4): ranker only reorders the permitted set ────────────────
    @Test
    void ranker_never_adds_a_tool_not_in_candidates() {
        var candidates = List.of("introspect", "remember");
        // even forcing a tool that isn't permitted must NOT introduce it
        var ranked = ToolAffordanceRanker.rank(Map.of("generativity", 0.9),
            "shape_recipe", candidates, SEED, 0);
        assertThat(ranked).containsExactlyInAnyOrderElementsOf(candidates);
        assertThat(ranked).doesNotContain("shape_recipe");
    }

    // ── Seed: served-needs resolve from the existing ActionPolicy domain ─────
    @Test
    void seed_couples_recipes_domain_to_generativity() {
        var a = AffordanceSeed.forTool("shape_recipe");
        assertThat(a.servedNeeds()).containsKey("generativity");
        assertThat(a.servedNeeds().get("generativity")).isGreaterThan(0.0);
    }

    // ── Store: the agent-owned override beats the seed ───────────────────────
    @Test
    void store_override_beats_seed(@TempDir Path tmp) {
        var jdbc = "jdbc:sqlite:" + tmp.resolve("aff.db").toAbsolutePath();
        var store = new ToolAffordanceStore(jdbc);
        // seed: shape_recipe serves generativity. Agent retunes it to serve curiosity instead.
        store.upsert(new ToolAffordance("shape_recipe", Map.of("Curiosity", 1.5),
            "author a recipe to grow what I can do", 0.1), Instant.now());
        var resolved = store.resolve("shape_recipe", "recipes");
        assertThat(resolved.servedNeeds()).containsEntry("Curiosity", 1.5);
        assertThat(resolved.baseSalience()).isEqualTo(0.1);
        // a tool with no override still falls back to the seed
        var seeded = store.resolve("introspect", "self");
        assertThat(seeded.servedNeeds()).containsKey("equanimity");
    }

    // ── Instrument: surfacing mismatch is detected ───────────────────────────
    @Test
    void report_flags_want_not_surfaced() {
        var rows = List.of(
            new ToolAffordanceLog.Row("did:x", Instant.now(), "generativity",
                "shape_recipe", List.of("introspect", "remember"), "introspect"));
        var fit = ToolFitReport.compute(rows);
        assertThat(fit.passesWithWant()).isEqualTo(1);
        assertThat(fit.surfacedFraction()).isEqualTo(0.0);
        assertThat(fit.mismatches()).hasSize(1);
        assertThat(fit.mismatches().get(0).wantVerb()).isEqualTo("shape_recipe");
    }

    // ── Tuner: bounded nudge raises the coupling we keep losing ──────────────
    @Test
    void tuner_raises_coupling_bounded() {
        var mismatches = List.of(
            new ToolFitReport.Mismatch("shape_recipe", "generativity", false, "introspect"),
            new ToolFitReport.Mismatch("shape_recipe", "generativity", false, "remember"));
        var proposals = ToolAffordanceTuner.tune(mismatches, SEED, ToolAffordanceTuner.Bounds.defaults());
        assertThat(proposals).hasSize(1);
        var p = proposals.get(0);
        assertThat(p.toolName()).isEqualTo("shape_recipe");
        double seedWeight = AffordanceSeed.forTool("shape_recipe").servedNeeds().get("generativity");
        assertThat(p.servedNeeds().get("generativity")).isGreaterThan(seedWeight);   // nudged up
        assertThat(p.servedNeeds().get("generativity")).isLessThanOrEqualTo(2.0);    // clamped
    }

    @Test
    void tuner_ignores_single_observations() {
        var one = List.of(new ToolFitReport.Mismatch("shape_recipe", "generativity", false, "introspect"));
        assertThat(ToolAffordanceTuner.tune(one, SEED, ToolAffordanceTuner.Bounds.defaults())).isEmpty();
    }
}
