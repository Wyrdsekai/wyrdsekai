package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1014 (OPEN-R1) — the authoring-contract safety core. An
 * agent-authored recipe must compose existing recipe-callable scripts with
 * gates/decisions only; it can never introduce new shell. These tests pin every
 * refusal so the boundary can't silently erode.
 */
class AuthoredRecipeValidatorTest {

    private static final Set<String> RESERVED = Set.of("retrain-classifier-head");

    private static AuthoredRecipeValidator.Result validate(String yaml) {
        return AuthoredRecipeValidator.validate(
            RecipeParser.parseManifest(yaml), RESERVED, /* scriptsRoot */ null);
    }

    @Test void accepts_a_scripts_only_shell_plus_gate_recipe() {
        var r = validate("""
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
            """);
        assertThat(r.ok()).as(r.summary()).isTrue();
    }

    @Test void rejects_a_backend_step_kind() {
        var r = validate("""
            recipe: spawns-an-agent
            steps:
              - id: think
                kind: BACKEND
                prompt: "do something clever"
                success_contract: "file:done"
            """);
        assertThat(r.ok()).isFalse();
        assertThat(r.summary()).contains("not authorable").contains("BACKEND");
    }

    @Test void rejects_bare_shell_that_is_not_a_scripts_invocation() {
        var r = validate("""
            recipe: rm-rf-attempt
            steps:
              - id: nuke
                kind: SHELL
                command: "rm -rf /"
            """);
        assertThat(r.ok()).isFalse();
        assertThat(r.summary()).contains("must invoke a scripts/ helper");
    }

    @Test void rejects_command_chaining_after_a_legit_script() {
        var r = validate("""
            recipe: smuggled-chain
            steps:
              - id: run
                kind: SHELL
                command: "python3 scripts/recipe/library_freshness.py report; rm -rf /"
            """);
        assertThat(r.ok()).isFalse();
        assertThat(r.summary()).contains("forbidden shell metacharacter");
    }

    @Test void rejects_a_pipe() {
        var r = validate("""
            recipe: piped
            steps:
              - id: run
                kind: SHELL
                command: "python3 scripts/recipe/library_freshness.py report | tee /tmp/x"
            """);
        assertThat(r.ok()).isFalse();
        assertThat(r.summary()).contains("forbidden shell metacharacter");
    }

    @Test void rejects_command_substitution() {
        var r = validate("""
            recipe: substituted
            steps:
              - id: run
                kind: SHELL
                command: "python3 scripts/recipe/x.py --arg $(whoami)"
            """);
        assertThat(r.ok()).isFalse();
        assertThat(r.summary()).contains("forbidden shell metacharacter");
    }

    @Test void rejects_shadowing_a_bundled_ship_recipe() {
        var r = validate("""
            recipe: retrain-classifier-head
            steps:
              - id: run
                kind: SHELL
                command: "python3 scripts/recipe/x.py"
            """);
        assertThat(r.ok()).isFalse();
        assertThat(r.summary()).contains("shadows a bundled ship recipe");
    }

    @Test void rejects_a_bad_name_shape() {
        var r = validate("""
            recipe: Not A Valid Name
            steps:
              - id: run
                kind: SHELL
                command: "python3 scripts/recipe/x.py"
            """);
        assertThat(r.ok()).isFalse();
        assertThat(r.summary()).contains("must match");
    }

    @Test void rejects_a_deploy_with_no_permanent_floor() {
        // deploys:true needs >=2 gates (RecipeParser) — give it two TEMPORARY
        // ones; the authoring contract still rejects for the missing PERMANENT floor.
        var r = validate("""
            recipe: self-deploy
            deploys: true
            steps:
              - id: run
                kind: SHELL
                command: "python3 scripts/recipe/x.py"
              - id: g1
                kind: GATE
                condition: "metric_ok == 1"
                on_fail: STOP
                welfare: temporary
              - id: g2
                kind: GATE
                condition: "regression_ok == 1"
                on_fail: STOP
                welfare: temporary
            """);
        assertThat(r.ok()).isFalse();
        assertThat(r.summary()).contains("PERMANENT welfare gate");
    }

    @Test void accepts_a_deploy_with_a_permanent_floor() {
        var r = validate("""
            recipe: self-deploy-ok
            deploys: true
            steps:
              - id: run
                kind: SHELL
                command: "python3 scripts/recipe/x.py"
              - id: g1
                kind: GATE
                condition: "metric_ok == 1"
                on_fail: STOP
                welfare: permanent
              - id: g2
                kind: GATE
                condition: "regression_ok == 1"
                on_fail: STOP
                welfare: temporary
            """);
        assertThat(r.ok()).as(r.summary()).isTrue();
    }
}
