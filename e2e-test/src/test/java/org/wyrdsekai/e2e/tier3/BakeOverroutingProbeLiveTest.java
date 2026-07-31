package org.wyrdsekai.e2e.tier3;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OPEN-R5 follow-up #1010: live verify that the build-time
 * release bake threshold (max_overrouting_misses=30 + per_lang=12) holds
 * across the actual {@code probe_overrouting.py} contract — even with the
 * bake's intentionally-coarser classifier.
 *
 * <p>The earlier B2 cycle ran the full bake on home-server end-to-end; this test
 * is the regression guard for the threshold itself. It exercises
 * {@code probe_overrouting.py} against the production ONNX at the bake-time
 * thresholds and asserts:</p>
 *
 * <ol>
 *   <li>The probe completes (90 anchors, all 3 langs)</li>
 *   <li>{@code overrouting_probe_passes == true} at bake thresholds (30/90 + 12/30 per-lang)</li>
 *   <li>{@code anchors_tested == 90} (3 langs × 30 anchors each)</li>
 *   <li>{@code per_lang} summary lands in stdout JSON</li>
 *   <li>At least one per-lang count is non-zero (proves the probe actually classified)</li>
 * </ol>
 *
 * <p>If this test fails because the bake-time classifier got dramatically worse
 * (e.g. one language flipped fully to "none"), operator sees it before the bake
 * lands a broken ONNX into pretrained/. If it fails because the thresholds
 * are out of sync between recipe + RecipeBakeMain, that's a constants drift
 * to fix.</p>
 *
 * <p>Run on home-server with prod ONNX present (post-release-bake):</p>
 * <pre>
 *   WYRDSEKAI_BAKE_PROBE_E2E=1 ./gradlew :e2e-test:test \
 *     --tests "org.wyrdsekai.e2e.tier3.BakeOverroutingProbeLiveTest"
 * </pre>
 */
@Tag("tier3")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_BAKE_PROBE_E2E", matches = "1|true")
class BakeOverroutingProbeLiveTest {

    // Bake-time thresholds (mirrors cli/.../RecipeBakeMain putIfAbsent calls).
    // If RecipeBakeMain.java diverges from these, that's the bug the test catches.
    private static final int BAKE_MAX_MISSES = 30;
    private static final int BAKE_MAX_MISSES_PER_LANG = 12;
    private static final int EXPECTED_ANCHORS = 90;

    private static final Path REPO_ROOT = locateRepoRoot();

    @Test
    void bake_threshold_holds_against_production_classifier(@TempDir Path tmp) throws Exception {
        Path prodOnnx = REPO_ROOT.resolve(
            "core/src/main/resources/classifier/pretrained/task_present.onnx");
        Path prodLabels = REPO_ROOT.resolve(
            "core/src/main/resources/classifier/pretrained/task_present.labels.json");
        assumeTrue(Files.exists(prodOnnx),
            "production task_present.onnx must exist (post-release-bake commit)");
        assumeTrue(Files.exists(prodLabels),
            "production task_present.labels.json must exist");

        // Run against a copy in tmp to keep the test side-effect-free, even
        // though the probe is read-only against the ONNX.
        Path onnxCopy = tmp.resolve("task_present.onnx");
        Path labelsCopy = tmp.resolve("task_present.labels.json");
        Files.copy(prodOnnx, onnxCopy, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(prodLabels, labelsCopy, StandardCopyOption.REPLACE_EXISTING);

        ProcessBuilder pb = new ProcessBuilder(
            "python3",
            "scripts/classifier/probe_overrouting.py",
            "--head", "task_present",
            "--classifier", onnxCopy.toString(),
            "--labels", labelsCopy.toString(),
            "--max-misses", String.valueOf(BAKE_MAX_MISSES),
            "--max-misses-per-lang", String.valueOf(BAKE_MAX_MISSES_PER_LANG));
        pb.directory(REPO_ROOT.toFile());
        pb.redirectErrorStream(false);
        pb.environment().put("HF_HUB_OFFLINE", "1");
        pb.environment().put("TRANSFORMERS_OFFLINE", "1");

        Process p = pb.start();
        String stdout = new String(p.getInputStream().readAllBytes());
        String stderr = new String(p.getErrorStream().readAllBytes());
        boolean exited = p.waitFor(180, TimeUnit.SECONDS);
        assertThat(exited).as("probe must finish within 180s").isTrue();
        assertThat(p.exitValue())
            .as("probe exit code (stdout=%s, stderr=%s)", stdout, stderr)
            .isEqualTo(0);

        System.out.println("=== probe stdout ===\n" + stdout);
        System.out.println("=== probe stderr ===\n" + stderr);

        // Probe emits a single JSON line on stdout. Parse it.
        var mapper = new ObjectMapper();
        var json = mapper.readTree(stdout.trim());

        assertThat(json.has("overrouting_probe_passes"))
            .as("probe output must include overrouting_probe_passes key")
            .isTrue();
        assertThat(json.get("overrouting_probe_passes").asBoolean())
            .as("bake thresholds (max-misses=%d + per-lang=%d) must hold against "
                + "the current production classifier — if this fails, either the "
                + "bake produced a much worse classifier than usual, OR the "
                + "thresholds drifted between recipe + RecipeBakeMain",
                BAKE_MAX_MISSES, BAKE_MAX_MISSES_PER_LANG)
            .isTrue();

        assertThat(json.get("anchors_tested").asInt())
            .as("anchor count must match the multilingual JSONL (30 EN + 30 ES + 30 JA)")
            .isEqualTo(EXPECTED_ANCHORS);

        assertThat(json.get("max_misses").asInt())
            .as("max_misses must round-trip the bake threshold")
            .isEqualTo(BAKE_MAX_MISSES);

        // per_lang summary present + covers all 3 languages.
        assertThat(json.has("per_lang"))
            .as("per_lang summary key must be present for diagnostic visibility")
            .isTrue();
        var perLang = json.get("per_lang");
        for (String lang : List.of("en", "es", "ja")) {
            assertThat(perLang.has(lang))
                .as("per_lang must include '%s' summary", lang)
                .isTrue();
            assertThat(perLang.get(lang).get("total").asInt())
                .as("each language must have 30 anchors", lang)
                .isEqualTo(30);
        }
    }

    private static Path locateRepoRoot() {
        // Walk up from cwd until we find settings.gradle.kts at the repo root.
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("settings.gradle.kts"))) return p;
        }
        return cwd;
    }
}
