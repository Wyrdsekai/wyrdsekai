package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every task and every mend works in a folder of its own, and the coding helper keeps an
 * environment for each; nothing ever cleared them, and a household node held 738 folders and
 * 62 environments, 2.3 GB, most over a month old (2026-11-12). Finished folders untouched for
 * the kept days go, and with them the helper's environments whose folders are gone. A folder
 * still in use, an environment whose folder is still here, and anything not the house's own
 * scratch are left.
 */
class TheWorkshopClearsWhatItLeavesTest {

    @TempDir Path tmp;

    private static Path folder(Path parent, String name, Instant touched) throws Exception {
        var dir = Files.createDirectories(parent.resolve(name));
        Files.writeString(dir.resolve("absent_letter.js"), "exports.manifest = {};");
        age(dir, touched);
        return dir;
    }

    private static void age(Path dir, Instant when) throws Exception {
        try (Stream<Path> s = Files.walk(dir)) {
            for (var p : s.toList()) Files.setLastModifiedTime(p, FileTime.from(when));
        }
    }

    private static Path env(Path envs, String name, Path project, Instant touched) throws Exception {
        var dir = Files.createDirectories(envs.resolve(name));
        Files.writeString(dir.resolve("project.txt"), project.toString() + "\n");
        Files.createDirectories(dir.resolve("py"));
        age(dir, touched);
        return dir;
    }

    @Test
    void finished_folders_and_their_helper_environments_go_and_the_rest_stays() throws Exception {
        var now = Instant.parse("2026-11-12T15:00:00Z");
        var month = now.minus(Duration.ofDays(31));
        var root = Files.createDirectories(tmp.resolve("coding-workspaces"));
        var envs = Files.createDirectories(tmp.resolve("home/.codezaiku/envs"));
        var oldRepair = folder(root, "repair-absent_letter.js-20261012-131456", month);
        var oldCheckpoints = folder(root, "repair-absent_letter.js-20261012-131456.cp-checkpoints", month);
        var oldTask = folder(root, "ffe22310-a10c-4384-b7fb-8c67def37131", month);
        var today = folder(root, "repair-carry_letter.js-20261112-114950", now.minus(Duration.ofHours(2)));
        var oldButTouchedInside = folder(root, "fe8aa3fc-df28-4dc7-9c05-8b2ef132145c", month);
        Files.setLastModifiedTime(oldButTouchedInside.resolve("absent_letter.js"), FileTime.from(now.minus(Duration.ofDays(1))));

        var envOfOld = env(envs, "repair-absent_letter.js-20261012-131456-93a12e2f6388", oldRepair, month);
        var envOfToday = env(envs, "repair-carry_letter.js-20261112-114950-9c69f01af323", today, month);
        var envOfAnotherPlace = env(envs, "ws-0481d27523f6", tmp.resolve("someone-elses/ws"), month);
        var envOfGoneButFresh = env(envs, "repair-x.js-20261111-000000-aaaa", root.resolve("repair-x.js-20261111-000000"), now.minus(Duration.ofDays(1)));

        var s = WorkshopSweep.sweep(root, envs, Duration.ofDays(7), now);

        assertThat(oldRepair).doesNotExist();
        assertThat(oldCheckpoints).doesNotExist();
        assertThat(oldTask).doesNotExist();
        assertThat(today).as("a folder of today").exists();
        assertThat(oldButTouchedInside).as("something in it changed yesterday").exists();
        assertThat(envOfOld).as("its folder is gone").doesNotExist();
        assertThat(envOfToday).as("its folder is still here").exists();
        assertThat(envOfAnotherPlace).as("not the house's scratch").exists();
        assertThat(envOfGoneButFresh).as("touched yesterday").exists();
        assertThat(s.folders()).isEqualTo(3);
        assertThat(s.environments()).isEqualTo(1);
        assertThat(s.bytes()).isPositive();
    }

    @Test
    void nothing_to_clear_is_nothing_cleared() {
        assertThat(WorkshopSweep.sweep(tmp.resolve("absent"), tmp.resolve("absent-envs"), Duration.ofDays(7), Instant.now()))
            .isEqualTo(new WorkshopSweep.Swept(0, 0, 0));
        assertThat(WorkshopSweep.sweep(null, null, Duration.ofDays(7), Instant.now())).isEqualTo(new WorkshopSweep.Swept(0, 0, 0));
    }
}
