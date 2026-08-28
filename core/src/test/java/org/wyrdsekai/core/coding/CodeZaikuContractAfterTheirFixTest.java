package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Our side of CodeZaiku commit 01de82d2, which fixed the three defects we reported.
 *
 * <ul>
 *   <li>Exit code now derives from status: 0 success/untested, <b>2 incomplete</b> (turn
 *       budget exhausted, files[] still real work), 1 failed, 143 killed. Anything of ours
 *       treating non-zero as failure would route "ran out of room" as "went wrong".</li>
 *   <li>{@code --mode artifact}: one deliverable, no project. Measured by them on our
 *       wiki_briefing task: default 23 turns / 4,324 files; artifact 6 / 1.</li>
 *   <li>files[] can carry a whole node_modules tree when a run invokes a package manager;
 *       whether CodeZaiku filters is a contract decision they have not made, so we cap.</li>
 * </ul>
 */
class CodeZaikuContractAfterTheirFixTest {

    private static String src(String rel) throws Exception {
        var fromCore = Path.of("..", rel);
        return Files.readString(Files.exists(fromCore) ? fromCore : Path.of(rel));
    }

    @Test
    @DisplayName("every run — shell-exec included — asks CodeZaiku for artifact mode")
    void artifactModeIsOnTheArgv() throws Exception {
        var s = src("core/src/main/java/org/wyrdsekai/core/coding/CodeZaikuBackend.java");
        var mode = s.indexOf("args.add(\"--mode\");\n        args.add(\"artifact\");");
        var text = s.indexOf("args.add(\"--text\");");
        assertThat(mode).isGreaterThan(0);
        // AFTER --text: the argv contract puts the task at index 3 and two existing tests
        // pin it. UNCONDITIONAL: the "a shell task is not an artifact" gate was retired
        // on the CodeZaiku team's own bake-path measurement (2026-08-24) — default mode
        // did the asked work then wandered 36 turns improving the repo at 95% window
        // use; artifact mode was 4 turns at 42%. One command, one file: one deliverable.
        assertThat(mode).isGreaterThan(text);
        assertThat(s.substring(Math.max(0, mode - 900), mode))
            .doesNotContain("if (!shellExec)");
    }

    @Test
    @DisplayName("exit 2 with the file on disk is incomplete work, not failure")
    void exitTwoIsRanOutOfRoom() throws Exception {
        var s = src("core/src/main/java/org/wyrdsekai/core/coding/CodeZaikuBackend.java");
        assertThat(s).contains("var incomplete = result.exitCode() == 2");
        assertThat(s).contains("if (incomplete && produced) {");
    }

    @Test
    @DisplayName("dependency trees never count as deliverables")
    void dependencyPathsAreFiltered() {
        assertThat(CodeZaikuBackend.isDependencyPath("node_modules/lodash/index.js")).isTrue();
        assertThat(CodeZaikuBackend.isDependencyPath("src/.venv/lib/x.py")).isTrue();
        assertThat(CodeZaikuBackend.isDependencyPath("build/classes/A.class")).isTrue();
        assertThat(CodeZaikuBackend.isDependencyPath("wiki_briefing.js")).isFalse();
        assertThat(CodeZaikuBackend.isDependencyPath("src/tool.js")).isFalse();
        assertThat(CodeZaikuBackend.FILES_CAP).isGreaterThan(0);
    }

    @Test
    @DisplayName("ACP treats an unfinished or cancelled prompt as work, not failure")
    void acpIncompleteIsNotFailed() throws Exception {
        var s = src("core/src/main/java/org/wyrdsekai/core/coding/AcpBackend.java");
        assertThat(s).contains("case \"max_turn_requests\", \"max_tokens\", \"incomplete\", \"cancelled\" ->");
        assertThat(s).contains("case \"refusal\" -> TaskStatus.FAILED;");
    }
}
