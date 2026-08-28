package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.item.ScriptedItemLoader;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tool she already made should get BETTER, not get a sibling.
 *
 * <h2>What this costs today</h2>
 * {@code revise_form} exists for thought-forms; nothing equivalent existed for scripted
 * items. So every "can you make me…" produced a new item, and changing one meant asking
 * for a replacement and living with both.
 *
 * <p>Live 2026-08-21: four asks for essentially one library tool. Two results were
 * refused, one was retired, and the survivor summarises where the steward wanted it to
 * tell a story. Without revision, getting what he asked for means a fifth item that does
 * almost the same thing — and then choosing between them forever.
 *
 * <p>That is the line between a tool-making system that compounds and one that
 * accumulates.
 */
class ReviseRatherThanReMakeTest {

    @TempDir Path tmp;
    private Path itemsDir;

    private static final String V1 = """
        exports.manifest = {
          name: "library_stories",
          version: "1.0.0",
          description: "Queries the library and summarises what it finds.",
          author: "did:wyrd:goose",
          capabilities: ["library.search", "llm.summarize"],
          embodiment: { silent: false, emits: ["body_language"],
                        descriptor_template: "{actor} reads" },
          commands: [ { label: "Tell", args: "" } ]
        };
        function invoke(params) {
          var hits = world.library.search(params.args, 5);
          return { ok: true, summary: world.llm.summarize(hits[0].text, "two paragraphs") };
        }
        """;

    @BeforeEach
    void setUp() throws Exception {
        itemsDir = Files.createDirectories(tmp.resolve("items"));
        Files.writeString(itemsDir.resolve("library_stories.js"), V1);
        // setSearchDirs, not the wyrdsekai.items.dir property: searchDirs is computed
        // when the singleton is constructed, so setting the property afterwards changes
        // nothing. That is a real caveat for anyone testing near this loader.
        System.setProperty("wyrdsekai.items.dir", itemsDir.toString());
        ScriptedItemLoader.get().setSearchDirs(java.util.List.of(itemsDir));
        ScriptedItemLoader.get().reloadAll();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("wyrdsekai.items.dir");
        ScriptedItemLoader.get().setSearchDirs(java.util.List.of());
        ScriptedItemLoader.get().reloadAll();
    }

    @Test
    void revising_carries_the_current_source_so_the_backend_edits_rather_than_reinvents() {
        var instruction = ItemRevision
            .instructionFor("library_stories", "tell a fairy tale instead of a summary")
            .orElseThrow();

        assertThat(instruction)
            .as("the backend must see what it is changing")
            .contains("function invoke(params)")
            .contains("world.llm.summarize");
        assertThat(instruction)
            .as("and must be told what should be different")
            .contains("fairy tale");
    }

    /** The name is the identity: change it and you have made a second item, not a better one. */
    @Test
    void the_instruction_pins_the_name_and_asks_for_a_version_bump() {
        var instruction = ItemRevision.instructionFor("library_stories", "add details")
            .orElseThrow();
        assertThat(instruction).contains("EXACTLY as \"library_stories\"");
        assertThat(instruction).contains("BUMP the manifest `version`");
    }

    /**
     * Revising something that does not exist must fall through to an ordinary build.
     * Producing an item from nothing would be worse than the duplicate problem.
     */
    @Test
    void revising_something_that_does_not_exist_is_not_a_revision() {
        assertThat(ItemRevision.instructionFor("no_such_tool", "change it")).isEmpty();
        assertThat(ItemRevision.exists("no_such_tool")).isFalse();
        assertThat(ItemRevision.exists("library_stories")).isTrue();
    }

    /**
     * Lineage is what makes replacing in place safe. A revision that loses the previous
     * file is a worse deal than a duplicate — at least a duplicate leaves the working one
     * intact.
     */
    @Test
    void the_version_being_replaced_is_kept() {
        var archived = ItemRevision.archive(itemsDir, "library_stories").orElseThrow();
        assertThat(archived).exists();
        assertThat(archived.getFileName().toString()).isEqualTo("library_stories.1.0.0.js");
        assertThat(archived).content().contains("summarise");
        assertThat(itemsDir.resolve("library_stories.js"))
            .as("archiving must not remove the live item").exists();
    }

