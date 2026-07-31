package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * the reward oracle. The RFT loop trusts {@code validate()} to
 * mean exactly what the in-world {@code shape_recipe} gate means: a recipe earns full
 * reward iff it would be accepted live. These pin the three reward tiers — valid /
 * parses-but-contract-fails / won't-parse — against the real
 * {@link RecipeParser} + {@link AuthoredRecipeValidator} path.
 */
class RecipeValidateServerTest {

    // A recipe an agent could legitimately author: deploys:false, one SHELL step that
    // invokes a scripts/ helper (the only shell shape the authoring contract allows).
    private static final String VALID = """
        recipe: probe-emit-valid
        version: 0.1.0
        description: a well-formed authored recipe
        deploys: false
        ownership: run
        steps:
          - id: do-thing
            kind: SHELL
            command: scripts/recipes/do_thing.py --x 1
        """;

    // Parses fine, but a BACKEND step is steward/ship-only — not authorable.
    private static final String CONTRACT_FAIL = """
        recipe: probe-emit-backend
        version: 0.1.0
        description: reaches for a step kind agents may not author
        deploys: false
        ownership: run
        steps:
          - id: spawn
            kind: BACKEND
            command: do something
        """;

    // Missing the required 'recipe' name → RecipeParser throws → won't parse at all.
    private static final String PARSE_FAIL = """
        version: 0.1.0
        description: no recipe name
        steps: []
        """;

    @Test
    void valid_recipe_scores_full() {
        Map<String, Object> r = RecipeValidateServer.validate(VALID, null);
        assertThat(r.get("parsed")).isEqualTo(true);
        assertThat(r.get("valid")).isEqualTo(true);
        assertThat((List<?>) r.get("violations")).isEmpty();
    }

    @Test
    void parses_but_contract_fails_is_distinguishable() {
        Map<String, Object> r = RecipeValidateServer.validate(CONTRACT_FAIL, null);
        // reached a parseable recipe (partial credit) but not a valid one (no full credit)
        assertThat(r.get("parsed")).isEqualTo(true);
        assertThat(r.get("valid")).isEqualTo(false);
        assertThat((List<?>) r.get("violations")).isNotEmpty();
    }

    @Test
    void unparseable_yaml_is_flagged() {
        Map<String, Object> r = RecipeValidateServer.validate(PARSE_FAIL, null);
        assertThat(r.get("parsed")).isEqualTo(false);
        assertThat(r.get("parse_error")).asString().isNotBlank();
        assertThat(r.get("valid")).isEqualTo(false);
    }

    @Test
    void blank_is_not_parsed() {
        Map<String, Object> r = RecipeValidateServer.validate("   ", null);
        assertThat(r.get("parsed")).isEqualTo(false);
        assertThat(r.get("valid")).isEqualTo(false);
    }
}
