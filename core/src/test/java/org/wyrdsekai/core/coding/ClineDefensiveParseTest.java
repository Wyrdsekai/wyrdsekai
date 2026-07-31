package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2d / 2026-05-04 reconciliation — verifies the Cline event parser
 * handles malformed input gracefully.
 *
 * <p>Pre-2026-05 the adapter wrapped a defensive {@code MAX_PARSE_ERRORS=5}
 * threshold around upstream gRPC-instability claims. Cline 2.18+ is
 * npm-distributed with a flat {@code {type, text, ts, ...}} event schema;
 * the gRPC-instability defence no longer applies, and the threshold
 * was dropped. Malformed JSON lines are tolerated as no-ops; file
 * paths are extracted from {@code text} via regex on common patterns.</p>
 */
class ClineDefensiveParseTest {

    @Test void malformed_lines_mixed_with_valid_lines_succeed() throws Exception {
        // 2026-05-04 schema: {type: "say", text: "...", ts: ...}.
        // File paths are mentioned inside `text` strings.
        var stdout = String.join("\n",
            "this is definitely not JSON",
            "{\"type\": \"say\", \"text\": \"Editing src/foo.java\", \"ts\": 100}",
            "more garbage",
            "{\"type\": \"say\", \"text\": \"Created src/bar.java\", \"ts\": 200}",
            "{\"type\": \"say\", \"text\": \"Modified src/baz.java\", \"ts\": 300}"
        );
        var b = new ClineBackend(enabledDefaults(), oauthResolver(),
            stub(stdout, "", 0, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);

        // Task SUCCEEDS — malformed lines are tolerated.
        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);
        var artifacts = b.artifactsFor(result.taskId().toString()).toList();
        var src = (SourceArtifact) artifacts.get(0);
        assertThat(src.files()).contains("src/foo.java", "src/bar.java", "src/baz.java");
    }

    @Test void all_malformed_input_still_succeeds_with_empty_files() throws Exception {
        // No good JSON lines, no recognisable file-path patterns. The
        // task SUCCEEDS (subprocess exit was 0) and the artifact has an
        // empty file list. The pre-2026-05 adapter would have FAILED
        // the task with "upstream may have changed schema"; the new
        // contract treats opaque output as "we just don't see files."
        var stdout = String.join("\n",
            "garble", "garble", "garble", "garble", "garble", "garble",
            "totally random output");
        var b = new ClineBackend(enabledDefaults(), oauthResolver(),
            stub(stdout, "", 0, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);
        var artifacts = b.artifactsFor(result.taskId().toString()).toList();
        var src = (SourceArtifact) artifacts.get(0);
        assertThat(src.files()).isEmpty();
    }

    // ─── extractFilesFromText regex coverage ─────────────────────

    @Test void extracts_paths_from_edit_verb_prose() {
        var out = new ArrayList<String>();
        ClineBackend.extractFilesFromText("Editing src/foo.java to fix bug", out);
        ClineBackend.extractFilesFromText("Created tests/bar_test.py", out);
        ClineBackend.extractFilesFromText("Wrote build/output.log", out);
        assertThat(out).contains("src/foo.java", "tests/bar_test.py", "build/output.log");
    }

    @Test void extracts_path_line_prefix_pattern() {
        var out = new ArrayList<String>();
        ClineBackend.extractFilesFromText("src/foo.java:42 something\nsrc/bar.java:1: error", out);
        assertThat(out).contains("src/foo.java", "src/bar.java");
    }

    @Test void extracts_paths_from_legacy_structured_fields_for_back_compat() {
        // A future / older schema with explicit `file`/`files` fields
        // would still be honoured. Costs nothing to keep — the upstream
        // schema isn't fully nailed down.
        var out = new ArrayList<String>();
        try {
            var node = new ObjectMapper().readTree(
                "{\"file\": \"src/legacy.java\", \"files\": [\"src/x.java\", \"src/y.java\"]}");
            ClineBackend.extractFilesFromStructuredFields(node, out);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertThat(out).contains("src/legacy.java", "src/x.java", "src/y.java");
    }

    // ─── Helpers ──────────────────────────────────────────────────

    private static ClineRuntimeConfig enabledDefaults() {
        var d = ClineRuntimeConfig.defaults();
        return new ClineRuntimeConfig(true, d.executablePath(), d.provider(),
            d.yolo(), d.maxWallclock(), d.extraFlags());
    }

    private static AuthResolver oauthResolver() {
        return name -> new AuthMode.OAuthSession();
    }

    private static ClineBackend.ProcessRunner stub(
            String stdout, String stderr, int exitCode, boolean timedOut) {
        return (args, env, timeout) -> new ClineBackend.ProcessResult(
            exitCode, stdout, stderr, timedOut);
    }

    @SuppressWarnings("unused")
    private static Duration unused() { return Duration.ofMillis(1); }
    @SuppressWarnings("unused")
    private static List<String> unused2() { return List.of(); }
}
