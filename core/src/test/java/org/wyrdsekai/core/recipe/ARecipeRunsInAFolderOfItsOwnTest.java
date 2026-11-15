package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A recipe's steps share a scratch folder made for the run and removed after it. The classifier
 * retrain wrote to fixed names in /tmp: on the build box the node's night retrain, as root, left
 * /tmp/task_present.onnx there and the release bake, as the builder, could not replace it, so the
 * bake stopped (2026-11-14). Two runs at once would have read each other's files.
 */
class ARecipeRunsInAFolderOfItsOwnTest {

    @TempDir Path work;

    private static final String RECIPE = """
        recipe: scratch-test
        version: 0.1.0
        description: two steps share the run's own folder
        deploys: false
        ownership: run
        params:
          out:
            type: string
            default: "{{run_scratch}}/out.txt"
        steps:
          - id: write
            kind: SHELL
            command: |-
              printf hello > '{{out}}' && printf '{"seen_by_write": "%s"}\\n' '{{run_scratch}}'
          - id: read
            kind: SHELL
            command: |-
              test "$(cat '{{run_scratch}}/out.txt')" = hello
        """;

    private RecipeRunner.RecipeRun runOnce() {
        var runner = new RecipeRunner(new ProcessCommandRunner(work.toFile(), Duration.ofSeconds(30)));
        return runner.run(RecipeParser.parseManifest(RECIPE), Map.of());
    }

    @Test
    void the_steps_share_a_folder_of_the_runs_own_and_it_is_gone_after() {
        var run = runOnce();

        assertThat(run.succeeded()).as(run.message()).isTrue();
        var folder = Path.of((String) run.context().get(RecipeRunner.RUN_SCRATCH));
        assertThat(run.context().get("seen_by_write")).isEqualTo(folder.toString());
        assertThat(run.context().get("out")).isEqualTo(folder.resolve("out.txt").toString());
        assertThat(folder).as("removed when the run ends").doesNotExist();
        assertThat(folder.toString()).doesNotStartWith(work.toString());
    }

    @Test
    void two_runs_never_share_one() {
        var a = runOnce();
        var b = runOnce();

        assertThat(a.succeeded()).isTrue();
        assertThat(b.succeeded()).isTrue();
        assertThat(a.context().get(RecipeRunner.RUN_SCRATCH)).isNotEqualTo(b.context().get(RecipeRunner.RUN_SCRATCH));
    }

    @Test
    void the_folder_is_removed_when_a_step_fails_too() throws Exception {
        var failing = RECIPE.replace("= hello", "= goodbye");
        var run = new RecipeRunner(new ProcessCommandRunner(work.toFile(), Duration.ofSeconds(30)))
            .run(RecipeParser.parseManifest(failing), Map.of());

        assertThat(run.succeeded()).isFalse();
        assertThat(Path.of((String) run.context().get(RecipeRunner.RUN_SCRATCH))).doesNotExist();
        try (var left = Files.list(work)) {
            assertThat(left).as("nothing written in the working folder").isEmpty();
        }
    }

    @Test
    void the_shipped_classifier_retrain_names_no_fixed_path_in_tmp() throws Exception {
        try (var in = getClass().getResourceAsStream("/recipes/retrain-classifier-head.recipe.yaml")) {
            assertThat(in).isNotNull();
            var text = new String(in.readAllBytes());
            assertThat(text).doesNotContain("/tmp/");
            assertThat(text).contains("{{run_scratch}}/{{head}}.onnx");
        }
    }
}
