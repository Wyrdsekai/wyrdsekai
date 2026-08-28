package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A backend reached through the CLI contract reports in the terminal shape, and every
 * adapter must understand it.
 *
 * <h2>What went wrong</h2>
 * 2026-08-23 09:06, first CodeZaiku run on staging: two tasks completed, real files on
 * disk, {@code files=1} in the broadcast — and {@code CodeZaikuEventAdapter} returned
 * null, because it only knew the board-protocol shape ({@code board_completed}). The
 * bridge dropped both without a log line. Goose, OpenCode and Codex each carried their own
 * copy of the terminal-shape parser; CodeZaiku had none.
 */
class EveryBackendReportsInOneShapeTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    @DisplayName("the shared parser reads what CodingTaskBroadcast emits")
    void theTerminalShapeParses() throws Exception {
        var data = M.readTree("""
            {"event":"task_completed","taskId":"t-1","status":"succeeded",
             "workspace":"/var/lib/wyrdsekai/coding-workspaces/t-1",
             "files":["library_fairy_tale.js"],"model":"9b"}""");
        var art = TerminalTaskArtifact.from(data, "codezaiku").orElseThrow();
        assertThat(art.files()).containsExactly("library_fairy_tale.js");
        assertThat(art.workspacePath()).endsWith("/t-1");
        assertThat(art.backendMetadata()).containsEntry("source", "codezaiku");
    }

    @Test
    @DisplayName("the CodeZaiku adapter accepts the terminal shape as well as the board shape")
    void codezaikuAdapterFallsBackToTheTerminalShape() throws Exception {
        var src = java.nio.file.Files.readString(java.nio.file.Path.of(
            java.nio.file.Files.exists(java.nio.file.Path.of("core/src/main/java"))
                ? "core/src/main/java/org/wyrdsekai/core/coding/CodeZaikuEventAdapter.java"
                : "../core/src/main/java/org/wyrdsekai/core/coding/CodeZaikuEventAdapter.java"));
        assertThat(src).contains("TerminalTaskArtifact.from(data, CodeZaikuBackend.NAME)");
    }

    @Test
    @DisplayName("a non-terminal event is not an artifact")
    void otherEventsAreNot() throws Exception {
        assertThat(TerminalTaskArtifact.from(M.readTree("{\"event\":\"task_started\"}"), "x"))
            .isEmpty();
        assertThat(TerminalTaskArtifact.from(null, "x")).isEmpty();
    }
}
