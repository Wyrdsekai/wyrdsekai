package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * She should name the backend that did the work, not the one that was default when the
 * sentence was written.
 *
 * <h2>Why this matters now</h2>
 * The household is moving from goose to CodeZaiku. Anything that hardcodes a backend name
 * becomes a lie on the day of the switch, and the worst offenders are the strings the
 * MODEL reads — those sit in her context every turn, so she learns to say "goose"
 * regardless of what is actually registered. She would be telling the steward something
 * untrue in her own voice, which is a different kind of wrong from a stale comment.
 *
 * <p>What is already right and must stay right: {@code dispatch.spoken.plan} interpolates
 * {@code chosen.name()}, so what she says out loud is resolved at dispatch time; and each
 * backend's own {@code summarise()} names itself, which is correct by construction —
 * GooseBackend says "Goose", CodeZaikuBackend says "CodeZaiku".
 */
class SheNamesTheBackendThatActuallyRanTest {

    /**
     * Text she READS while deciding, or SPEAKS from a template, must not name a backend.
     *
     * <p>Scoped to those two files on purpose. A backend naming ITSELF in its own summary
     * is right, and comments that explain history are not something she reads.
     */
    private static final List<String> MODEL_FACING = List.of(
        "core/src/main/java/org/wyrdsekai/core/agent/ActionToolBuilder.java",
        "core/src/main/java/org/wyrdsekai/core/item/ToolItemStarterKit.java");

    /**
     * Names distinctive enough to mean the backend and nothing else.
     *
     * <p>{@code cline} and {@code continue} are deliberately absent: the first is a
     * substring of {@code decline_with_reason} and the second is an ordinary English
     * word, so including them made this guard fire on four innocent lines the first time
     * it ran. A guard that cries wolf gets deleted, which is worse than not having it.
     */
    private static final List<String> BACKEND_NAMES = List.of(
        "goose", "codezaiku", "opencode", "openhands", "devin");

    @Test
    void nothing_she_reads_while_choosing_names_a_specific_backend() throws Exception {
        var offenders = new ArrayList<String>();
        for (var rel : MODEL_FACING) {
            var path = find(rel);
            var inString = false;
            for (var line : Files.readAllLines(path)) {
                var code = line.strip();
                if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
                    continue;   // history, not something she reads
                }
                if (!code.contains("\"")) continue;
                var lower = code.toLowerCase(Locale.ROOT);
                for (var name : BACKEND_NAMES) {
                    // Only inside a quoted string: a class reference or config key that
                    // names a backend is fine — she never reads those.
                    if (quotedContains(lower, name)) {
                        offenders.add(path.getFileName() + ": " + code);
                        break;
                    }
                }
            }
        }
        assertThat(offenders)
            .as("she reads this text every turn; naming a backend here teaches her to say "
                + "it after the household has switched to a different one. What she says "
                + "out loud comes from chosen.name() at dispatch time.")
            .isEmpty();
    }

    /** The spoken template must interpolate the chosen backend rather than bake one in. */
    @Test
    void what_she_says_out_loud_is_interpolated_not_baked_in() throws Exception {
        var en = Files.readString(find("scripts/i18n/en.json"));
        assertThat(en)
            .as("dispatch.spoken.plan must carry a placeholder for the backend")
            .contains("I''ll hand it to {1}");
        var offenders = new ArrayList<String>();
        for (var line : en.split("\n")) {
            if (!line.contains("\"dispatch.")) continue;
            var lower = line.toLowerCase(Locale.ROOT);
            for (var name : BACKEND_NAMES) {
                if (lower.contains(name)) offenders.add(line.strip());
            }
        }
        assertThat(offenders)
            .as("a dispatch line that names a backend becomes untrue on switch day")
            .isEmpty();
    }

    /** True only when the name appears between quotes on the line. */
    private static boolean quotedContains(String lowerLine, String needle) {
        int from = 0;
        while (true) {
            int open = lowerLine.indexOf('"', from);
            if (open < 0) return false;
            int close = lowerLine.indexOf('"', open + 1);
            if (close < 0) return false;
            if (lowerLine.substring(open + 1, close).contains(needle)) return true;
            from = close + 1;
        }
    }

    private static Path find(String repoRelative) {
        for (var candidate : List.of(repoRelative, "../" + repoRelative,
                repoRelative.replaceFirst("^core/", ""))) {
            var p = Path.of(candidate);
            if (Files.isRegularFile(p)) return p;
        }
        throw new IllegalStateException("not found from " + System.getProperty("user.dir")
            + ": " + repoRelative + " — this guard must never silently pass");
    }
}
