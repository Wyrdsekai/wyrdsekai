package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** — manifest + leaf parse/validation. */
class RecipeParserTest {

    private static String resource(String path) {
        try (InputStream in = RecipeParserTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "missing test resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test void parses_bundled_retrain_classifier_head_recipe() {
        RecipeManifest m = RecipeParser.parseManifest(resource("recipes/retrain-classifier-head.recipe.yaml"));
        assertEquals("retrain-classifier-head", m.recipe());
        assertEquals(RecipeManifest.Ownership.RUN, m.ownership());
        assertTrue(m.deploys());
        // 13 steps: check-seeds + clean-corpus + expand-corpus + count-corpus
        // + gate-corpus + setfit-pretrain (#1018) + train + gate-accuracy
        // + regression-probe + gate-regression + deploy-encoder (#1018)
        // + deploy + smoke
        //
        // clean-corpus arrived with the corpus-hygiene work and the count was
        // never updated, so this test has been red since. Assert the roll-call
        // too: a bare count says nothing about WHICH step went missing.
        assertEquals(13, m.steps().size());
        assertEquals(
            List.of("check-seeds", "clean-corpus", "expand-corpus", "count-corpus",
                "gate-corpus", "setfit-pretrain", "train", "gate-accuracy",
                "regression-probe", "gate-regression", "deploy-encoder",
                "deploy", "smoke"),
            m.steps().stream().map(RecipeStep::id).toList());
        // params parsed with type + default
        assertTrue(m.params().get("head").required());
        assertEquals(0.80, ((Number) m.params().get("min_accuracy").defaultValue()).doubleValue(), 1e-9);
        // a deploy recipe must carry >= 2 gates (metric + regression) — §4
        assertEquals(3, m.stepsOfKind(StepKind.GATE).size());
        // #1023 — quiet-hours preference. The SetFit pretrain step is GPU-preferred
        // and CPU-fallback expensive; the bundled recipe declares [2,3,4] local.
        assertEquals(List.of(2, 3, 4), m.prefersHours());
    }

    @Test void parses_all_bundled_v01_recipes_cleanly() {
        // #1024-#1028 — every recipe shipped at OSS-release v0.1 must parse
        // without error and declare its welfare-permanent gate set.
        record Expected(String resource, int minSteps, int minPermGates,
                        boolean deploys, List<Integer> prefersHours) {}
        List<Expected> shipped = List.of(
            new Expected("recipes/retrain-classifier-head.recipe.yaml",
                12, 3, true, List.of(2, 3, 4)),
            new Expected("recipes/extract-steering-vector.recipe.yaml",
                7, 3, true, List.of(2, 3, 4)),
            new Expected("recipes/run-substrate-sft.recipe.yaml",
                10, 5, true, List.of(2, 3, 4)),
            new Expected("recipes/consolidate-memory-graph.recipe.yaml",
                5, 2, true, List.of()),
            new Expected("recipes/compact-library-index.recipe.yaml",
                8, 2, true, List.of(2, 3, 4)),
            new Expected("recipes/align-bondholder-voice.recipe.yaml",
                8, 3, true, List.of(2, 3, 4))
        );
        for (var e : shipped) {
            RecipeManifest m;
            try {
                m = RecipeParser.parseManifest(resource(e.resource()));
            } catch (Exception ex) {
                fail("recipe '" + e.resource() + "' failed to parse: " + ex);
                return;
            }
            assertTrue(m.steps().size() >= e.minSteps(),
                e.resource() + " expected ≥" + e.minSteps() + " steps, got " + m.steps().size());
            long permGates = m.stepsOfKind(StepKind.GATE).stream()
                .filter(s -> s instanceof RecipeStep.Gate g
                    && g.welfare() == RecipeStep.WelfareClass.PERMANENT)
                .count();
            assertTrue(permGates >= e.minPermGates(),
                e.resource() + " expected ≥" + e.minPermGates()
                    + " welfare:permanent gates, got " + permGates);
            assertEquals(e.deploys(), m.deploys(),
                e.resource() + " deploys mismatch");
            assertEquals(e.prefersHours(), m.prefersHours(),
                e.resource() + " prefers_hours mismatch");
        }
    }

    @Test void parses_prefers_hours_field_sorts_dedupes_and_validates() {
        // Sorted + deduped on the record canonical constructor;
        // parser-side bounds check catches authoring errors.
        String yamlOk = """
            recipe: prefer-hours-ok
            prefers_hours: [4, 2, 3, 2]
            steps:
              - { id: noop, kind: SHELL, command: "true" }
            """;
        RecipeManifest m = RecipeParser.parseManifest(yamlOk);
        assertEquals(List.of(2, 3, 4), m.prefersHours());

        // Missing prefers_hours → empty list (anytime).
        String yamlAnytime = """
            recipe: anytime
            steps:
              - { id: noop, kind: SHELL, command: "true" }
            """;
        assertTrue(RecipeParser.parseManifest(yamlAnytime).prefersHours().isEmpty());

        // Out-of-range hour → parse-time failure.
        String yamlBad = """
            recipe: bad-hour
            prefers_hours: [25]
            steps:
              - { id: noop, kind: SHELL, command: "true" }
            """;
        var ex = assertThrows(RecipeValidationException.class,
                () -> RecipeParser.parseManifest(yamlBad));
        assertTrue(ex.getMessage().contains("[0,23]"));

        // Non-array → parse-time failure.
        String yamlNotArray = """
            recipe: not-array
            prefers_hours: 2
            steps:
              - { id: noop, kind: SHELL, command: "true" }
            """;
        assertThrows(RecipeValidationException.class,
                () -> RecipeParser.parseManifest(yamlNotArray));
    }

    @Test void step_kinds_resolve_to_correct_records() {
        String yaml = """
            recipe: kinds-demo
            steps:
              - { id: a, kind: SHELL, command: "echo hi" }
              - { id: b, kind: GATE, condition: "x >= 1", on_fail: STOP }
              - { id: c, kind: BACKEND, prompt: "do it", tools: [shell], success_contract: "exit:0" }
              - { id: d, kind: DECISION, reads: out.json, branches: { pass: a, fail: b } }
              - { id: e, kind: GOOSE_RECIPE, recipe_ref: foo.yaml }
              - { id: f, kind: LONG_JOB, command: "train.sh", poll_seconds: 120, done_when: "ckpt exists" }
            """;
        RecipeManifest m = RecipeParser.parseManifest(yaml);
        assertInstanceOf(RecipeStep.Shell.class, m.steps().get(0));
        RecipeStep.Gate g = assertInstanceOf(RecipeStep.Gate.class, m.steps().get(1));
        assertTrue(g.stopsOnFail());
        // Default welfare class (OPEN-R4) — temporary unless explicitly authored.
        assertEquals(RecipeStep.WelfareClass.TEMPORARY, g.welfare());
        assertFalse(g.isPermanentWelfare());
        assertInstanceOf(RecipeStep.Backend.class, m.steps().get(2));
        assertInstanceOf(RecipeStep.Decision.class, m.steps().get(3));
        assertInstanceOf(RecipeStep.GooseRecipeRef.class, m.steps().get(4));
        RecipeStep.LongJob lj = assertInstanceOf(RecipeStep.LongJob.class, m.steps().get(5));
        assertEquals(120, lj.pollSeconds());
    }

    @Test void rejects_deploy_recipe_without_two_gates() {
        String yaml = """
            recipe: bad-deploy
            deploys: true
            steps:
              - { id: train, kind: SHELL, command: "train.sh" }
              - { id: only-gate, kind: GATE, condition: "acc >= 0.8", on_fail: STOP }
              - { id: deploy, kind: SHELL, command: "cp model prod/" }
            """;
        RecipeValidationException ex = assertThrows(RecipeValidationException.class,
                () -> RecipeParser.parseManifest(yaml));
        assertTrue(ex.getMessage().contains("2 GATE"));
    }

    @Test void rejects_gate_onfail_to_unknown_step() {
        String yaml = """
            recipe: bad-gate
            steps:
              - { id: g, kind: GATE, condition: "x", on_fail: nowhere }
            """;
        assertThrows(RecipeValidationException.class, () -> RecipeParser.parseManifest(yaml));
    }

    @Test void rejects_duplicate_step_ids() {
        String yaml = """
            recipe: dup
            steps:
              - { id: a, kind: SHELL, command: "x" }
              - { id: a, kind: SHELL, command: "y" }
            """;
        assertThrows(RecipeValidationException.class, () -> RecipeParser.parseManifest(yaml));
    }

    @Test void rejects_manifest_without_steps() {
        assertThrows(RecipeValidationException.class,
                () -> RecipeParser.parseManifest("recipe: empty\n"));
    }

    @Test void parses_goose_leaf_recipe_and_requires_body() {
        GooseRecipe r = RecipeParser.parseGooseRecipe("""
            version: 1.0.0
            title: Code review
            description: Review a repo
            instructions: Review the code at {{repo_path}} and report issues.
            parameters:
              - key: repo_path
                description: path to repo
                required: true
            settings:
              temperature: 0.2
            unknown_future_field: ignored
            """);
        assertEquals("Code review", r.title());
        assertTrue(r.hasRunnableBody());
        assertEquals("repo_path", r.parameters().get(0).key());

        // missing both instructions and prompt → invalid
        assertThrows(RecipeValidationException.class, () -> RecipeParser.parseGooseRecipe("""
            title: No body
            description: nothing to run
            """));
    }

    @Test void non_deploy_recipe_needs_no_gates() {
        RecipeManifest m = RecipeParser.parseManifest("""
            recipe: probe-only
            steps:
              - { id: a, kind: SHELL, command: "echo probe" }
            """);
        assertFalse(m.deploys());
        assertEquals(RecipeManifest.Ownership.RUN, m.ownership());
    }

    // -- #1012: per-step timeout override + manifest-level retry_count --------------

    @Test void parses_step_timeout_shorthand_for_shell_backend_longjob_goose() {
        // YAML carries one of each kind that supports a timeout override (#1012). The parser
        // must thread Duration through to the record field for every supported kind.
        RecipeManifest m = RecipeParser.parseManifest("""
            recipe: timeouts-demo
            steps:
              - { id: a, kind: SHELL,        command: "echo a", timeout: 90s }
              - { id: b, kind: BACKEND,      prompt: "p",       success_contract: "exit:0", timeout: 30m }
              - { id: c, kind: GOOSE_RECIPE, recipe_ref: leaf.yaml, timeout: 1h }
              - { id: d, kind: LONG_JOB,     command: "train", poll_seconds: 60, done_when: "ckpt exists", timeout: 2h }
              - { id: e, kind: SHELL,        command: "no override" }
            """);
        assertEquals(Duration.ofSeconds(90), m.steps().get(0).timeout());
        assertEquals(Duration.ofMinutes(30), m.steps().get(1).timeout());
        assertEquals(Duration.ofHours(1),    m.steps().get(2).timeout());
        assertEquals(Duration.ofHours(2),    m.steps().get(3).timeout());
        assertNull(m.steps().get(4).timeout(), "missing timeout → null (runner falls back to kind default)");
    }

    @Test void parses_manifest_retry_count_field() {
        // Explicit retry_count overrides the manifest-level default of 1.
        RecipeManifest m = RecipeParser.parseManifest("""
            recipe: retry-2
            retry_count: 2
            steps:
              - { id: a, kind: SHELL, command: "echo a" }
            """);
        assertEquals(2, m.retryCount());
    }

    @Test void manifest_retry_count_defaults_to_one_when_absent() {
        RecipeManifest m = RecipeParser.parseManifest("""
            recipe: default-retry
            steps:
              - { id: a, kind: SHELL, command: "echo a" }
            """);
        assertEquals(RecipeManifest.DEFAULT_RETRY_COUNT, m.retryCount());
        assertEquals(1, m.retryCount());
    }

    @Test void manifest_retry_count_zero_disables_retry() {
        RecipeManifest m = RecipeParser.parseManifest("""
            recipe: no-retry
            retry_count: 0
            steps:
              - { id: a, kind: SHELL, command: "echo a" }
            """);
        assertEquals(0, m.retryCount());
    }

    @Test void rejects_unparseable_step_timeout_at_parse_time() {
        // #1012: bad duration scalars fail fast, not at recipe-run time.
        RecipeValidationException ex = assertThrows(RecipeValidationException.class,
                () -> RecipeParser.parseManifest("""
                    recipe: bad-timeout
                    steps:
                      - { id: a, kind: SHELL, command: "echo a", timeout: "next tuesday" }
                    """));
        assertTrue(ex.getMessage().contains("invalid duration"),
                "expected duration parse error, got: " + ex.getMessage());
    }

    @Test void gate_welfare_permanent_when_explicitly_authored() {
        // OPEN-R4 closure: PERMANENT is opt-in, never inferred.
        // Mixed YAML covers (a) explicit permanent, (b) explicit temporary,
        // (c) bogus value falls back to temporary (forgiving parse), (d)
        // missing welfare key defaults to temporary, (e) case-insensitive.
        RecipeManifest m = RecipeParser.parseManifest("""
            recipe: welfare-tags
            deploys: true
            steps:
              - { id: a, kind: SHELL, command: "echo seed" }
              - { id: g1, kind: GATE, condition: "x", on_fail: STOP, welfare: permanent }
              - { id: g2, kind: GATE, condition: "y", on_fail: STOP, welfare: TEMPORARY }
              - { id: g3, kind: GATE, condition: "z", on_fail: STOP, welfare: "nonsense" }
              - { id: g4, kind: GATE, condition: "w", on_fail: STOP }
              - { id: g5, kind: GATE, condition: "v", on_fail: STOP, welfare: PERMANENT }
            """);
        var gates = m.steps().stream()
            .filter(s -> s instanceof RecipeStep.Gate)
            .map(s -> (RecipeStep.Gate) s)
            .toList();
        assertEquals(5, gates.size());
        assertTrue(gates.get(0).isPermanentWelfare(), "lowercase permanent");
        assertFalse(gates.get(1).isPermanentWelfare(), "explicit temporary");
        assertFalse(gates.get(2).isPermanentWelfare(), "bogus → temporary");
        assertFalse(gates.get(3).isPermanentWelfare(), "missing key → temporary");
        assertTrue(gates.get(4).isPermanentWelfare(), "uppercase PERMANENT");
    }
}
