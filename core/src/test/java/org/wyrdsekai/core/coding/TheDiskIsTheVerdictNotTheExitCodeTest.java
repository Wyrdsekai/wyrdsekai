package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exit code is not the verdict; the disk is.
 *
 * <h2>What went wrong</h2>
 * Twice on 2026-08-23 CodeZaiku exited 1 with a valid, complete item on disk
 * ({@code wiki_briefing.js}, {@code venture_scout2.js}) — it had run out its turn budget
 * after writing the file — and {@code CodeZaikuBackend} returned FAILED before reading
 * its own result JSON, so the work was discarded unread. This is the mirror of the goose
 * lesson ({@code ExitZeroIsNotSuccessTest}): exit 0 with no file is not success, and exit
 * 1 with a real file is not failure. The file on disk is the evidence either way.
 *
 * <p>And the failure text quoted the HEAD of stderr — JDK native-access warnings — when
 * the reason was at the tail. The tail is what gets quoted now.
 */
class TheDiskIsTheVerdictNotTheExitCodeTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    @DisplayName("a result that names a file which exists counts as produced")
    void namedFileOnDiskIsProduced(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("venture_scout2.js"), "exports.manifest = {};");
        var parsed = M.readTree("{\"files\":[\"venture_scout2.js\"],\"filesComplete\":true}");
        assertThat(CodeZaikuBackend.namedFilesExist(ws, parsed)).isTrue();
    }

    @Test
    @DisplayName("a result that names a file which does not exist is not produced")
    void namedFileMissingIsNotProduced(@TempDir Path ws) throws Exception {
        var parsed = M.readTree("{\"files\":[\"ghost.js\"]}");
        assertThat(CodeZaikuBackend.namedFilesExist(ws, parsed)).isFalse();
        assertThat(CodeZaikuBackend.namedFilesExist(ws, M.readTree("{\"files\":[]}"))).isFalse();
    }

    @Test
    @DisplayName("the failure text quotes the end of stderr, not the JDK warnings at the top")
    void stderrTailIsQuoted() {
        var stderr = "WARNING: A restricted method in java.lang.foreign.Linker has been called\n"
            + "WARNING: Use --enable-native-access\n"
            + "10:01 INFO FamiliarLoop - turn 39/40\n"
            + "10:01 INFO FamiliarLoop - turn 40/40 (out_budget=4975)\n"
            + "10:01 ERROR FamiliarLoop - turn budget exhausted before task_done";
        var tail = CodeZaikuBackend.lastLines(stderr, 2);
        assertThat(tail).contains("turn budget exhausted");
        assertThat(tail).doesNotContain("restricted method");
    }

    /**
     * The repair path had the same defect as the task path: a repair run that fixed the
     * file and exited 1 on the turn budget was reported "did not complete", and the loop
     * shipped a corrected file believing it uncorrected (venture_scout3, trip_compass2,
     * storm_cellar — 2026-08-23). The reprompt now reports RAN; the loop's own re-read of
     * the file is the verdict.
     */
    @Test
    @DisplayName("a repair run that ran is reported as ran, whatever its exit code")
    void aRepairThatRanIsNotCondemnedByItsExitCode() throws Exception {
        var src = java.nio.file.Files.readString(java.nio.file.Path.of(
            java.nio.file.Files.exists(java.nio.file.Path.of("core/src/main/java"))
                ? "core/src/main/java/org/wyrdsekai/core/coding/CodeZaikuBackend.java"
                : "../core/src/main/java/org/wyrdsekai/core/coding/CodeZaikuBackend.java"));
        var repair = src.indexOf("ItemContractRepair.rerunWithPrompt(repairArgs -> {");
        var verdict = src.indexOf("return !r.timedOut();", repair);
        var oldVerdict = src.indexOf("return !r.timedOut() && r.exitCode() == 0;", repair);
        assertThat(verdict).as("the repair reprompt reports ran/not-ran").isGreaterThan(repair);
        assertThat(oldVerdict == -1 || oldVerdict > src.indexOf("healthCheck()"))
            .as("no exit-code verdict remains on the repair path (health probe may keep its own)")
            .isTrue();
    }

    @Test
    @DisplayName("the repair prompt scopes the work to one file")
    void theRepairPromptScopesToOneFile() {
        var prompt = ItemContractRepair.buildPrompt("x.js", java.util.List.of("missing embodiment"));
        assertThat(prompt).contains("ONE file and nothing else");
        assertThat(prompt).contains("no tests");
    }
}
