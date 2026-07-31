package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * recipe-callable invariant — unit + tier-2 coverage for
 * {@link RecipeCallableValidator} and {@link RecipeService}'s enforcement
 * hook. Closes #1004 follow-up: every script reachable from a recipe must
 * carry the local-ok header, so households can't accidentally enroll a
 * cloud-dependent recipe and break the OSS autonomy claim.
 */
class RecipeCallableValidatorTest {

    // ── pure validator function ──────────────────────────────────────────

    @Test
    void clean_manifest_with_header_present_passes(@TempDir Path scriptsRoot) throws Exception {
        var scriptPath = scriptsRoot.resolve("classifier/expand_corpus.py");
        Files.createDirectories(scriptPath.getParent());
        Files.writeString(scriptPath,
            "#!/usr/bin/env python3\n# recipe-callable: local-ok\nprint('hi')\n");

        var manifest = manifestWithBackendStep(
            "Run scripts/classifier/expand_corpus.py for head task_present");
        var violations = RecipeCallableValidator.validate(manifest, scriptsRoot);
        assertThat(violations).isEmpty();
    }

    @Test
    void manifest_referencing_script_without_header_yields_violation(
            @TempDir Path scriptsRoot) throws Exception {
        var scriptPath = scriptsRoot.resolve("classifier/expand_corpus.py");
        Files.createDirectories(scriptPath.getParent());
        // NO header — should be flagged
        Files.writeString(scriptPath, "#!/usr/bin/env python3\nimport sys\nprint('hi')\n");

        var manifest = manifestWithBackendStep(
            "Run scripts/classifier/expand_corpus.py to expand corpus");
        var violations = RecipeCallableValidator.validate(manifest, scriptsRoot);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).scriptPath())
            .isEqualTo("scripts/classifier/expand_corpus.py");
        assertThat(violations.get(0).reason()).contains("missing");
        assertThat(violations.get(0).reason()).contains(RecipeCallableValidator.HEADER_MARKER);
    }

    @Test
    void referenced_script_not_on_disk_yields_violation(@TempDir Path scriptsRoot) {
        var manifest = manifestWithBackendStep(
            "Run scripts/nonexistent/script.py to do a thing");
        var violations = RecipeCallableValidator.validate(manifest, scriptsRoot);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).reason()).contains("not found");
    }

    @Test
    void shell_step_command_is_scanned(@TempDir Path scriptsRoot) throws Exception {
        var scriptPath = scriptsRoot.resolve("classifier/train_classifier.py");
        Files.createDirectories(scriptPath.getParent());
        // NO header → violation expected
        Files.writeString(scriptPath, "#!/usr/bin/env python3\nimport sklearn\n");

        var manifest = new RecipeManifest(
            "test-recipe", "0.1.0", "desc", Map.of(),
            RecipeManifest.Ownership.RUN, false,
            List.of(new RecipeStep.Shell("train",
                "python3 scripts/classifier/train_classifier.py --foo bar")));
        var violations = RecipeCallableValidator.validate(manifest, scriptsRoot);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).stepId()).isEqualTo("train");
    }

    @Test
    void shell_rollback_command_is_scanned(@TempDir Path scriptsRoot) throws Exception {
        var scriptPath = scriptsRoot.resolve("recovery/rollback_classifier.sh");
        Files.createDirectories(scriptPath.getParent());
        // NO header
        Files.writeString(scriptPath, "#!/bin/bash\necho rollback\n");

        var manifest = new RecipeManifest(
            "test-recipe", "0.1.0", "desc", Map.of(),
            RecipeManifest.Ownership.RUN, false,
            List.of(new RecipeStep.Shell("deploy",
                "cp /tmp/new /tmp/prod",
                "bash scripts/recovery/rollback_classifier.sh")));
        var violations = RecipeCallableValidator.validate(manifest, scriptsRoot);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).scriptPath())
            .isEqualTo("scripts/recovery/rollback_classifier.sh");
    }

    @Test
    void longjob_command_is_scanned(@TempDir Path scriptsRoot) throws Exception {
        var scriptPath = scriptsRoot.resolve("training/long_run.py");
        Files.createDirectories(scriptPath.getParent());
        Files.writeString(scriptPath,
            "#!/usr/bin/env python3\n# recipe-callable: local-ok\nprint('long')\n");

        var manifest = new RecipeManifest(
            "test-recipe", "0.1.0", "desc", Map.of(),
            RecipeManifest.Ownership.RUN, false,
            List.of(new RecipeStep.LongJob("train",
                "python3 scripts/training/long_run.py --epochs 10", 30, "exit:0")));
        var violations = RecipeCallableValidator.validate(manifest, scriptsRoot);
        assertThat(violations).isEmpty();
    }

    @Test
    void multiple_script_references_in_one_step_all_validated(
            @TempDir Path scriptsRoot) throws Exception {
        var good = scriptsRoot.resolve("a/good.py");
        Files.createDirectories(good.getParent());
        Files.writeString(good,
            "#!/usr/bin/env python3\n# recipe-callable: local-ok\n");
        var bad = scriptsRoot.resolve("b/bad.py");
        Files.createDirectories(bad.getParent());
        Files.writeString(bad, "#!/usr/bin/env python3\n");  // no header

        var manifest = manifestWithBackendStep(
            "Run scripts/a/good.py then scripts/b/bad.py to test both");
        var violations = RecipeCallableValidator.validate(manifest, scriptsRoot);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).scriptPath()).isEqualTo("scripts/b/bad.py");
    }

    @Test
    void null_scripts_root_skips_validation() {
        var manifest = manifestWithBackendStep(
            "Run scripts/whatever/missing.py");
        // null scriptsRoot → silent skip (test context)
        var violations = RecipeCallableValidator.validate(manifest, null);
        assertThat(violations).isEmpty();
    }

    @Test
    void gate_and_decision_steps_skipped_no_shell_text(@TempDir Path scriptsRoot) {
        var manifest = new RecipeManifest(
            "test-recipe", "0.1.0", "desc", Map.of(),
            RecipeManifest.Ownership.RUN, false,
            List.of(
                new RecipeStep.Gate("g", "x >= 1", RecipeStep.Gate.STOP),
                new RecipeStep.Decision("d", "outcome", Map.of("yes", "g"))));
        var violations = RecipeCallableValidator.validate(manifest, scriptsRoot);
        assertThat(violations).isEmpty();
    }

    // ── header detection edge cases ──────────────────────────────────────

    @Test
    void header_works_with_shebang_and_blank_before(@TempDir Path scriptsRoot) throws Exception {
        var scriptPath = scriptsRoot.resolve("test.py");
        Files.writeString(scriptPath,
            "#!/usr/bin/env python3\n\n# some other comment\n# recipe-callable: local-ok\n");
        var manifest = manifestWithBackendStep("Run scripts/test.py");
        var violations = RecipeCallableValidator.validate(manifest, scriptsRoot);
        assertThat(violations).isEmpty();
    }

    @Test
    void header_past_10_lines_does_not_count(@TempDir Path scriptsRoot) throws Exception {
        var scriptPath = scriptsRoot.resolve("test.py");
        var sb = new StringBuilder("#!/usr/bin/env python3\n");
        for (int i = 0; i < 20; i++) sb.append("# line ").append(i).append("\n");
        sb.append("# recipe-callable: local-ok\n");
        Files.writeString(scriptPath, sb.toString());

        var manifest = manifestWithBackendStep("Run scripts/test.py");
        var violations = RecipeCallableValidator.validate(manifest, scriptsRoot);
        assertThat(violations).hasSize(1);
    }

    // ── RecipeService.loadManifest enforcement (tier-2 integration) ──────

    @Test
    void recipe_service_throws_when_scripts_root_set_and_violation_found(
            @TempDir Path tmp) throws Exception {
        var scriptsRoot = tmp.resolve("scripts");
        Files.createDirectories(scriptsRoot.resolve("classifier"));
        Files.writeString(scriptsRoot.resolve("classifier/bad.py"),
            "#!/usr/bin/env python3\n");  // no header

        var recipesDir = tmp.resolve("recipes");
        Files.createDirectories(recipesDir);
        var recipeYaml = """
            recipe: test-bad-script
            version: 0.1.0
            description: test
            deploys: false
            ownership: run
            steps:
              - id: do-thing
                kind: SHELL
                command: "python3 scripts/classifier/bad.py"
            """;
        Files.writeString(recipesDir.resolve("test-bad-script.recipe.yaml"), recipeYaml);

        var service = new RecipeService(
            recipesDir,
            new RecipeRunner(new ProcessCommandRunner(tmp.toFile(),
                Duration.ofSeconds(10))),
            null,
            scriptsRoot);

        assertThatThrownBy(() -> service.inspect("test-bad-script"))
            .isInstanceOf(RecipeValidationException.class)
            .hasMessageContaining("recipe-callable invariant")
            .hasMessageContaining("scripts/classifier/bad.py")
            .hasMessageContaining(RecipeCallableValidator.HEADER_MARKER);
    }

    @Test
    void recipe_service_passes_when_scripts_root_set_and_script_clean(
            @TempDir Path tmp) throws Exception {
        var scriptsRoot = tmp.resolve("scripts");
        Files.createDirectories(scriptsRoot.resolve("classifier"));
        Files.writeString(scriptsRoot.resolve("classifier/good.py"),
            "#!/usr/bin/env python3\n# recipe-callable: local-ok\nprint('ok')\n");

        var recipesDir = tmp.resolve("recipes");
        Files.createDirectories(recipesDir);
        var recipeYaml = """
            recipe: test-good-script
            version: 0.1.0
            description: test
            deploys: false
            ownership: run
            steps:
              - id: do-thing
                kind: SHELL
                command: "python3 scripts/classifier/good.py"
            """;
        Files.writeString(recipesDir.resolve("test-good-script.recipe.yaml"), recipeYaml);

        var service = new RecipeService(
            recipesDir,
            new RecipeRunner(new ProcessCommandRunner(tmp.toFile(),
                Duration.ofSeconds(10))),
            null,
            scriptsRoot);

        // inspect doesn't throw — manifest loads clean
        var manifest = service.inspect("test-good-script");
        assertThat(manifest.recipe()).isEqualTo("test-good-script");
    }

    @Test
    void recipe_service_skips_validation_when_scripts_root_null(
            @TempDir Path tmp) throws Exception {
        var recipesDir = tmp.resolve("recipes");
        Files.createDirectories(recipesDir);
        var recipeYaml = """
            recipe: test-unchecked
            version: 0.1.0
            description: test
            deploys: false
            ownership: run
            steps:
              - id: do-thing
                kind: SHELL
                command: "python3 scripts/nonexistent/totally_missing.py"
            """;
        Files.writeString(recipesDir.resolve("test-unchecked.recipe.yaml"), recipeYaml);

        // scriptsRoot=null → no validation, manifest loads even with broken refs
        var service = new RecipeService(
            recipesDir,
            new RecipeRunner(new ProcessCommandRunner(tmp.toFile(),
                Duration.ofSeconds(10))));

        var manifest = service.inspect("test-unchecked");
        assertThat(manifest.recipe()).isEqualTo("test-unchecked");
    }

    // ── production-recipe smoke ──────────────────────────────────────────

    /**
     * Sanity check that the actual shipped retrain-classifier-head recipe
     * passes the invariant against the real {@code scripts/} directory.
     * This is the canary that fires if either (a) a recipe gets edited to
     * reference a new script without the header, or (b) the header gets
     * accidentally stripped from a long-standing script.
     */
    @Test
    void shipped_retrain_classifier_head_recipe_passes_against_real_scripts() {
        var repoRoot = findRepoRoot();
        if (repoRoot == null) return;  // skip when source tree absent
        var scriptsRoot = repoRoot.resolve("scripts");
        if (!Files.isDirectory(scriptsRoot)) return;

        var manifest = RecipeParser.parseManifest(loadClasspathRecipe(
            "retrain-classifier-head"));
        var violations = RecipeCallableValidator.validate(manifest, scriptsRoot);
        assertThat(violations)
            .as("shipped retrain-classifier-head recipe must clear the "
                + "recipe-callable invariant: %s",
                RecipeCallableValidator.summarize(violations))
            .isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static RecipeManifest manifestWithBackendStep(String prompt) {
        return new RecipeManifest(
            "test-recipe", "0.1.0", "desc", Map.of(),
            RecipeManifest.Ownership.RUN, false,
            List.of(new RecipeStep.Backend("the-step", prompt,
                List.of("shell"), "exit:0")));
    }

    private static Path findRepoRoot() {
        var dir = Paths.get(System.getProperty("user.dir"))
            .toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(
                    "scripts/classifier/train_classifier.py"))) {
                return dir;
            }
        }
        return null;
    }

    private static String loadClasspathRecipe(String name) {
        try (var in = RecipeCallableValidatorTest.class.getClassLoader()
                .getResourceAsStream("recipes/" + name + ".recipe.yaml")) {
            if (in == null) throw new RuntimeException("classpath recipe not found: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
