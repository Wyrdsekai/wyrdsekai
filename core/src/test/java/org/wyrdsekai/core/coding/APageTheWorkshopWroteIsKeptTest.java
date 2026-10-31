package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A task that wrote a page and no program is a page for her notebook, not "no files". The
 * household node dropped every such page for four days because the "no files" exit ran first:
 * a markdown page never has a script to find (2026-10-31).
 */
class APageTheWorkshopWroteIsKeptTest {

    @TempDir Path dir;

    @AfterEach
    void tearDown() { ItemContractRepair.resetOutcomesForTests(); }

    private SourceArtifact artifact(String taskId, Path ws, List<String> files) {
        return new SourceArtifact(UUID.randomUUID(), "codezaiku", taskId, ws.toString(), files, null, Instant.now(), Map.of());
    }

    @Test
    void a_page_alone_is_kept_and_nothing_is_still_nothing() throws Exception {
        var ws = Files.createDirectories(dir.resolve("task-page"));
        Files.writeString(ws.resolve("the_unfinished_letter.md"), "# An unfinished letter\n\nNot addressed to anyone yet. What stays is the waiting.");
        var page = artifact("task-page", ws, List.of("the_unfinished_letter.md"));
        assertThat(CodingTaskItemBridge.placeable(page)).as("no script to find: the old exit").isFalse();
        assertThat(CodingTaskItemBridge.intake(page)).isEqualTo(CodingTaskItemBridge.Intake.PAGE);
        var kept = ItemContractRepair.proseOf("task-page");
        assertThat(kept).isPresent();
        assertThat(kept.get().title()).isEqualTo("An unfinished letter");
        assertThat(kept.get().text()).contains("What stays is the waiting.");

        var empty = Files.createDirectories(dir.resolve("task-empty"));
        assertThat(CodingTaskItemBridge.intake(artifact("task-empty", empty, List.of()))).isEqualTo(CodingTaskItemBridge.Intake.NOTHING);

        var tool = Files.createDirectories(dir.resolve("task-tool"));
        Files.writeString(tool.resolve("lantern.js"), "exports.manifest = { name: \"lantern\" }; function invoke(p) { return { ok: true }; }");
        Files.writeString(tool.resolve("README.md"), "# Lantern\n\nHow to use it.");
        assertThat(CodingTaskItemBridge.intake(artifact("task-tool", tool, List.of("lantern.js", "README.md"))))
            .as("a program with its readme is a build").isEqualTo(CodingTaskItemBridge.Intake.BUILD);
        assertThat(ItemContractRepair.proseOf("task-tool")).isEmpty();
    }
}
