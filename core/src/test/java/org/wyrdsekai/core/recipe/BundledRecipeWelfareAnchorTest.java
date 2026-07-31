package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OPEN-R4 anchor test: the bundled `retrain-classifier-head` recipe's
 * three load-bearing gates MUST stay tagged welfare:permanent. They mirror the
 * welfare-floor contract from — auto-loosening
 * any of them silently breaks the floor.
 *
 * <p>If a future edit removes a PERMANENT tag, this test fails immediately —
 * the recipe author has to explicitly justify the downgrade.
 */
class BundledRecipeWelfareAnchorTest {

    private static final List<String> LOAD_BEARING_GATES = List.of(
        "gate-corpus", "gate-accuracy", "gate-regression");

    @Test void retrain_classifier_head_load_bearing_gates_are_permanent() {
        // Load via the same path RecipeService uses at runtime (classpath
        // discovery → RecipeParser). No filesystem dance — this proves the
        // shipped bundle stays correct.
        var service = new RecipeService(null, null);
        RecipeManifest manifest = service.inspect("retrain-classifier-head");
        // RecipeService accepts null runner — `inspect` only loads the manifest.
        assertNotNull(manifest, "bundled retrain-classifier-head must be discoverable");

        var gatesByid = manifest.steps().stream()
            .filter(s -> s instanceof RecipeStep.Gate)
            .map(s -> (RecipeStep.Gate) s)
            .collect(Collectors.toMap(
                RecipeStep.Gate::id, g -> g));

        for (String gateId : LOAD_BEARING_GATES) {
            RecipeStep.Gate g = gatesByid.get(gateId);
            assertNotNull(g, "load-bearing gate '" + gateId
                + "' must exist in retrain-classifier-head");
            assertTrue(g.isPermanentWelfare(),
                "gate '" + gateId + "' must be tagged welfare:permanent — "
                + "it mirrors the welfare floor "
                + "and must never be auto-loosened by Forge-side recipe revision. "
                + "If you are intentionally downgrading this gate, update this "
                + "anchor test AND document the rationale.");
            assertTrue(g.stopsOnFail(),
                "gate '" + gateId + "' must STOP on fail — the welfare floor "
                + "never allows a fall-through branch.");
        }
    }

    @Test void retrain_classifier_head_exposes_overrouting_probe_threshold_params() {
        // OPEN-R5 closure: bake vs production threshold parameters must remain
        // on the manifest. RecipeBakeMain overrides them (10/30 → 30/90 + 12/30
        // per-lang); CompanionActor runs use the defaults (6/90 + 3/30 per-lang).
        var manifest = new RecipeService(null, null)
            .inspect("retrain-classifier-head");
        Map<String, RecipeManifest.RecipeParam> params = manifest.params();

        var overall = params.get("max_overrouting_misses");
        assertNotNull(overall, "max_overrouting_misses param must exist");
        assertEquals(6, ((Number) overall.defaultValue()).intValue(),
            "production default is 6 misses out of 90 (~7%, multilingual)");

        var perLang = params.get("max_overrouting_misses_per_lang");
        assertNotNull(perLang,
            "max_overrouting_misses_per_lang must exist — catches silent "
            + "regression in one language even when totals stay under budget");
        assertEquals(3, ((Number) perLang.defaultValue()).intValue(),
            "production per-lang default is 3 misses out of 30");
    }
}
