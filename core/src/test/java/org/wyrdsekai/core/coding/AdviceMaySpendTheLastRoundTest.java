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
 * Advisory gaps may spend the last repair round — the revert-if-worse guard
 * made that safe, and NOT spending it shipped fabricators.
 *
 * <h2>What went wrong</h2>
 * The rule was "advice never spends the last round" (2026-08-21, before the
 * revert guard existed). Its cost surfaced 2026-08-24, twice in one evening:
 * any item whose round 1 fixed a CONTRACT problem exited with intent gaps
 * never evaluated once. That is how a "weather tool" that declares
 * {@code [web.search, web.fetch]}, calls nothing, and returns a HARDCODED
 * temperature-72 forecast reached a person's hands — invoke-smoke passes,
 * the contract passes, and the one check that would have objected was
 * skipped because the budget rule said the round was too precious.
 */
class AdviceMaySpendTheLastRoundTest {

    @Test
    @DisplayName("a contract fix in round 1 still gets intent advice in round 2")
    void contractFixThenAdvice(@TempDir Path workspace) throws Exception {
        var script = workspace.resolve("weather_lookup.js");
        // Round 1's defect: no embodiment/commands. The declared-but-uncalled
        // web capabilities are the round-2 advice this test is about.
        var broken = """
            exports.manifest = { name: "weather_lookup", version: "1.0.0",
              description: "x", author: "did:wyrd:test",
              capabilities: ["web.search", "web.fetch", "library.search"] };
            function invoke(params) {
              var hits = world.library.search(params.args, 3);
              return { ok: true, summary: "Current weather: 72F" };
            }
            """;
        var repaired = """
            exports.manifest = { name: "weather_lookup", version: "1.0.0",
              description: "x", author: "did:wyrd:test",
              capabilities: ["web.search", "web.fetch", "library.search"],
              embodiment: { silent: true, reason: "a lookup" },
              commands: [ { label: "Look up", args: "" } ] };
            function invoke(params) {
              var hits = world.library.search(params.args, 3);
              return { ok: true, summary: "Current weather: 72F" };
            }
            """;

        var prompts = new ArrayList<String>();
        ItemContractRepair.Reprompt reprompt = prompt -> {
            prompts.add(prompt);
            try {
                // Round 1 "fixes" the contract; any later round leaves the file be.
                if (prompts.size() == 1) Files.writeString(script, repaired);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return true;
        };

        Files.writeString(script, broken);
        ItemContractRepair.repairRun(
            List.of(new SourceArtifact(UUID.randomUUID(), "test", "task-w",
                workspace.toString(), List.of(script.toString()), null,
                Instant.now(), Map.of())),
            workspace, "task-w", Instant.now().minusSeconds(60), reprompt,
            "a tool that tells me the current weather by city and state");

        assertThat(prompts)
            .as("round 1 = contract, round 2 = the advice the old rule skipped")
            .hasSize(2);
        assertThat(prompts.get(1))
            .as("the last round carries the declared-but-never-called objection")
            .contains("never calls");
    }
}
