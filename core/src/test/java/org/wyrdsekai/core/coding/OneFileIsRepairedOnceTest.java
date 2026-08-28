package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run repairs a file once, however many routes find it.
 *
 * <h2>What went wrong</h2>
 * {@code repairRun} repairs the paths the backend reports, then scans the working
 * directory — which finds the same files again. On staging 2026-08-22 a single item went
 * through two identical rounds and "repair SUCCEEDED after 1 round(s)" printed twice.
 * Each round is a model call, and the second one re-opens a file the first just settled
 * while the revert guard only compares within a round.
 */
class OneFileIsRepairedOnceTest {

    @Test
    @DisplayName("a file reported by the backend is not repaired again by the directory scan")
    void reportedAndScannedIsStillOneRepair(@TempDir Path workspace) throws Exception {
        var script = workspace.resolve("briefing.js");
        // Missing embodiment and commands, so every round has something to complain about
        // — without a defect the loop would return before we could count anything.
        Files.writeString(script, """
            exports.manifest = { name: "briefing", version: "1.0.0",
              description: "x", author: "did:wyrd:test", capabilities: [] };
            function invoke(params) { return { ok: true }; }
            """);

        var prompts = new ArrayList<String>();
        ItemContractRepair.Reprompt reprompt = prompt -> {
            prompts.add(prompt);
            return false;   // the backend declines; the file stays as it is
        };

        ItemContractRepair.repairRun(
            List.of(new SourceArtifact(UUID.randomUUID(), "goose", "task-1",
                workspace.toString(), List.of(script.toString()), null,
                Instant.now(), Map.of())),
            workspace, "task-1", Instant.now().minusSeconds(60), reprompt, "brief me on a topic");

        assertThat(prompts)
            .as("the backend was asked to fix the same file twice")
            .hasSize(1);
    }
}
