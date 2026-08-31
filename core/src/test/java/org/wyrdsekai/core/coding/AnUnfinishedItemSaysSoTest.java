package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.model.RoomObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Honest placement. Until 2026-08-27 an item that failed registration was
 * placed wearing its own manifest description — live 2026-08-25 a person's
 * reading tool crashed on first use while presenting as "Draft a reading
 * list from saved passages…". The only honesty was a log line no one in the
 * world could see. A thing that does not work must say so where it stands.
 */
class AnUnfinishedItemSaysSoTest {

    private static SourceArtifact source(Path workspace, String fileName) {
        return new SourceArtifact(
            UUID.randomUUID(),
            "goose",
            "task-" + UUID.randomUUID().toString().substring(0, 8),
            workspace.toString(),
            List.of(fileName),
            null,
            Instant.now(),
            Map.of());
    }

    private static final String BROKEN = """
        exports.manifest = {
          name: "reading_draft", version: "1.0.0",
          description: "Draft a reading list from saved passages.",
          author: "did:wyrd:backend",
          capabilities: [],
          embodiment: { silent: true, reason: "a drafting aid" },
          commands: [ { label: "Draft a list", args: "" } ]
        };
        function invoke(params) {
          var notebook = params.notebook;
          return { ok: true, message: notebook.search("x") };
        }
        """;

    @Test
    void a_failed_registration_reports_why(@TempDir Path workspace) throws Exception {
        var fileName = "reading_draft.js";
        Files.writeString(workspace.resolve(fileName), BROKEN);
        var roomObj = new RoomObject("codex-1", "reading_draft",
            "Draft a reading list from saved passages.", true);

        var outcome = CodingTaskItemBridge.tryRegisterScriptedItem(
            roomObj, source(workspace, fileName));

        assertThat(outcome.registered()).isFalse();
        assertThat(outcome.knownBroken()).isTrue();
        assertThat(outcome.problems()).isNotEmpty();
    }

    @Test
    void an_unfinished_item_wears_the_truth_not_its_manifest() {
        var pretty = new RoomObject("codex-2", "reading_draft",
            "Draft a reading list from saved passages.", true, true);
        var honest = CodingTaskItemBridge.markUnfinished(pretty,
            List.of("calling invoke() once, in the real sandbox, failed: TypeError"));

        assertThat(honest.description()).startsWith("UNFINISHED");
        assertThat(honest.description())
            .as("the maker's intent is kept, so the thing stays recognisable")
            .contains("Draft a reading list from saved passages.");
        assertThat(honest.description()).contains("TypeError");
        assertThat(honest.description()).contains("workshop");
        assertThat(honest.state()).containsEntry("needs_repair", "true");
        assertThat(honest.id()).isEqualTo(pretty.id());
        assertThat(honest.name())
            .as("the name survives — addressability is how repair happens")
            .isEqualTo(pretty.name());
    }

    @Test
    void a_workspace_with_no_js_is_not_called_broken(@TempDir Path workspace) {
        var roomObj = new RoomObject("codex-3", "artifact", "A build artifact.", true);
        var outcome = CodingTaskItemBridge.tryRegisterScriptedItem(
            roomObj, source(workspace, "notes.txt"));

        assertThat(outcome.registered()).isFalse();
        assertThat(outcome.knownBroken())
            .as("the legacy router path may still serve it — unknown is not broken")
            .isFalse();
    }
}
