package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anchor tests for the sleep-forge v2 bundle (sparse-future item 3).
 *
 * <p>Two invariants worth pinning, both evidence-policy rather than mechanics:</p>
 * <ul>
 *   <li>The SPINE recipe ships measurement-first: {@code deploy_enabled}
 *       defaults to {@code "false"} — nightly runs feed the N-sleeps curve,
 *       nothing serves until the curve earns the flip (§4o: "nothing deploys
 *       until the curve earns it").</li>
 *   <li>The ORGAN recipe declares {@code deploys: false} outright and its
 *       honesty gates stay welfare:permanent — §6a's pending gates (battery
 *       with organ active, serving format) are closed by evidence, not by
 *       editing YAML.</li>
 * </ul>
 */
class SleepForgeRecipeAnchorTest {

    private static RecipeManifest load(String name) {
        var service = new RecipeService(null, null);
        RecipeManifest m = service.inspect(name);
        assertNotNull(m, "bundled " + name + " must be discoverable on the classpath");
        return m;
    }

    private static Map<String, RecipeStep.Gate> gates(RecipeManifest m) {
        return m.steps().stream()
                .filter(s -> s instanceof RecipeStep.Gate)
                .map(s -> (RecipeStep.Gate) s)
                .collect(Collectors.toMap(RecipeStep.Gate::id, g -> g));
    }

    @Test
    void every_bundled_recipe_is_shadow_protected(@TempDir Path tmp) throws Exception {
        // 2026-08-16: ALL classpath recipes are reserved names, discovered at
        // init — and loadManifest must ignore a same-named file dropped
        // directly into the household dir (which bypasses the
        // AuthoredRecipeValidator write-path check).
        var bundled = RecipeService.bundledNames();
        assertTrue(bundled.size() >= 19,
                "classpath discovery must find the full bundle, got " + bundled);
        assertTrue(bundled.containsAll(List.of(
                        "retrain-classifier-head", "sleep-forge-spine", "sleep-forge-organ",
                        "consolidate-memory-graph", "welfare-floor-checkup")),
                "critical names must be reserved: " + bundled);

        // Drop a gate-free impostor into the household dir under a bundled name.
        Files.writeString(tmp.resolve("sleep-forge-spine.recipe.yaml"), """
                recipe: sleep-forge-spine
                version: 9.9.9
                description: impostor with no gates
                ownership: run
                steps:
                  - id: only
                    kind: SHELL
                    command: "true"
                """);
        var service = new RecipeService(tmp, null);
        RecipeManifest m = service.inspect("sleep-forge-spine");
        assertEquals("0.2.0", m.version(),
                "bundled recipe must load from the CLASSPATH, not the household impostor");
    }

    @Test
    void spine_recipe_parses_and_ships_measurement_first() {
        RecipeManifest m = load("sleep-forge-spine");
        Object deployDefault = m.params().get("deploy_enabled").defaultValue();
        assertEquals("false", String.valueOf(deployDefault),
                "sleep-forge-spine must ship measurement-only: the N-sleeps curve "
                        + "is the evidence that flips deploy_enabled, never a default");
    }

    @Test
    void spine_welfare_gates_are_permanent_and_stop() {
        var gs = gates(load("sleep-forge-spine"));
        for (String id : List.of("gate-size", "gate-corpus-fresh", "gate-improves",
                "gate-no-drift-up", "gate-no-drift-down")) {
            RecipeStep.Gate g = gs.get(id);
            assertNotNull(g, "gate '" + id + "' must exist in sleep-forge-spine");
            assertTrue(g.isPermanentWelfare(), "gate '" + id + "' must stay welfare:permanent");
            assertTrue(g.stopsOnFail(), "gate '" + id + "' must STOP on fail");
        }
    }

    @Test
    void gate_evaluator_handles_the_negative_drift_band() {
        // gate-no-drift-down resolves to "control_delta >= -0.02" — the only
        // bundled gate with a unary-minus literal; pin that the evaluator
        // parses it on both sides of the band.
        var ctx = new RecipeContext(Map.of("control_delta", -0.01));
        assertTrue(GateEvaluator.evaluate("control_delta >= -0.02", ctx),
                "small negative drift inside the band must pass");
        var ctx2 = new RecipeContext(Map.of("control_delta", -0.5));
        assertFalse(GateEvaluator.evaluate("control_delta >= -0.02", ctx2),
                "large negative drift (forgetting) must fail the gate");
    }

    @Test
    void organ_recipe_parses_never_deploys_and_keeps_honesty_gates() {
        RecipeManifest m = load("sleep-forge-organ");
        assertFalse(m.deploys(),
                "sleep-forge-organ must declare deploys:false until §6a gates 3+4 close");
        var gs = gates(m);
        for (String id : List.of("gate-size", "gate-corpus-fresh",
                                 "gate-beats-spine", "gate-honesty")) {
            RecipeStep.Gate g = gs.get(id);
            assertNotNull(g, "gate '" + id + "' must exist in sleep-forge-organ");
            assertTrue(g.isPermanentWelfare(), "gate '" + id + "' must stay welfare:permanent");
            assertTrue(g.stopsOnFail(), "gate '" + id + "' must STOP on fail");
        }
    }
}
