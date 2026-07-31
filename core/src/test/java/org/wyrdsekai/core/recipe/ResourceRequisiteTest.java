package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.security.Denial;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 1 of the recipe resource-requisites feature: gate logic, manifest parsing, runner preflight. */
class ResourceRequisiteTest {

    private static ResourceRequisiteGate.Snapshot snap(List<Double> gpus, double ram, double disk,
                                                       Set<String> files, Set<String> keys) {
        return new ResourceRequisiteGate.Snapshot(gpus, ram, disk, files, keys);
    }

    // ── gate pure logic ──────────────────────────────────────────────────────

    @Test void emptyRequiresAlwaysAllows() {
        var d = ResourceRequisiteGate.evaluate(List.of(), snap(List.of(), 0, 0, Set.of(), Set.of()));
        assertTrue(d.allow());
        assertTrue(d.unmetHard().isEmpty());
    }

    @Test void hardGpuUnmetDenies() {
        var reqs = List.of(ResourceRequirement.hard(ResourceRequirement.Kind.GPU_COUNT, 2, "needs 2"));
        var d = ResourceRequisiteGate.evaluate(reqs, snap(List.of(48.0), 64, 100, Set.of(), Set.of()));
        assertFalse(d.allow());
        assertEquals(ResourceRequisiteGate.DenyReason.GPU_COUNT_INSUFFICIENT, d.firstReason());
        assertEquals(1, d.unmetHard().size());
    }

    @Test void gpuVramCoupledWithCount() {
        // 2 GPUs each ≥48: only one card is 48GB, the other is 24 → unmet.
        var reqs = List.of(
                ResourceRequirement.hard(ResourceRequirement.Kind.GPU_COUNT, 2, ""),
                ResourceRequirement.hard(ResourceRequirement.Kind.GPU_VRAM_GB, 48, ""));
        var twoSmall = ResourceRequisiteGate.evaluate(reqs, snap(List.of(48.0, 24.0), 64, 100, Set.of(), Set.of()));
        assertFalse(twoSmall.allow());
        var twoBig = ResourceRequisiteGate.evaluate(reqs, snap(List.of(48.0, 48.0), 64, 100, Set.of(), Set.of()));
        assertTrue(twoBig.allow());
    }

    @Test void softUnmetWarnsButAllows() {
        var reqs = List.of(ResourceRequirement.soft(ResourceRequirement.Kind.GPU_COUNT, 1, "prefers gpu"));
        var d = ResourceRequisiteGate.evaluate(reqs, snap(List.of(), 16, 100, Set.of(), Set.of()));
        assertTrue(d.allow());
        assertEquals(1, d.unmetSoft().size());
    }

    @Test void wallClockNeverBlocks() {
        // Declared hard:true in YAML, but the record forces WALL_CLOCK soft.
        var r = new ResourceRequirement(ResourceRequirement.Kind.WALL_CLOCK_MIN, 1035, null, true, "");
        assertFalse(r.hard());
        var d = ResourceRequisiteGate.evaluate(List.of(r), snap(List.of(), 0, 0, Set.of(), Set.of()));
        assertTrue(d.allow());
    }

    @Test void dataFilePresenceChecked() {
        var reqs = List.of(new ResourceRequirement(ResourceRequirement.Kind.DATA_FILE, 0,
                "data/x/bank.jsonl", true, "the rollout bank"));
        var missing = ResourceRequisiteGate.evaluate(reqs, snap(List.of(), 99, 99, Set.of(), Set.of()));
        assertFalse(missing.allow());
        assertEquals(ResourceRequisiteGate.DenyReason.DATA_FILE_MISSING, missing.firstReason());
        var present = ResourceRequisiteGate.evaluate(reqs, snap(List.of(), 99, 99, Set.of("data/x/bank.jsonl"), Set.of()));
        assertTrue(present.allow());
    }

    // ── manifest parsing ─────────────────────────────────────────────────────

