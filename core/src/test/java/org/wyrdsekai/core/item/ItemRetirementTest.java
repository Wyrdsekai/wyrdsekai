package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Removing an item has to remove it from everywhere it lives.
 *
 * <p>An in-house scripted item is an inventory row, a room object, a loader registration,
 * and a {@code .js} re-read on every boot. Clearing some and not the others is worse than
 * clearing none: the object vanishes while the name still resolves, or the row goes and
 * the file brings it straight back at the next restart.
 *
 * <p>Until now a steward had no delete at all — only {@code drop}, which leaves the thing
 * in the room. That is how a Nexus ends up holding two objects called {@code codex} with
 * no way to be rid of either (household node, 2026-08-20).
 */
class ItemRetirementTest {

    @TempDir Path itemsDir;

    private static final String ITEM = """
        exports.manifest = {
          name: "librarian", version: "1.0.0",
          description: "Fetches library entries and tells a story.",
          author: "did:wyrd:goose", capabilities: [],
          embodiment: { silent: false, emits: ["body_language"],
                        descriptor_template: "{actor} reads" },
          commands: [ { label: "Tell a story", args: "" } ]
        };
        function invoke(params) { return { ok: true }; }
        """;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("wyrdsekai.items.dir", itemsDir.toString());
        Files.writeString(itemsDir.resolve("librarian.js"), ITEM);
        ScriptedItemLoader.get().setSearchDirs(List.of(itemsDir));
        ScriptedItemLoader.get().reloadAll();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("wyrdsekai.items.dir");
        ScriptedItemLoader.get().setSearchDirs(List.of());
        ScriptedItemLoader.get().reloadAll();
    }

    private boolean registered(String id) {
        return ScriptedItemLoader.get().all().stream()
            .anyMatch(d -> id.equals(d.itemId()));
    }

    @Test
    void retiring_unregisters_it_and_moves_the_script_out_of_the_way() {
        assertThat(registered("librarian")).as("precondition").isTrue();

        var out = ItemRetirement.retireScripted("librarian");

        assertThat(out.found()).isTrue();
        assertThat(out.clean()).as(out.problems().toString()).isTrue();
        assertThat(registered("librarian")).as("the name must stop resolving").isFalse();
        assertThat(Files.exists(itemsDir.resolve("librarian.js")))
            .as("and the file must not be left where boot will re-read it").isFalse();
    }

    @Test
    void it_is_soft_so_a_mistake_is_recoverable() {
        // These are things she made. A typo must not be able to erase one.
        ItemRetirement.retireScripted("librarian");
        assertThat(ItemRetirement.listRetired()).contains("librarian.js");

        var back = ItemRetirement.restore("librarian.js");
        assertThat(back.found()).isTrue();
        assertThat(registered("librarian")).as("restoring must bring it back").isTrue();
        assertThat(ItemRetirement.listRetired()).doesNotContain("librarian.js");
    }

    @Test
    void it_survives_a_reload_after_retirement() {
        // The whole point: a restart must not resurrect it.
        ItemRetirement.retireScripted("librarian");
        ScriptedItemLoader.get().reloadAll();
        assertThat(registered("librarian")).isFalse();
    }

    @Test
    void a_name_that_is_not_here_says_so_rather_than_pretending() {
        var out = ItemRetirement.retireScripted("no_such_item");
        assertThat(out.found()).isFalse();
        assertThat(out.describe("no_such_item")).contains("nothing called");
    }

    @Test
    void it_matches_the_display_name_too_because_that_is_what_a_person_types() {
        var def = ScriptedItemLoader.get().all().stream()
            .filter(d -> "librarian".equals(d.itemId())).findFirst().orElseThrow();
        var out = ItemRetirement.retireScripted(def.displayName());
        assertThat(out.found()).isTrue();
        assertThat(registered("librarian")).isFalse();
    }

    @Test
    void the_outcome_tells_the_person_what_actually_happened() {
        var out = ItemRetirement.retireScripted("librarian");
        assertThat(out.describe("librarian"))
            .contains("librarian")
            .contains("restorable");
    }
}
