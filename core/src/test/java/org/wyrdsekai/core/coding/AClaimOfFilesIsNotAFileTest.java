package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.DisplayName;
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
 * A claim of files is not a file.
 *
 * <h2>What went wrong</h2>
 * Live on staging 2026-08-22 goose returned {@code SUCCEEDED} naming {@code media_sorter.js}
 * — and its workspace directory was empty. {@code placeable} read the artifact's file
 * LIST, which was non-empty, so the bridge logged "Placed 1 goose item(s)" and the steward
 * would have picked up an object with nothing inside it. The list says what the backend
 * meant to write. The disk says what it wrote.
 */
class AClaimOfFilesIsNotAFileTest {

    private static SourceArtifact claiming(Path workspace, String... files) {
        return new SourceArtifact(UUID.randomUUID(), "goose", "task-1",
            workspace == null ? null : workspace.toString(),
            List.of(files), null, Instant.now(), Map.of());
    }

    @Test
    @DisplayName("a run that named a file it never wrote places nothing")
    void anEmptyWorkspaceIsNotPlaceable(@TempDir Path workspace) {
        assertThat(CodingTaskItemBridge.placeable(claiming(workspace, "media_sorter.js")))
            .as("the workspace is empty — nothing was made")
            .isFalse();
    }

    @Test
    @DisplayName("a run that actually wrote the file is placed")
    void aRealFileIsPlaceable(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("media_sorter.js"), "exports.manifest = {};");
        assertThat(CodingTaskItemBridge.placeable(claiming(workspace, "media_sorter.js")))
            .isTrue();
    }

    @Test
    @DisplayName("a file written somewhere else still counts")
    void anAbsolutePathOutsideTheWorkspaceCounts(@TempDir Path tmp) throws Exception {
        var workspace = Files.createDirectories(tmp.resolve("ws"));
        var elsewhere = Files.writeString(tmp.resolve("media_sorter.js"), "exports.manifest = {};");
        // goose has written outside the workspace it was handed before (2026-08-20) —
        // that is a real item, and refusing it would throw away work that exists.
        assertThat(CodingTaskItemBridge.placeable(claiming(workspace, elsewhere.toString())))
            .isTrue();
    }

    @Test
    @DisplayName("with nowhere to verify against, work is trusted rather than dropped")
    void anUnverifiableClaimIsStillPlaced() {
        assertThat(CodingTaskItemBridge.placeable(claiming(null, "media_sorter.js"))).isTrue();
    }

    @Test
    @DisplayName("a run that claimed nothing is still nothing")
    void noClaimIsNotPlaceable(@TempDir Path workspace) {
        assertThat(CodingTaskItemBridge.placeable(claiming(workspace))).isFalse();
    }
}