    @Test void parserReadsRequiresBlock() {
        var m = RecipeParser.parseManifest("""
            recipe: r
            requires:
              - { kind: gpu_count, amount: 2, hard: true, note: "two cards" }
              - { kind: gpu_vram_gb, amount: 48 }
              - { kind: wall_clock_min, amount: 1035, hard: true }
              - { kind: data_file, target: data/x.jsonl, hard: true }
            steps:
              - id: s1
                kind: SHELL
                command: "echo {}"
            """);
        assertEquals(4, m.requires().size());
        // default hard=true when omitted
        assertTrue(m.requires().get(1).hard());
        // wall_clock forced soft despite hard:true
        assertFalse(m.requires().get(2).hard());
        assertEquals("data/x.jsonl", m.requires().get(3).target());
    }

    @Test void parserRejectsUnknownKind() {
        try {
            RecipeParser.parseManifest("""
                recipe: r
                requires:
                  - { kind: quantum_flux, amount: 1 }
                steps:
                  - id: s1
                    kind: SHELL
                    command: "echo {}"
                """);
            throw new AssertionError("expected validation failure on unknown kind");
        } catch (RecipeValidationException expected) { /* good */ }
    }

    // ── runner preflight ─────────────────────────────────────────────────────

    static final class StubCommands implements CommandRunner {
        private final Function<String, Result> f;
        StubCommands(Function<String, Result> f) { this.f = f; }
        public Result run(String command) { return f.apply(command); }
    }

    private static final String HEAVY_YAML = """
        recipe: heavy
        requires:
          - { kind: gpu_count, amount: 2, hard: true, note: "shards across 2 cards" }
          - { kind: gpu_vram_gb, amount: 48, hard: true }
        steps:
          - id: train
            kind: SHELL
            command: "printf '{\\"ok\\": true}\\n'"
        """;

    @Test void runnerBlocksWhenHardRequisiteUnmet() {
        var cmds = new StubCommands(c -> new CommandRunner.Result(0, "{\"ok\":true}", ""));
        // Probe reports NO GPU → hard reqs unmet.
        RecipeRunner.ResourceProbe noGpu = m -> snap(List.of(), 64, 100, Set.of(), Set.of());
        var run = new RecipeRunner(cmds, null, RecipeRunner.Sleeper.NOOP, noGpu)
                .run(RecipeParser.parseManifest(HEAVY_YAML), Map.of());
        assertEquals(RecipeRunner.Status.RESOURCE_DENIED, run.status());
        assertNotNull(run.resourceDenial());
        assertFalse(run.resourceDenial().allow());
        // The blocked run executed ZERO steps.
        assertTrue(run.outcomes().isEmpty());
    }

    @Test void runnerProceedsWhenRequisitesMet() {
        var cmds = new StubCommands(c -> new CommandRunner.Result(0, "{\"ok\":true}", ""));
        RecipeRunner.ResourceProbe twoBigGpus = m -> snap(List.of(48.0, 48.0), 64, 100, Set.of(), Set.of());
        var run = new RecipeRunner(cmds, null, RecipeRunner.Sleeper.NOOP, twoBigGpus)
                .run(RecipeParser.parseManifest(HEAVY_YAML), Map.of());
        // Not blocked by requisites — the step ran (status is whatever the step produced, not RESOURCE_DENIED).
        assertEquals(RecipeRunner.Status.SUCCESS, run.status());
        assertNull(run.resourceDenial());
        assertFalse(run.outcomes().isEmpty());
    }

    // ── steward-request connector (a) ────────────────────────────────────────

    @Test void deniedDecisionBuildsStewardRequest() {
        var reqs = List.of(ResourceRequirement.hard(ResourceRequirement.Kind.GPU_COUNT, 2, "shards"));
        var d = ResourceRequisiteGate.evaluate(reqs, snap(List.of(48.0), 64, 100, Set.of(), Set.of()));
        assertFalse(d.allow());
        Denial denial = ResourceRequest.forDeniedRun("run-emit-rft", d);
        assertEquals(ResourceRequest.CODE, denial.code());
        // Carries an emittable request_access template targeting the GPU resource.
        assertNotNull(denial.inWorldResolution());
        assertEquals("request_access", denial.inWorldResolution().action());
        assertEquals("resource:gpu", denial.inWorldResolution().source());
        // And a steward-terminal CLI hint.
        assertNotNull(denial.cliHint());
        assertTrue(denial.cliHint().containsKey("need"));
    }
}
