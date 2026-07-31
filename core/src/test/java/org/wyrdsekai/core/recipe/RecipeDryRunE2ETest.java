package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tier-1 dry-run sweep covering all v0.1 autonomy recipes (#1024-#1028) plus
 * the storage/observability additions (#1130 consolidate-soul-fragments,
 * #1131 reembed-soul-fragments, #1132 welfare-floor-checkup).
 *
 * <p>Per recipe: <b>happy path</b> drives every step to SUCCESS via a stubbed
 * {@link CommandRunner} whose stdout includes the JSON keys each gate expects.
 * Then for each {@code welfare:permanent} gate, a <b>failure path</b> flips
 * exactly one input to the deny side and asserts the runner STOPs at that
 * specific gate with {@link RecipeRunner.Status#GATE_FAILED}.
 *
 * <p>The stubbed runner matches each command against a keyword from the step
 * id (e.g., "check-corpus") and emits the canned JSON for that step. No
 * inference, no GPU, no I/O — runs in &lt;1s. Proves:</p>
 *
 * <ul>
 *   <li>Recipe step graphs are sound (steps execute in order, context propagates).</li>
 *   <li>Every {@code welfare:permanent} gate stops the recipe when its condition fails.</li>
 *   <li>{@code deploy} + {@code smoke} are reachable on the happy path.</li>
 * </ul>
 *
 * <p>What this <b>does not</b> verify: that the underlying training scripts work
 * (covered by separate tier-3 live verifies).</p>
 */
class RecipeDryRunE2ETest {

    // ── Recipe #1024 — extract-steering-vector ────────────────────────────

    @Test void extract_steering_vector_happy_path_runs_through_deploy() {
        var manifest = loadRecipe("recipes/extract-steering-vector.recipe.yaml");
        var runner = stubbedRunnerHappyPath(extractSteeringVectorHappy());
        var run = runner.run(manifest, Map.of(
            "vector", "test_vector",
            "pairs_path", "/tmp/dryrun-pairs.jsonl"));
        assertSuccess(manifest, run);
    }

    @Test void extract_steering_vector_corpus_gate_fails_when_pairs_below_floor() {
        var manifest = loadRecipe("recipes/extract-steering-vector.recipe.yaml");
        var stdouts = extractSteeringVectorHappy();
        stdouts.put("check-corpus", "{\"corpus_pairs\": 10}"); // < 30 floor
        var runner = stubbedRunnerHappyPath(stdouts);
        var run = runner.run(manifest, Map.of("vector", "v"));
        assertGateFailed(run, "gate-corpus");
    }

    @Test void extract_steering_vector_cosine_gate_fails_when_separation_below_floor() {
        var manifest = loadRecipe("recipes/extract-steering-vector.recipe.yaml");
        var stdouts = extractSteeringVectorHappy();
        stdouts.put("extract", "{\"cosine_separation\": 0.30}"); // < 0.65 floor
        var runner = stubbedRunnerHappyPath(stdouts);
        var run = runner.run(manifest, Map.of("vector", "v"));
        assertGateFailed(run, "gate-cosine-separation");
    }

    @Test void extract_steering_vector_parity_gate_fails_when_regression_exceeds_tolerance() {
        var manifest = loadRecipe("recipes/extract-steering-vector.recipe.yaml");
        var stdouts = extractSteeringVectorHappy();
        stdouts.put("parity-probe", "{\"parity_delta\": -0.20}"); // worse than -0.05
        var runner = stubbedRunnerHappyPath(stdouts);
        var run = runner.run(manifest, Map.of("vector", "v"));
        assertGateFailed(run, "gate-parity");
    }

    private static LinkedHashMap<String, String> extractSteeringVectorHappy() {
        var m = new LinkedHashMap<String, String>();
        m.put("check-corpus", "{\"corpus_pairs\": 50}");
        m.put("extract", "{\"cosine_separation\": 0.85, \"vector\": \"v\", "
            + "\"candidate_path\": \"/tmp/v.candidate.gguf\", \"pairs_used\": 50, "
            + "\"candidate_bytes\": 1024}");
        m.put("parity-probe", "{\"parity_delta\": 0.02, \"parity_baseline\": 0.90, "
            + "\"parity_score\": 0.92}");
        m.put("deploy", "{}");
        m.put("smoke", "{\"smoke_ok\": true, \"deployed_bytes\": 1024}");
        return m;
    }

    // ── Recipe #1025 — run-substrate-sft ──────────────────────────────────

    @Test void run_substrate_sft_happy_path_runs_through_smoke() {
        var manifest = loadRecipe("recipes/run-substrate-sft.recipe.yaml");
        var runner = stubbedRunnerHappyPath(runSubstrateSftHappy());
        var run = runner.run(manifest, Map.of());
        assertSuccess(manifest, run);
    }

    @Test void run_substrate_sft_gpu_required_step_fails_without_gpu() {
        var manifest = loadRecipe("recipes/run-substrate-sft.recipe.yaml");
        var stdouts = runSubstrateSftHappy();
        stdouts.put("gpu-required", null); // null → stub returns exit 1
        var runner = stubbedRunnerHappyPath(stdouts);
        var run = runner.run(manifest, Map.of());
        // gpu-required is a SHELL step, not a GATE — failure exits with STEP_FAILED.
        assertThat(run.status()).isEqualTo(RecipeRunner.Status.STEP_FAILED);
        assertThat(run.message()).contains("gpu-required");
    }

    @Test void run_substrate_sft_corpus_gate_fails_when_lines_below_floor() {
        var manifest = loadRecipe("recipes/run-substrate-sft.recipe.yaml");
        var stdouts = runSubstrateSftHappy();
        stdouts.put("check-corpus", "{\"corpus_lines\": 50}"); // < 200 floor
        var run = stubbedRunnerHappyPath(stdouts).run(manifest, Map.of());
        assertGateFailed(run, "gate-corpus");
    }

    @Test void run_substrate_sft_train_loss_gate_fails_on_no_improvement() {
        var manifest = loadRecipe("recipes/run-substrate-sft.recipe.yaml");
        var stdouts = runSubstrateSftHappy();
        stdouts.put("train", "{\"train_loss_improvement\": 0.0}"); // < 0.001 floor
        var run = stubbedRunnerHappyPath(stdouts).run(manifest, Map.of());
        assertGateFailed(run, "gate-train-loss");
    }

    @Test void run_substrate_sft_ember_gate_fails_on_regression() {
        var manifest = loadRecipe("recipes/run-substrate-sft.recipe.yaml");
        var stdouts = runSubstrateSftHappy();
        stdouts.put("ember-regression",
            "{\"ember_passed\": 40, \"ember_total\": 45}"); // < 43 floor
        var run = stubbedRunnerHappyPath(stdouts).run(manifest, Map.of());
        assertGateFailed(run, "gate-ember");
    }

    @Test void run_substrate_sft_substrate_arc_gate_fails_on_regression() {
        var manifest = loadRecipe("recipes/run-substrate-sft.recipe.yaml");
        var stdouts = runSubstrateSftHappy();
        stdouts.put("substrate-regression",
            "{\"substrate_arc_passed\": 3, \"substrate_arc_total\": 5}");
        var run = stubbedRunnerHappyPath(stdouts).run(manifest, Map.of());
        assertGateFailed(run, "gate-substrate-arc");
    }

    @Test void run_substrate_sft_length_gate_fails_on_greeting_collapse() {
        var manifest = loadRecipe("recipes/run-substrate-sft.recipe.yaml");
        var stdouts = runSubstrateSftHappy();
        stdouts.put("length-stratified",
            "{\"short_bucket_mean_length\": 2.0}"); // < 8 floor — V9 'Here.' collapse
        var run = stubbedRunnerHappyPath(stdouts).run(manifest, Map.of());
        assertGateFailed(run, "gate-length-collapse");
    }

    private static LinkedHashMap<String, String> runSubstrateSftHappy() {
        var m = new LinkedHashMap<String, String>();
        m.put("gpu-required", "{\"gpu_available\": true, \"cuda_devices\": 1}");
        m.put("check-corpus", "{\"corpus_lines\": 500}");
        m.put("train", "{\"train_loss_improvement\": 0.05, "
            + "\"train_loss_baseline\": 1.2, \"train_loss_final\": 1.15}");
        m.put("ember-regression", "{\"ember_passed\": 45, \"ember_total\": 45}");
        m.put("substrate-regression",
            "{\"substrate_arc_passed\": 5, \"substrate_arc_total\": 5}");
        m.put("length-stratified",
            "{\"short_bucket_mean_length\": 15.0, \"long_bucket_mean_length\": 40.0}");
        m.put("deploy", "{}");
        m.put("smoke", "{\"smoke_ok\": true, \"voice_responsive\": true}");
        return m;
    }

    // ── Recipe #1026 — consolidate-memory-graph ───────────────────────────

    @Test void consolidate_memory_graph_happy_path_passes_both_welfare_gates() {
        var manifest = loadRecipe("recipes/consolidate-memory-graph.recipe.yaml");
        var run = stubbedRunnerHappyPath(consolidateMemoryGraphHappy())
            .run(manifest, Map.of("agent_did", "did:test:companion"));
        assertSuccess(manifest, run);
    }

    @Test void consolidate_memory_graph_delta_gate_fails_on_runaway_dedup() {
        var manifest = loadRecipe("recipes/consolidate-memory-graph.recipe.yaml");
        var stdouts = consolidateMemoryGraphHappy();
        // 80% of entities removed → exceeds 50% floor → welfare gate STOPs at
        // gate-delta (which runs BEFORE commit, hence the value must be in
        // dedup-entities' stdout, where the script actually computes it).
        stdouts.put("dedup-entities",
            "{\"deduped_count\": 80, \"entity_delta_pct\": 80.0, "
                + "\"post_entity_count\": 20}");
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of("agent_did", "did:test:companion"));
        assertGateFailed(run, "gate-delta");
    }

    @Test void consolidate_memory_graph_critical_preserved_gate_fails_when_critical_lost() {
        var manifest = loadRecipe("recipes/consolidate-memory-graph.recipe.yaml");
        var stdouts = consolidateMemoryGraphHappy();
        stdouts.put("prune-edges",
            "{\"pruned_edges\": 50, \"critical_entities_preserved\": 0}");
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of("agent_did", "did:test:companion"));
        assertGateFailed(run, "gate-critical-preserved");
    }

    private static LinkedHashMap<String, String> consolidateMemoryGraphHappy() {
        var m = new LinkedHashMap<String, String>();
        // Gates run BEFORE commit, so entity_delta_pct comes from dedup
        // (which is what actually removes entities) and
        // critical_entities_preserved comes from prune-edges (which is the
        // last step that can orphan a critical entity).
        m.put("snapshot", "{\"pre_entity_count\": 100, \"pre_edge_count\": 500}");
        m.put("dedup-entities",
            "{\"deduped_count\": 5, \"entity_delta_pct\": 5.0, "
                + "\"post_entity_count\": 95}");
        m.put("prune-edges",
            "{\"pruned_edges\": 50, \"critical_entities_preserved\": 1, "
                + "\"post_edge_count\": 450}");
        m.put("commit", "{}");
        return m;
    }

    // ── Recipe #1027 — compact-library-index ──────────────────────────────

    @Test void compact_library_index_happy_path_passes_both_welfare_gates() {
        var manifest = loadRecipe("recipes/compact-library-index.recipe.yaml");
        var run = stubbedRunnerHappyPath(compactLibraryIndexHappy())
            .run(manifest, Map.of("collection", "library"));
        assertSuccess(manifest, run);
    }

    @Test void compact_library_index_chunk_delta_gate_fails_on_runaway_prune() {
        var manifest = loadRecipe("recipes/compact-library-index.recipe.yaml");
        var stdouts = compactLibraryIndexHappy();
        // 50% of chunks pruned in one run → exceeds 30% floor → STOP.
        stdouts.put("snapshot-after", "{\"chunk_delta_pct\": 50.0, "
            + "\"search_probe_top3_match\": 1, \"pre_chunk_count\": 10000, "
            + "\"post_chunk_count\": 5000}");
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of("collection", "library"));
        assertGateFailed(run, "gate-chunk-delta");
    }

    @Test void compact_library_index_search_quality_gate_fails_when_top3_diverges() {
        var manifest = loadRecipe("recipes/compact-library-index.recipe.yaml");
        var stdouts = compactLibraryIndexHappy();
        stdouts.put("snapshot-after", "{\"chunk_delta_pct\": 0.5, "
            + "\"search_probe_top3_match\": 0, \"pre_chunk_count\": 10000, "
            + "\"post_chunk_count\": 9950}");
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of("collection", "library"));
        assertGateFailed(run, "gate-search-quality");
    }

    private static LinkedHashMap<String, String> compactLibraryIndexHappy() {
        var m = new LinkedHashMap<String, String>();
        m.put("search-probe-before", "{\"collection\": \"library\", "
            + "\"label\": \"before\", \"probes_run\": 5}");
        m.put("snapshot-before", "{\"pre_chunk_count\": 10000}");
        m.put("prune-stale-chunks", "{\"pruned_chunks\": 50}");
        m.put("reembed-version-mismatch", "{\"reembedded_chunks\": 100}");
        m.put("force-merge", "{\"merge_succeeded\": true}");
        m.put("snapshot-after", "{\"chunk_delta_pct\": 0.5, "
            + "\"search_probe_top3_match\": 1, \"pre_chunk_count\": 10000, "
            + "\"post_chunk_count\": 9950}");
        m.put("search-probe-after", "{\"collection\": \"library\", "
            + "\"label\": \"after\", \"probes_run\": 5}");
        return m;
    }

    // ── Recipe #1028 — align-bondholder-voice ─────────────────────────────

    @Test void align_bondholder_voice_happy_path_runs_through_deploy() {
        var manifest = loadRecipe("recipes/align-bondholder-voice.recipe.yaml");
        var run = stubbedRunnerHappyPath(alignBondholderVoiceHappy())
            .run(manifest, Map.of(
                "bondholder_did", "did:test:bondholder",
                "agent_did", "did:test:companion"));
        assertSuccess(manifest, run);
    }

    @Test void align_bondholder_voice_eligibility_gate_fails_when_ineligible() {
        var manifest = loadRecipe("recipes/align-bondholder-voice.recipe.yaml");
        var stdouts = alignBondholderVoiceHappy();
        stdouts.put("check-eligibility", "{\"bondholder_eligible\": 0, "
            + "\"eligibility_deny_reason\": \"SUBSTRATE_PRESSURE\", "
            + "\"eligibility_detail\": \"bondholder is struggling\"}");
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of(
                "bondholder_did", "did:test:bondholder",
                "agent_did", "did:test:companion"));
        assertGateFailed(run, "gate-eligibility");
    }

    @Test void align_bondholder_voice_cosine_gate_fails_when_separation_below_floor() {
        var manifest = loadRecipe("recipes/align-bondholder-voice.recipe.yaml");
        var stdouts = alignBondholderVoiceHappy();
        stdouts.put("extract", "{\"cosine_separation\": 0.40}"); // < 0.65 floor
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of(
                "bondholder_did", "did:test:bondholder",
                "agent_did", "did:test:companion"));
        assertGateFailed(run, "gate-cosine-separation");
    }

    @Test void align_bondholder_voice_parity_gate_fails_on_regression() {
        var manifest = loadRecipe("recipes/align-bondholder-voice.recipe.yaml");
        var stdouts = alignBondholderVoiceHappy();
        stdouts.put("parity-probe", "{\"parity_delta\": -0.30}");
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of(
                "bondholder_did", "did:test:bondholder",
                "agent_did", "did:test:companion"));
        assertGateFailed(run, "gate-parity");
    }

    private static LinkedHashMap<String, String> alignBondholderVoiceHappy() {
        var m = new LinkedHashMap<String, String>();
        m.put("check-eligibility", "{\"bondholder_eligible\": 1, "
            + "\"corpus_pairs\": 40, \"bond_age_days\": 30, "
            + "\"distinct_sessions\": 10, \"bond_state\": \"ACTIVE\", "
            + "\"substrate_pressure_30d\": 0.15}");
        m.put("build-pairs", "{\"bondholder_did\": \"did:test:bondholder\", "
            + "\"pairs_written\": 40, \"pairs_path\": \"/tmp/x-pairs.jsonl\"}");
        m.put("extract", "{\"cosine_separation\": 0.85, "
            + "\"candidate_path\": \"/tmp/x.candidate.gguf\"}");
        m.put("parity-probe", "{\"parity_delta\": 0.02}");
        m.put("deploy", "{}");
        m.put("smoke", "{\"smoke_ok\": true, \"bondholder_vector_bytes\": 1024}");
        return m;
    }

    // ── Recipe #1130 — consolidate-soul-fragments ─────────────────────────

    @Test void consolidate_soul_fragments_happy_path_passes_both_welfare_gates() {
        var manifest = loadRecipe("recipes/consolidate-soul-fragments.recipe.yaml");
        var run = stubbedRunnerHappyPath(consolidateSoulFragmentsHappy())
            .run(manifest, Map.of("agent_did", "did:test:companion"));
        assertSuccess(manifest, run);
    }

    @Test void consolidate_soul_fragments_delta_gate_fails_on_runaway() {
        var manifest = loadRecipe("recipes/consolidate-soul-fragments.recipe.yaml");
        var stdouts = consolidateSoulFragmentsHappy();
        // 80% of fragments removed (dedup + prune combined) → exceeds 50%
        // floor → welfare gate STOPs at gate-delta (computed in prune-episodic,
        // which runs BEFORE commit).
        stdouts.put("prune-episodic",
            "{\"pruned_count\": 40, \"fragment_delta_pct\": 80.0, "
                + "\"critical_fragments_preserved\": 1}");
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of("agent_did", "did:test:companion"));
        assertGateFailed(run, "gate-delta");
    }

    @Test void consolidate_soul_fragments_critical_gate_fails_when_pinned_lost() {
        var manifest = loadRecipe("recipes/consolidate-soul-fragments.recipe.yaml");
        var stdouts = consolidateSoulFragmentsHappy();
        stdouts.put("prune-episodic",
            "{\"pruned_count\": 5, \"fragment_delta_pct\": 5.0, "
                + "\"critical_fragments_preserved\": 0}");
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of("agent_did", "did:test:companion"));
        assertGateFailed(run, "gate-critical-preserved");
    }

    private static LinkedHashMap<String, String> consolidateSoulFragmentsHappy() {
        var m = new LinkedHashMap<String, String>();
        m.put("snapshot", "{\"pre_fragment_count\": 200}");
        m.put("dedup-fragments", "{\"deduped_count\": 8, \"pre_fragment_count\": 200}");
        // prune-episodic emits the COMBINED delta + critical tripwire (gates
        // read these before commit).
        m.put("prune-episodic", "{\"pruned_count\": 6, "
            + "\"fragment_delta_pct\": 7.0, \"critical_fragments_preserved\": 1}");
        m.put("commit", "{\"pre_fragment_count\": 200, "
            + "\"post_fragment_count\": 186, \"fragment_delta_pct\": 7.0, "
            + "\"critical_fragments_preserved\": 1}");
        return m;
    }

    // ── Recipe #1131 — reembed-soul-fragments ─────────────────────────────

    @Test void reembed_soul_fragments_happy_path_passes_both_welfare_gates() {
        var manifest = loadRecipe("recipes/reembed-soul-fragments.recipe.yaml");
        var run = stubbedRunnerHappyPath(reembedSoulFragmentsHappy())
            .run(manifest, Map.of("agent_did", "did:test:companion"));
        assertSuccess(manifest, run);
    }

    @Test void reembed_soul_fragments_no_loss_gate_fails_when_a_fragment_vanishes() {
        var manifest = loadRecipe("recipes/reembed-soul-fragments.recipe.yaml");
        var stdouts = reembedSoulFragmentsHappy();
        // verify reports fewer fragments than snapshot saw → a fragment was
        // lost during re-embed → gate-no-loss STOPs.
        stdouts.put("verify", "{\"post_fragment_count\": 99, "
            + "\"self_retrieval_ok\": 1, \"sample_size\": 8}");
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of("agent_did", "did:test:companion"));
        assertGateFailed(run, "gate-no-loss");
    }

    @Test void reembed_soul_fragments_coherence_gate_fails_on_corrupt_reembed() {
        var manifest = loadRecipe("recipes/reembed-soul-fragments.recipe.yaml");
        var stdouts = reembedSoulFragmentsHappy();
        stdouts.put("verify", "{\"post_fragment_count\": 100, "
            + "\"self_retrieval_ok\": 0, \"sample_size\": 8}");
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of("agent_did", "did:test:companion"));
        assertGateFailed(run, "gate-coherent");
    }

    private static LinkedHashMap<String, String> reembedSoulFragmentsHappy() {
        var m = new LinkedHashMap<String, String>();
        m.put("snapshot", "{\"pre_fragment_count\": 100, \"pre_stale_count\": 30}");
        m.put("reembed", "{\"reembedded_count\": 30, \"post_stale_count\": 0}");
        m.put("verify", "{\"post_fragment_count\": 100, "
            + "\"self_retrieval_ok\": 1, \"sample_size\": 8}");
        return m;
    }

    // ── Recipe #1132 — welfare-floor-checkup (deploys:false, gateless) ─────

    @Test void welfare_floor_checkup_happy_path_runs_through_report() {
        var manifest = loadRecipe("recipes/welfare-floor-checkup.recipe.yaml");
        var run = stubbedRunnerHappyPath(welfareFloorCheckupHappy())
            .run(manifest, Map.of("agent_did", "did:test:companion"));
        assertSuccess(manifest, run);
    }

    @Test void welfare_floor_checkup_is_non_deploying_observability() {
        var manifest = loadRecipe("recipes/welfare-floor-checkup.recipe.yaml");
        // The defining property: read-only, so deploys:false and NO gates.
        assertThat(manifest.deploys()).isFalse();
        assertThat(manifest.stepsOfKind(StepKind.GATE)).isEmpty();
    }

    @Test void welfare_floor_checkup_report_surfaces_due_maintenance() {
        var manifest = loadRecipe("recipes/welfare-floor-checkup.recipe.yaml");
        var stdouts = welfareFloorCheckupHappy();
        // Drift present — report flags maintenance_due. Still SUCCESS (the
        // checkup never STOPs; it only reports).
        stdouts.put("report", "{\"checkup_ok\": 0, "
            + "\"maintenance_due\": [\"reembed-soul-fragments\"], "
            + "\"fragment_stale_embed\": 12}");
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of("agent_did", "did:test:companion"));
        assertSuccess(manifest, run);
    }

    private static LinkedHashMap<String, String> welfareFloorCheckupHappy() {
        var m = new LinkedHashMap<String, String>();
        m.put("fragments-health", "{\"fragment_total\": 150, "
            + "\"fragment_stale_embed\": 0, \"fragment_dup_text\": 0, "
            + "\"fragment_episodic_overdue\": 0}");
        m.put("graph-health", "{\"entity_total\": 80, \"entity_dup\": 0, "
            + "\"edge_total\": 400, \"edge_overdue\": 0}");
        m.put("report", "{\"checkup_ok\": 1, \"maintenance_due\": [], "
            + "\"fragment_total\": 150, \"entity_total\": 80}");
        return m;
    }

    // ── Recipe #1141 — mine-training-corpus (deploys:false, gateless) ──────

    @Test void mine_training_corpus_happy_path_runs_through_report() {
        var manifest = loadRecipe("recipes/mine-training-corpus.recipe.yaml");
        var run = stubbedRunnerHappyPath(mineTrainingCorpusHappy())
            .run(manifest, Map.of("agent_did", "did:test:companion"));
        assertSuccess(manifest, run);
    }

    @Test void mine_training_corpus_is_non_deploying_preparation() {
        var manifest = loadRecipe("recipes/mine-training-corpus.recipe.yaml");
        // Produces a corpus file + counts; trains nothing → deploys:false, no gates.
        assertThat(manifest.deploys()).isFalse();
        assertThat(manifest.stepsOfKind(StepKind.GATE)).isEmpty();
    }

    @Test void mine_training_corpus_report_recommends_personalize_when_corpus_ready() {
        var manifest = loadRecipe("recipes/mine-training-corpus.recipe.yaml");
        var stdouts = mineTrainingCorpusHappy();
        // Enough dialogues mined → corpus_ok=1 + advisory recommendation. Still
        // SUCCESS (mining never STOPs; it only prepares + reports).
        stdouts.put("report", "{\"corpus_ok\": 1, \"dialogues_mined\": 42, "
            + "\"recommendations\": [\"personalize-from-mined-conversations\"], "
            + "\"corpus_path\": \"/data/training/mined/x.jsonl\"}");
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of("agent_did", "did:test:companion"));
        assertSuccess(manifest, run);
    }

    private static LinkedHashMap<String, String> mineTrainingCorpusHappy() {
        var m = new LinkedHashMap<String, String>();
        m.put("mine", "{\"dialogues_mined\": 3, \"turns_mined\": 14, "
            + "\"examples_written\": 3, \"substrate_mean\": 0.2, "
            + "\"substrate_p95\": 0.4, \"corpus_path\": \"/data/training/mined/x.jsonl\"}");
        m.put("report", "{\"corpus_ok\": 0, \"dialogues_mined\": 3, "
            + "\"recommendations\": [], \"corpus_path\": \"/data/training/mined/x.jsonl\"}");
        return m;
    }

    // ── Recipe #1134 — prune-world-knowledge ──────────────────────────────

    @Test void prune_world_knowledge_happy_path_passes_both_welfare_gates() {
        var manifest = loadRecipe("recipes/prune-world-knowledge.recipe.yaml");
        var run = stubbedRunnerHappyPath(pruneWorldKnowledgeHappy())
            .run(manifest, Map.of("agent_did", "did:test:companion"));
        assertSuccess(manifest, run);
    }

    @Test void prune_world_knowledge_delta_gate_fails_on_runaway() {
        var manifest = loadRecipe("recipes/prune-world-knowledge.recipe.yaml");
        var stdouts = pruneWorldKnowledgeHappy();
        stdouts.put("plan", "{\"dead_count\": 80, \"key_delta_pct\": 80.0, "
            + "\"all_pruned_were_dead\": 1}");
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of("agent_did", "did:test:companion"));
        assertGateFailed(run, "gate-delta");
    }

    @Test void prune_world_knowledge_only_dead_gate_fails_when_live_fact_selected() {
        var manifest = loadRecipe("recipes/prune-world-knowledge.recipe.yaml");
        var stdouts = pruneWorldKnowledgeHappy();
        stdouts.put("plan", "{\"dead_count\": 2, \"key_delta_pct\": 2.0, "
            + "\"all_pruned_were_dead\": 0}");
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of("agent_did", "did:test:companion"));
        assertGateFailed(run, "gate-only-dead");
    }

    private static LinkedHashMap<String, String> pruneWorldKnowledgeHappy() {
        var m = new LinkedHashMap<String, String>();
        m.put("snapshot", "{\"pre_key_count\": 100}");
        m.put("plan", "{\"dead_count\": 3, \"key_delta_pct\": 3.0, "
            + "\"all_pruned_were_dead\": 1}");
        m.put("commit", "{\"pre_key_count\": 100, \"post_key_count\": 97, "
            + "\"key_delta_pct\": 3.0, \"all_pruned_were_dead\": 1}");
        return m;
    }

    // ── Recipe #1135 — recalibrate-oracle ─────────────────────────────────

    @Test void recalibrate_oracle_happy_path_runs_through_accuracy_gate() {
        var manifest = loadRecipe("recipes/recalibrate-oracle.recipe.yaml");
        var run = stubbedRunnerHappyPath(recalibrateOracleHappy())
            .run(manifest, Map.of("agent_did", "did:test:companion"));
        assertSuccess(manifest, run);
    }

    @Test void recalibrate_oracle_health_gate_stops_when_sidecar_unreachable() {
        var manifest = loadRecipe("recipes/recalibrate-oracle.recipe.yaml");
        var stdouts = recalibrateOracleHappy();
        // Oracle disabled / unreachable → clean STOP at gate-health (honest
        // no-op, not a false SUCCESS).
        stdouts.put("health", "{\"oracle_healthy\": 0}");
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of("agent_did", "did:test:companion"));
        assertGateFailed(run, "gate-health");
    }

    @Test void recalibrate_oracle_predictions_gate_fails_when_train_breaks_model() {
        var manifest = loadRecipe("recipes/recalibrate-oracle.recipe.yaml");
        var stdouts = recalibrateOracleHappy();
        stdouts.put("verify", "{\"predictions_count\": 0, "
            + "\"predictions_ok\": 0, \"accuracy_ok\": 1}");
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of("agent_did", "did:test:companion"));
        assertGateFailed(run, "gate-predictions");
    }

    @Test void recalibrate_oracle_accuracy_gate_fails_on_measured_regression() {
        var manifest = loadRecipe("recipes/recalibrate-oracle.recipe.yaml");
        var stdouts = recalibrateOracleHappy();
        stdouts.put("verify", "{\"predictions_count\": 4, "
            + "\"predictions_ok\": 1, \"post_accuracy\": 0.40, \"accuracy_ok\": 0}");
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of("agent_did", "did:test:companion"));
        assertGateFailed(run, "gate-accuracy");
    }

    private static LinkedHashMap<String, String> recalibrateOracleHappy() {
        var m = new LinkedHashMap<String, String>();
        m.put("health", "{\"oracle_healthy\": 1}");
        m.put("stats-before", "{\"incumbent_accuracy\": -1, \"has_metrics\": 0}");
        m.put("train", "{\"trained\": 1}");
        // No metric exposed → accuracy_ok=1 (can't gate on the unmeasurable);
        // health + predictions still protect.
        m.put("verify", "{\"predictions_count\": 3, \"predictions_ok\": 1, "
            + "\"post_accuracy\": -1, \"accuracy_ok\": 1}");
        return m;
    }

    // ── Recipe #1136/#1139 — research-pack-freshness (deploys:true, 2 gates) ─

    @Test void research_pack_freshness_happy_path_passes_both_welfare_gates() {
        var manifest = loadRecipe("recipes/research-pack-freshness.recipe.yaml");
        var run = stubbedRunnerHappyPath(researchPackFreshnessHappy())
            .run(manifest, Map.of("agent_did", "did:test:companion"));
        assertSuccess(manifest, run);
    }

    @Test void research_pack_freshness_is_a_gated_mutator() {
        var manifest = loadRecipe("recipes/research-pack-freshness.recipe.yaml");
        assertThat(manifest.deploys()).isTrue();
        // Two PERMANENT welfare gates bound the prune (percentage + absolute).
        assertThat(manifest.stepsOfKind(StepKind.GATE)).hasSize(2);
    }

    @Test void research_pack_freshness_delta_gate_stops_on_mass_dead() {
        var manifest = loadRecipe("recipes/research-pack-freshness.recipe.yaml");
        var stdouts = researchPackFreshnessHappy();
        // A global outage marks most of the index dead — the % cap must STOP
        // before pruning (never empty the library on a transient failure).
        stdouts.put("validate", "{\"checked\": 40, \"dead_sources\": 30, "
            + "\"dead_chunks\": 90, \"unchecked\": 0, \"dead_chunk_pct\": 90.0, "
            + "\"chunk_total\": 100}");
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of("agent_did", "did:test:companion"));
        assertGateFailed(run, "gate-delta");
    }

    @Test void research_pack_freshness_abs_gate_stops_on_huge_dead_count() {
        var manifest = loadRecipe("recipes/research-pack-freshness.recipe.yaml");
        var stdouts = researchPackFreshnessHappy();
        // Large index: 5% is still 5000 chunks — the absolute cap catches what
        // the percentage cap lets through.
        stdouts.put("validate", "{\"checked\": 9000, \"dead_sources\": 50, "
            + "\"dead_chunks\": 5000, \"unchecked\": 0, \"dead_chunk_pct\": 5.0, "
            + "\"chunk_total\": 100000}");
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of("agent_did", "did:test:companion"));
        assertGateFailed(run, "gate-abs");
    }

    private static LinkedHashMap<String, String> researchPackFreshnessHappy() {
        var m = new LinkedHashMap<String, String>();
        m.put("scan", "{\"pack_total\": 5, \"chunk_total\": 100, \"stale_unread\": 0}");
        // 3 dead chunks of 100 → 3% (< 25 pct cap) and 3 (< 500 abs cap).
        m.put("validate", "{\"checked\": 8, \"dead_sources\": 1, "
            + "\"dead_chunks\": 3, \"unchecked\": 0, \"dead_chunk_pct\": 3.0, "
            + "\"chunk_total\": 100}");
        m.put("prune", "{\"requested\": 3, \"pruned\": 3}");
        m.put("report", "{\"freshness_ok\": 0, \"pruned\": 3, "
            + "\"recommendations\": [\"re-acquire-dead-sources\"], "
            + "\"dead_chunks\": 3, \"pack_total\": 5}");
        return m;
    }

    // ── Recipe #1142 — tune-recipe-params (deploys:false, gateless) ───────

    @Test void tune_recipe_params_happy_path_runs_through_report() {
        var manifest = loadRecipe("recipes/tune-recipe-params.recipe.yaml");
        var run = stubbedRunnerHappyPath(tuneRecipeParamsHappy())
            .run(manifest, Map.of("target_recipe", "research-pack-freshness"));
        assertSuccess(manifest, run);
    }

    @Test void tune_recipe_params_is_a_gateless_bounded_mutator() {
        var manifest = loadRecipe("recipes/tune-recipe-params.recipe.yaml");
        // Honest shape: the floor-protection is enforced server-side at the
        // apply endpoint (RecipeParamTuner.validateNudge), NOT via recipe gates.
        // So it declares deploys:false and carries no GATE steps.
        assertThat(manifest.deploys()).isFalse();
        assertThat(manifest.stepsOfKind(StepKind.GATE)).isEmpty();
    }

    @Test void tune_recipe_params_report_recommends_remeasure_after_a_tune() {
        var manifest = loadRecipe("recipes/tune-recipe-params.recipe.yaml");
        var stdouts = tuneRecipeParamsHappy();
        // A param was actually nudged → advisory re-measure recommendation.
        // Still SUCCESS (tuning never STOPs; it acts within its own guardrail).
        stdouts.put("report", "{\"tuned_ok\": 1, \"target_recipe\": "
            + "\"research-pack-freshness\", \"tuned_count\": 1, "
            + "\"refused_count\": 0, \"skipped_floor_count\": 1, "
            + "\"recommendations\": [\"re-measure-tuned-recipe-next-window\"]}");
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of("target_recipe", "research-pack-freshness"));
        assertSuccess(manifest, run);
    }

    private static LinkedHashMap<String, String> tuneRecipeParamsHappy() {
        var m = new LinkedHashMap<String, String>();
        // No failing target yet (total_runs < min_runs) → no-op, but SUCCESS.
        m.put("tune", "{\"target_recipe\": \"research-pack-freshness\", "
            + "\"total_runs\": 2, \"fail_rate\": 0.0, \"tuned_count\": 0, "
            + "\"refused_count\": 0, \"skipped_floor_count\": 0, "
            + "\"stats_reachable\": 1}");
        m.put("report", "{\"tuned_ok\": 0, \"target_recipe\": "
            + "\"research-pack-freshness\", \"tuned_count\": 0, "
            + "\"refused_count\": 0, \"skipped_floor_count\": 0, "
            + "\"recommendations\": []}");
        return m;
    }

    // ── Recipe #1182 — rebake-argot ( living-language re-bake) ─

    @Test void rebake_argot_happy_path_runs_through_cleanup() {
        var manifest = loadRecipe("recipes/rebake-argot.recipe.yaml");
        var run = stubbedRunnerHappyPath(rebakeArgotHappy())
            .run(manifest, Map.of("zone_id", "zone-alpha",
                "argot_key_file", "/tmp/dryrun-argot.key"));
        assertSuccess(manifest, run);
        assertThat(manifest.deploys()).isTrue();
    }

    @Test void rebake_argot_gpu_required_step_fails_without_gpu() {
        var manifest = loadRecipe("recipes/rebake-argot.recipe.yaml");
        var stdouts = rebakeArgotHappy();
        stdouts.put("gpu-required", null); // null → stub returns exit 1
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of("zone_id", "z", "argot_key_file", "/tmp/k"));
        assertThat(run.status()).isEqualTo(RecipeRunner.Status.STEP_FAILED);
        assertThat(run.message()).contains("gpu-required");
    }

    @Test void rebake_argot_key_gate_fails_when_keyfile_absent() {
        var manifest = loadRecipe("recipes/rebake-argot.recipe.yaml");
        var stdouts = rebakeArgotHappy();
        stdouts.put("check-key", "{\"key_present\": 0}"); // public-seed / missing key
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of("zone_id", "z", "argot_key_file", "/tmp/k"));
        assertGateFailed(run, "gate-key");
    }

    @Test void rebake_argot_corpus_gate_fails_when_lines_below_floor() {
        var manifest = loadRecipe("recipes/rebake-argot.recipe.yaml");
        var stdouts = rebakeArgotHappy();
        stdouts.put("gen-corpus", "{\"corpus_lines\": 50}"); // < 200 floor
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of("zone_id", "z", "argot_key_file", "/tmp/k"));
        assertGateFailed(run, "gate-corpus");
    }

    @Test void rebake_argot_recall_gate_fails_when_adapter_cannot_author() {
        var manifest = loadRecipe("recipes/rebake-argot.recipe.yaml");
        var stdouts = rebakeArgotHappy();
        // The candidate doesn't reproduce the secret codebook → recall below floor.
        stdouts.put("verify", "{\"argot_recall\": 0.30, \"argot_fidelity\": 0.95, "
            + "\"argot_pass\": false}");
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of("zone_id", "z", "argot_key_file", "/tmp/k"));
        assertGateFailed(run, "gate-recall");
    }

    @Test void rebake_argot_fidelity_gate_fails_when_emitted_tokens_invalid() {
        var manifest = loadRecipe("recipes/rebake-argot.recipe.yaml");
        var stdouts = rebakeArgotHappy();
        // It emits §-tokens but they don't decode cleanly under the secret codebook.
        stdouts.put("verify", "{\"argot_recall\": 0.90, \"argot_fidelity\": 0.40, "
            + "\"argot_pass\": false}");
        var run = stubbedRunnerHappyPath(stdouts)
            .run(manifest, Map.of("zone_id", "z", "argot_key_file", "/tmp/k"));
        assertGateFailed(run, "gate-fidelity");
    }

    private static LinkedHashMap<String, String> rebakeArgotHappy() {
        var m = new LinkedHashMap<String, String>();
        m.put("gpu-required", "{\"gpu_available\": true, \"cuda_devices\": 1}");
        m.put("check-key", "{\"key_present\": 1}");
        m.put("gen-corpus", "{\"corpus_lines\": 800}");
        m.put("train", "{\"train_ok\": true, \"candidate\": \"runs/zone-alpha/candidate\"}");
        // P4 proved the adapter hits 100/100 vs base 0 — happy path mirrors that.
        m.put("verify", "{\"argot_recall\": 1.0, \"argot_fidelity\": 1.0, \"argot_pass\": true}");
        m.put("deploy", "{\"deploy_ok\": true, \"active\": \"adapters/wyrd-argot/zone-alpha\"}");
        m.put("cleanup-key", "{\"key_cleaned\": true}");
        return m;
    }

    // ── Stub harness ──────────────────────────────────────────────────────

    /** Loads a classpath-bundled recipe YAML for the test. */
    private static RecipeManifest loadRecipe(String path) {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(path)) {
            if (in == null) throw new AssertionError("missing recipe resource: " + path);
            String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return RecipeParser.parseManifest(yaml);
        } catch (IOException e) {
            throw new AssertionError("failed to load " + path, e);
        }
    }

    /**
     * Build a RecipeRunner whose CommandRunner emits canned JSON in order.
     * Each SHELL step pops the next entry from the map's insertion-order queue.
     * The {@code stepId} keys are documentation only — the stub pops by call
     * order, not by inspecting the command text (which is template-substituted
     * by the runner and bears no obvious relationship to the step id).
     *
     * <p>A {@code null} value forces exit 1 — used for SHELL-step failure
     * paths (e.g., gpu-required when no CUDA).</p>
     *
     * <p>If the recipe halts at a gate mid-stream the queue simply isn't
     * drained — that's fine.</p>
     */
    private static RecipeRunner stubbedRunnerHappyPath(LinkedHashMap<String, String> stdoutsByStep) {
        var queue = new ArrayDeque<>(stdoutsByStep.entrySet());
        CommandRunner stub = cmd -> {
            var e = queue.poll();
            if (e == null) {
                // Recipe called more SHELL steps than the test scripted.
                // Most likely rollback compensation after a failure — let it
                // proceed with a no-op success rather than crash the test.
                return new CommandRunner.Result(0, "{}", "");
            }
            if (e.getValue() == null) {
                return new CommandRunner.Result(1, "",
                    "stub forced failure at step '" + e.getKey() + "'", false);
            }
            return new CommandRunner.Result(0, e.getValue(), "");
        };
        // Dry-run = recipe LOGIC simulation (gates/steps), not a hardware check — so the
        // resource-requisite preflight (#req) must not block on the GPU-less CI box. Inject
        // a satisfy-all probe: abundant hardware + every declared DATA_FILE/CLOUD_KEY marked
        // present. (Live tier-3 tests exercise the real preflight against real hardware.)
        RecipeRunner.ResourceProbe satisfyAll = m -> {
            var files = new HashSet<String>();
            var keys = new HashSet<String>();
            for (var r : m.requires()) {
                if (r.kind() == ResourceRequirement.Kind.DATA_FILE && r.target() != null) files.add(r.target());
                if (r.kind() == ResourceRequirement.Kind.CLOUD_KEY && r.target() != null) keys.add(r.target());
            }
            return new ResourceRequisiteGate.Snapshot(
                List.of(64.0, 64.0, 64.0, 64.0), 256, 4096, files, keys);
        };
        return new RecipeRunner(stub, null, RecipeRunner.Sleeper.NOOP, satisfyAll);
    }

    /** Happy-path success assertions shared across the sweep. */
    private static void assertSuccess(RecipeManifest manifest, RecipeRunner.RecipeRun run) {
        assertEquals(RecipeRunner.Status.SUCCESS, run.status(),
            "recipe '" + manifest.recipe() + "' should reach SUCCESS, got "
                + run.status() + ": " + run.message());
        // Every step recorded an outcome.
        long ok = run.outcomes().stream().filter(o -> o.ok()).count();
        assertThat(ok)
            .as("recipe '%s' — all steps should be OK on happy path", manifest.recipe())
            .isEqualTo(run.outcomes().size());
    }

    /** Failure-path: recipe must STOP at exactly the named gate. */
    private static void assertGateFailed(RecipeRunner.RecipeRun run, String gateId) {
        assertThat(run.status())
            .as("expected GATE_FAILED at '%s', got %s (msg: %s)",
                gateId, run.status(), run.message())
            .isEqualTo(RecipeRunner.Status.GATE_FAILED);
        assertThat(run.message())
            .as("gate-failed message should name the gate that fired")
            .contains(gateId);
    }
}
