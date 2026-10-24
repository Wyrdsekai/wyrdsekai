package org.wyrdsekai.core.recipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The retrain recipe's {@code regression-probe} step, run the way the shipped
 * recipe runs it: the step's own command text, every {@code {{…}}} filled by
 * the runtime, started through {@link ProcessCommandRunner} on this JVM's java
 * and class path.
 *
 * <p>Until 2026-10-24 the step read {@code cli/build/probe-classpath.txt} (a
 * Gradle-written dev file) and named a class in the CLI module. Neither exists
 * on an installed node, so the step exited 1 on every household run from
 * 2026-09-22 and the recipe never deployed. This test is the dev-side half of
 * the proof; {@code packaging/build-dist.sh} runs the same probe from the
 * staged server jars, the install-side half.
 */
class TheProbeRunsOnTheNodesOwnClasspathTest {

    private static final String HEAD = "task_present";

    @Test
    void the_shipped_step_text_runs_the_probe_from_this_jvms_class_path(@TempDir Path tmp) throws Exception {
        Path repoRoot = repoRoot();
        assumeTrue(repoRoot != null, "repo root not found");
        Path pretrained = repoRoot.resolve("core/src/main/resources/classifier/pretrained");
        Path encoder = repoRoot.resolve(
                "core/src/main/resources/models/paraphrase-multilingual-MiniLM-L12-v2-setfit-q8.onnx");
        assumeTrue(Files.exists(encoder), "classifier encoder not fetched: " + encoder);
        assumeTrue(Files.exists(pretrained.resolve(HEAD + ".onnx")), "shipped head missing");

        var manifest = new RecipeService(tmp, null).inspect("retrain-classifier-head");
        RecipeStep.Shell probe = manifest.steps().stream()
                .filter(s -> s instanceof RecipeStep.Shell sh && sh.id().equals("regression-probe"))
                .map(s -> (RecipeStep.Shell) s)
                .findFirst().orElseThrow();

        // The recipe trains into /tmp/<head>.*; point the same command at the
        // shipped head instead so nothing outside this test's folder is touched.
        Path onnx = tmp.resolve(HEAD + ".onnx");
        Path labels = tmp.resolve(HEAD + ".labels.json");
        Files.copy(pretrained.resolve(HEAD + ".onnx"), onnx);
        Files.copy(pretrained.resolve(HEAD + ".labels.json"), labels);
        String stepText = probe.command().replace("/tmp/{{head}}", tmp.resolve("{{head}}").toString());

        var ctx = new RecipeContext(RecipeRuntimeFacts.ofThisProcess());
        ctx.putAll(Map.of(
                "head", HEAD,
                "max_overrouting_misses", 90,
                "max_overrouting_misses_per_lang", 90,
                "max_overrouting_misses_per_lang_map", ""));
        String command = ctx.resolve(stepText);
        assertThat(command).doesNotContain("{{").doesNotContain("probe-classpath");
        assertThat(command).contains(RecipeRuntimeFacts.javaBinary())
                .contains("org.wyrdsekai.core.agent.classifier.ProbeHeadMain");

        var result = new ProcessCommandRunner(repoRoot.toFile(), Duration.ofMinutes(4)).run(command);
        assertThat(result.exitCode()).as("stderr:\n" + tail(result.stderr())).isZero();

        var out = new ObjectMapper().readTree(result.stdout().trim());
        assertThat(out.path("anchors_tested").asInt()).isGreaterThan(0);
        assertThat(out.path("overrouting_probe_passes").asBoolean()).isTrue();
        assertThat(out.path("probe_space").asText()).isEqualTo("runtime-classifierarm");
    }

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && p != null; i++, p = p.getParent()) {
            if (Files.exists(p.resolve("core/src/main/resources/recipes/retrain-classifier-head.recipe.yaml"))) {
                return p;
            }
        }
        return null;
    }

    private static String tail(String s) {
        if (s == null) return "";
        return s.length() > 2000 ? s.substring(s.length() - 2000) : s;
    }
}
