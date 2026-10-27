package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A household item the steward puts away leaves the items folder for one beside it, kept, and
 * comes back the same way; the unfinished ones are named with their first problem.
 */
class AHouseholdItemCanBePutAwayTest {

    @TempDir Path dir;

    @AfterEach
    void tearDown() { System.clearProperty("wyrdsekai.items.dir"); }

    private static final String WHOLE = """
        exports.manifest = { name: "lantern", version: "1.0.0", description: "a lantern", author: "did:wyrd:openhands",
          capabilities: [], embodiment: { silent: true, reason: "a quiet thing" }, commands: [{ label: "Light", args: "" }] };
        function invoke(params) { return { ok: true, summary: "lit" }; }
        """;
    private static final String BROKEN = """
        exports.manifest = { name: "skills_check", version: "1.0.0", description: "checks", author: "did:wyrd:openhands",
          capabilities: [], embodiment: { silent: true, reason: "a look" }, commands: [{ label: "Check", args: "" }] };
        function invoke(params) { return { ok: true, summary: world.room.emit("hello") }; }
        """;

    @Test
    void unfinished_items_are_named_and_a_put_away_item_moves_beside_the_folder_and_back() throws Exception {
        var items = dir.resolve("items");
        Files.createDirectories(items.resolve("versions"));
        Files.writeString(items.resolve("lantern.js"), WHOLE);
        Files.writeString(items.resolve("skills_check.js"), BROKEN);
        Files.writeString(items.resolve("versions").resolve("skills_check.1.0.0-candidate.js"), BROKEN);
        ScriptedItemLoader.get();   // the loader's own folders are fixed when it is first touched: not this test's
        System.setProperty("wyrdsekai.items.dir", items.toString());

        var unfinished = HouseholdItems.unfinished(5);
        assertThat(unfinished).extracting(HouseholdItems.Unfinished::name).containsExactly("skills_check");
        assertThat(unfinished.getFirst().problem()).isNotBlank();
        Files.setLastModifiedTime(items.resolve("skills_check.js"), FileTime.from(Instant.now().minus(Duration.ofDays(30))));
        assertThat(HouseholdItems.unfinished(5, Duration.ofDays(14))).as("an old failure is not today's work").isEmpty();
        assertThat(HouseholdItems.unfinished(5)).as("still unfinished when asked for all").hasSize(1);

        assertThat(HouseholdItems.putAway("skills_check")).isPresent();
        assertThat(Files.exists(items.resolve("skills_check.js"))).isFalse();
        assertThat(Files.exists(dir.resolve("items-away").resolve("skills_check.js"))).isTrue();
        assertThat(Files.exists(dir.resolve("items-away").resolve("versions").resolve("skills_check.1.0.0-candidate.js"))).isTrue();
        assertThat(HouseholdItems.away()).containsExactly("skills_check");
        assertThat(HouseholdItems.unfinished(5)).isEmpty();

        assertThat(HouseholdItems.putAway("skills_check")).as("nothing of that name is in the folder now").isEmpty();
        assertThat(HouseholdItems.putAway("../etc")).as("a name, never a path").isEmpty();

        assertThat(HouseholdItems.takeOut("skills_check")).isPresent();
        assertThat(Files.exists(items.resolve("skills_check.js"))).isTrue();
        assertThat(HouseholdItems.away()).isEmpty();
    }
}
