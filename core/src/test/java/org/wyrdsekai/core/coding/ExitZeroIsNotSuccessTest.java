package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * goose exits 0 and says "completed" when it could not reach a model. That is not success.
 *
 * <p>Both fixtures are goose's real output from the staging node on 2026-08-21:
 * {@code never_reached_model.json} is the 7-second run against a dead port —
 * "Network error: Could not connect to localhost:8200", no tokens, no files — which the
 * backend reported as SUCCEEDED; {@code wrote_a_file.json} is the same goose against the
 * live 4B, which wrote the file in 3 seconds.
 */
class ExitZeroIsNotSuccessTest {

    private static String fixture(String name) throws Exception {
        try (var in = ExitZeroIsNotSuccessTest.class.getResourceAsStream("/goose/" + name)) {
            if (in == null) throw new IllegalStateException("fixture missing: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static SourceArtifact artifact(List<String> files) {
        return new SourceArtifact(UUID.randomUUID(), GooseBackend.NAME, "t", "/tmp/ws",
            files, null, Instant.now(), Map.of());
    }

    @Test
    void a_run_that_never_reached_a_model_is_named_as_such() throws Exception {
        var why = GooseBackend.neverReachedModel(
            fixture("never_reached_model.json"), List.of(artifact(List.of())));
        assertThat(why).isPresent();
        assertThat(why.get()).contains("never reached a model");
        assertThat(why.get())
            .as("goose's own words reach the person, so they know WHICH port was dead")
            .contains("Could not connect to localhost:8200");
    }

    @Test
    void a_run_that_wrote_a_file_is_fine() throws Exception {
        assertThat(GooseBackend.neverReachedModel(
            fixture("wrote_a_file.json"), List.of(artifact(List.of("hello.js"))))).isEmpty();
    }

    /** Tokens were spent but nothing was written: a real answer, not a dead run. */
    @Test
    void tokens_spent_with_no_files_is_not_a_dead_run() throws Exception {
        assertThat(GooseBackend.neverReachedModel(
            fixture("wrote_a_file.json"), List.of(artifact(List.of())))).isEmpty();
    }

    @Test
    void unparseable_output_is_not_condemned() {
        assertThat(GooseBackend.neverReachedModel("not json at all", List.of())).isEmpty();
        assertThat(GooseBackend.neverReachedModel(null, List.of())).isEmpty();
    }

    /** And the bridge places nothing for nothing. */
    @Test
    void a_zero_file_artifact_is_not_placeable() {
        assertThat(CodingTaskItemBridge.placeable(artifact(List.of()))).isFalse();
        assertThat(CodingTaskItemBridge.placeable(artifact(List.of("tool.js")))).isTrue();
        assertThat(CodingTaskItemBridge.placeable(null)).isFalse();
    }
}
