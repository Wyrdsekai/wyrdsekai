package org.wyrdsekai.core.recipe;

import java.util.List;
import java.util.Map;

/**
 * A Wyrdsekai recipe manifest — the outer governed pipeline.
 *
 * <p>Thin and ours: an ordered list of typed {@link RecipeStep}s plus parameters and
 * an {@link Ownership} floor. The leaf executable units (GOOSE_RECIPE steps) reuse the
 * Goose recipe format ({@link GooseRecipe}); the gates, deploy/rollback, and runner are
 * Wyrdsekai's (GoalExecutor/Pekko). for the layering.
 *
 * <p>#1012 — {@code retryCount} is the manifest-level retry budget the {@link RecipeRunner}
 * applies to transient failures (timeout, OOM-kill, IOException, network timeout) on
 * SHELL / BACKEND / GOOSE_RECIPE / LONG_JOB steps. Logical failures (exit 1, success_contract
 * miss) never retry. Default 1; {@code retry_count: 0} disables.
 */
public record RecipeManifest(
        String recipe,                       // unique recipe name
        String version,                      // semver of this manifest
        String description,
        Map<String, RecipeParam> params,     // declared inputs (Jinja {{ }} in leaf recipes)
        Ownership ownership,                 // floor: who may run/modify/author
        boolean deploys,                     // true if any step writes a production artifact
        List<RecipeStep> steps,
        int retryCount,                      // transient-failure retry budget per step (#1012)
        List<Integer> prefersHours,          // quiet-hours preference (#1023); empty = anytime
        List<ResourceRequirement> requires   // declared hardware/data needs, preflight-checked
) {
    /** Default retry budget when the manifest omits {@code retry_count}. */
    public static final int DEFAULT_RETRY_COUNT = 1;

    public RecipeManifest {
        if (retryCount < 0) retryCount = 0;
        // Defensive copy + bounds check on prefersHours. Hours outside 0-23 silently
        // dropped — bad YAML shouldn't blow up the runtime, but the bake-time parser
        // (RecipeParser.validate) catches them with a clearer error.
        if (prefersHours == null) {
            prefersHours = List.of();
        } else {
            prefersHours = prefersHours.stream()
                .filter(h -> h != null && h >= 0 && h <= 23)
                .distinct().sorted().toList();
        }
        requires = (requires == null) ? List.of() : List.copyOf(requires);
    }

    /** Backwards-compat constructor — defaults retryCount + empty prefersHours + no requires. */
    public RecipeManifest(String recipe, String version, String description,
                          Map<String, RecipeParam> params, Ownership ownership,
                          boolean deploys, List<RecipeStep> steps) {
        this(recipe, version, description, params, ownership, deploys, steps,
                DEFAULT_RETRY_COUNT, List.of(), List.of());
    }

    /** Backwards-compat constructor — kept for callers pre-{@code prefersHours} (#1023). */
    public RecipeManifest(String recipe, String version, String description,
                          Map<String, RecipeParam> params, Ownership ownership,
                          boolean deploys, List<RecipeStep> steps, int retryCount) {
        this(recipe, version, description, params, ownership, deploys, steps,
                retryCount, List.of(), List.of());
    }

    /** Backwards-compat constructor — kept for callers pre-{@code requires}. */
    public RecipeManifest(String recipe, String version, String description,
                          Map<String, RecipeParam> params, Ownership ownership,
                          boolean deploys, List<RecipeStep> steps, int retryCount,
                          List<Integer> prefersHours) {
        this(recipe, version, description, params, ownership, deploys, steps,
                retryCount, prefersHours, List.of());
    }

    /** Who is permitted to act on a recipe. Floor — gates always fire regardless. */
    public enum Ownership { RUN, MODIFY, AUTHOR }

    public record RecipeParam(String type, boolean required, Object defaultValue) {}

    /** Steps of a given kind, in order. */
    public List<RecipeStep> stepsOfKind(StepKind kind) {
        return steps.stream().filter(s -> s.kind() == kind).toList();
    }
}