    /** Two revisions of the same version must not silently overwrite each other. */
    @Test
    void a_second_archive_of_the_same_version_does_not_clobber_the_first() {
        ItemRevision.archive(itemsDir, "library_stories").orElseThrow();
        var second = ItemRevision.archive(itemsDir, "library_stories").orElseThrow();
        assertThat(second.getFileName().toString())
            .isEqualTo("library_stories.1.0.0-2.js");
    }

    @Test
    void archiving_something_absent_is_quietly_nothing() {
        assertThat(ItemRevision.archive(itemsDir, "no_such_tool")).isEmpty();
        assertThat(ItemRevision.archive(null, "library_stories")).isEmpty();
    }

    /** She has to be able to CHOOSE it, or the capability is decoration. */
    @Test
    void the_tool_is_offered_and_says_when_to_prefer_it() {
        // Asserted from the source, because DESCRIPTIONS is private: what matters is
        // that the tool is DECLARED and that its text tells her when to prefer it over
        // building another one. A capability she is never offered is decoration.
        var src = java.nio.file.Path.of(
            "src/main/java/org/wyrdsekai/core/agent/ActionToolBuilder.java");
        if (!java.nio.file.Files.isRegularFile(src)) {
            src = java.nio.file.Path.of(
                "core/src/main/java/org/wyrdsekai/core/agent/ActionToolBuilder.java");
        }
        assertThat(java.nio.file.Files.isRegularFile(src))
            .as("this guard must never silently pass for want of its source").isTrue();
        String declared;
        try {
            declared = java.nio.file.Files.readString(src);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertThat(declared).contains("Map.entry(\"revise_item\"");
        assertThat(declared).contains("INSTEAD of");
    }

    /**
     * v1.0.0 of trip_compass made four adapter calls; the revision that asked only for a
     * different argument split came back with none — goose swapped openweather for
     * web.fetch("current temperature in Denver, CO") and then could not find a number in
     * the page. "Leave the rest alone" was not enough; the instruction has to say it.
     */
    @org.junit.jupiter.api.Test
    void a_revision_is_told_to_keep_every_service_call() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/coding/ItemRevision.java";
        var fromCore = java.nio.file.Path.of("..", rel);
        var src = java.nio.file.Files.readString(
            java.nio.file.Files.exists(fromCore) ? fromCore : java.nio.file.Path.of(rel));
        org.assertj.core.api.Assertions.assertThat(src)
            .contains("A REVISION NEVER MAKES THE ITEM LESS REAL");
        org.assertj.core.api.Assertions.assertThat(src).contains("Do NOT replace");
    }

    /**
     * 22:17 the same day: goose revised trip_compass, kept every adapter, added humidity —
     * and left the version at 1.0.0. The archive held 1.0.0.js and 1.0.0-2.js: two
     * programs, one number. The person asked for the change; the number is corrected
     * rather than the change refused.
     */
    @org.junit.jupiter.api.Test
    void a_same_version_revision_is_bumped_rather_than_refused() {
        var src = "exports.manifest = { name: \"t\", version: \"1.0.0\" };\nfunction invoke(p){}";
        org.assertj.core.api.Assertions.assertThat(ItemRevision.versionOf(
            ItemRevision.bumpIfSame(src, "1.0.0"))).isEqualTo("1.0.1");
        // A revision that DID bump is left alone.
        var bumped = "exports.manifest = { name: \"t\", version: \"1.1.0\" };";
        org.assertj.core.api.Assertions.assertThat(ItemRevision.bumpIfSame(bumped, "1.0.0"))
            .isSameAs(bumped);
        // No previous version known: nothing to compare against, nothing changes.
        org.assertj.core.api.Assertions.assertThat(ItemRevision.bumpIfSame(src, null)).isSameAs(src);
    }
}
