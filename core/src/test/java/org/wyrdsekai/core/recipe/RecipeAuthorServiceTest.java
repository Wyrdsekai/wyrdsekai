package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1014 (OPEN-R1) — the author service write path: import (with
 * the authoring contract), overwrite guard, export round-trip, list, retire.
 */
class RecipeAuthorServiceTest {

    @TempDir Path recipesDir;
    private RecipeAuthorService svc;

    private static final String VALID = """
        recipe: nightly-freshness
        deploys: false
        params:
          agent_did: { type: string, default: "" }
        steps:
          - id: run
            kind: SHELL
            command: "python3 scripts/recipe/library_freshness.py report --agent-did {{agent_did}}"
          - id: gate-ok
            kind: GATE
            condition: "freshness_ok == 1"
            on_fail: STOP
        """;

    @BeforeEach void setUp() {
        // scriptsRoot null → skip the on-disk header check; structural + contract still apply.
        svc = new RecipeAuthorService(recipesDir, null, Set.of("retrain-classifier-head"));
    }

    @Test void imports_a_valid_recipe_and_writes_the_file() {
        var r = svc.importRecipe(VALID, "did:test:companion", false);
        assertThat(r.ok()).as(r.error()).isTrue();
        assertThat(r.name()).isEqualTo("nightly-freshness");
        assertThat(Files.isRegularFile(recipesDir.resolve("nightly-freshness.recipe.yaml"))).isTrue();
        assertThat(svc.listAuthored()).containsExactly("nightly-freshness");
    }

    @Test void refuses_to_overwrite_without_the_flag() {
        assertThat(svc.importRecipe(VALID, "did:test:c", false).ok()).isTrue();
        var second = svc.importRecipe(VALID, "did:test:c", false);
        assertThat(second.ok()).isFalse();
        assertThat(second.error()).contains("already exists");
    }

    @Test void overwrites_when_the_flag_is_set() {
        assertThat(svc.importRecipe(VALID, "did:test:c", false).ok()).isTrue();
        assertThat(svc.importRecipe(VALID, "did:test:c", true).ok()).isTrue();
    }

    @Test void rejects_a_structurally_broken_recipe() {
        // No steps → RecipeParser.validate throws → rejected, not a crash.
        var r = svc.importRecipe("recipe: empty\nsteps: []\n", "did:test:c", false);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).isNotBlank();
        assertThat(svc.listAuthored()).isEmpty();
    }

    @Test void rejects_an_authoring_contract_violation_with_violation_list() {
        var r = svc.importRecipe("""
            recipe: smuggled
            steps:
              - id: nuke
                kind: SHELL
                command: "rm -rf /"
            """, "did:test:c", false);
        assertThat(r.ok()).isFalse();
        assertThat(r.violations()).isNotEmpty();
        assertThat(String.join(" ", r.violations())).contains("scripts/ helper");
    }

    @Test void rejects_shadowing_a_bundled_name() {
        var r = svc.importRecipe("""
            recipe: retrain-classifier-head
            steps:
              - id: run
                kind: SHELL
                command: "python3 scripts/recipe/x.py"
            """, "did:test:c", false);
        assertThat(r.ok()).isFalse();
        assertThat(String.join(" ", r.violations())).contains("shadows a bundled");
    }

    @Test void exports_the_authored_yaml_verbatim() {
        svc.importRecipe(VALID, "did:test:c", false);
        var out = svc.exportRecipe("nightly-freshness");
        assertThat(out).isPresent();
        assertThat(out.get()).isEqualTo(VALID);
        assertThat(svc.exportRecipe("does-not-exist")).isEmpty();
    }

    @Test void removes_an_authored_recipe_but_never_a_bundled_name() {
        svc.importRecipe(VALID, "did:test:c", false);
        assertThat(svc.removeRecipe("nightly-freshness")).isTrue();
        assertThat(svc.listAuthored()).isEmpty();
        // Reserved/bundled name can't be removed through the authored compartment.
        assertThat(svc.removeRecipe("retrain-classifier-head")).isFalse();
        // A path-traversal name is refused.
        assertThat(svc.removeRecipe("../secrets")).isFalse();
    }
}
