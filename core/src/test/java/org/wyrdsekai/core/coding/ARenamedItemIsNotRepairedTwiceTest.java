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
 * When the backend rewrites an item under a corrected name, the abandoned file is not
 * worth a model round.
 *
 * <h2>What went wrong</h2>
 * Told that {@code invitation-scroll} breaks the name rule, goose wrote a second file,
 * {@code invitation_scroll.js}, and left the first untouched. Both repair rounds were then
 * spent re-reading the file nobody would ever load —
 * {@code repair exhausted for invitation-scroll.js after 2 rounds} — while the working
 * item sat beside it, clean. Two rounds, two model calls, no change possible.
 */
class ARenamedItemIsNotRepairedTwiceTest {

    private static String item(String name) {
        return """
            exports.manifest = {
              name: "%s",
              version: "1.0.0",
              description: "A scroll that invites.",
              author: "did:wyrd:test",
              capabilities: [],
              embodiment: { silent: false, emits: ["body_language"],
                            descriptor_template: "{actor} unrolls it" },
              commands: [{ label: "Read the scroll", args: "" }]
            };
            function invoke(params) { return { ok: true, summary: "read" }; }
            """.formatted(name);
    }

    @Test
    @DisplayName("the abandoned file costs no repair rounds when its corrected twin is clean")
    void supersededFileIsSkipped(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("invitation-scroll.js"), item("invitation-scroll"));
        Files.writeString(ws.resolve("invitation_scroll.js"), item("invitation_scroll"));

        var prompts = new ArrayList<String>();
        ItemContractRepair.repairRun(
            List.of(new SourceArtifact(UUID.randomUUID(), "goose", "task-1", ws.toString(),
                List.of(ws.resolve("invitation-scroll.js").toString()), null,
                Instant.now(), Map.of())),
            ws, "task-1", Instant.now().minusSeconds(60),
            prompt -> { prompts.add(prompt); return false; }, "make me an invitation scroll");

        assertThat(prompts)
            .as("a round was spent on the file the backend had already replaced")
            .isEmpty();
    }

    @Test
    @DisplayName("with no corrected twin, the broken file is still repaired")
    void aLoneBrokenFileIsStillRepaired(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("invitation-scroll.js"), item("invitation-scroll"));

        var prompts = new ArrayList<String>();
        ItemContractRepair.repairRun(
            List.of(new SourceArtifact(UUID.randomUUID(), "goose", "task-2", ws.toString(),
                List.of(ws.resolve("invitation-scroll.js").toString()), null,
                Instant.now(), Map.of())),
            ws, "task-2", Instant.now().minusSeconds(60),
            prompt -> { prompts.add(prompt); return false; }, "make me an invitation scroll");

        assertThat(prompts).hasSize(1);
        assertThat(prompts.get(0))
            .as("the backend is told to edit the file, not to write a corrected copy")
            .contains("IN PLACE")
            .contains("Do not create a new file");
    }
}
