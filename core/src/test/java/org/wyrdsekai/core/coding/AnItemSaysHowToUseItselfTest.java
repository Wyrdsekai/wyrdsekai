package org.wyrdsekai.core.coding;

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
 * A thing you are handed should tell you what it is and what to type.
 *
 * <p>An accepted item arrives carrying a {@code description} of itself and a list of
 * {@code commands} — the manifest fields that exist precisely to answer "what is this and
 * how do I use it" — and none of it reached the person holding it. What the steward saw
 * was {@code "A goose codex containing 1 file(s) for task bd605d46-716c-4e10-…"}: a uuid
 * and a file count. His words, 2026-08-20: <i>"I got the item but I don't know how to
 * actually use it."</i>
 */
class AnItemSaysHowToUseItselfTest {

    @TempDir Path workspace;

    /** The manifest of the item goose actually produced on the household node. */
    private static final String LIBRARIAN = """
        exports.manifest = {
          name: "librarian",
          version: "1.0.0",
          description: "A scripted tool that fetches library entries and turns them into a brief story.",
          author: "did:wyrd:openhands",
          capabilities: ["library.search", "room.emit"],
          embodiment: { silent: false, emits: ["body_language"],
                        descriptor_template: "{actor} works through the material" },
          commands: [
            { label: "Query the library and speak a story", args: "" },
            { label: "Show the query results before writing", args: "details" }
          ]
        };
        function invoke(params) { return { ok: true }; }
        """;

    private SourceArtifact artifactFor(String fileName, String script) throws Exception {
        Files.writeString(workspace.resolve(fileName), script);
        return new SourceArtifact(UUID.randomUUID(), GooseBackend.NAME, "task-1",
            workspace.toString(), List.of(fileName), null, Instant.now(), Map.of());
    }

    @Test
    void the_description_is_the_items_own_words_not_a_task_uuid() throws Exception {
        var told = CodingTaskItemBridge.manifestDescription(
            artifactFor("librarian.js", LIBRARIAN), "librarian");
        assertThat(told).isPresent();
        assertThat(told.get())
            .contains("fetches library entries")
            .doesNotContain("codex containing")
            .doesNotContain("task-1");
    }

    @Test
    void every_declared_command_becomes_a_line_a_person_can_type() throws Exception {
        var told = CodingTaskItemBridge.manifestDescription(
            artifactFor("librarian.js", LIBRARIAN), "librarian").orElseThrow();
        // The no-arg command and the one that takes an argument, both spelled out.
        assertThat(told).contains("`use librarian`");
        assertThat(told).contains("`use librarian details`");
        // ...each with the label that says what it does.
        assertThat(told).contains("Query the library and speak a story");
        assertThat(told).contains("Show the query results before writing");
    }

    @Test
    void the_name_in_the_usage_line_is_the_name_the_object_carries() throws Exception {
        // If these drift, the instructions tell the person to type something the room
        // does not answer to — worse than no instructions.
        var art = artifactFor("librarian.js", LIBRARIAN);
        var name = CodingTaskItemBridge.manifestNameOf(art).orElseThrow();
        assertThat(name).isEqualTo("librarian");
        assertThat(CodingTaskItemBridge.manifestDescription(art, name).orElseThrow())
            .contains("use " + name);
    }

    @Test
    void an_artifact_with_no_manifest_falls_back_rather_than_inventing_usage() throws Exception {
        var told = CodingTaskItemBridge.manifestDescription(
            artifactFor("plain.js", "// just some code\nfunction invoke(p){return {};}"),
            "plain");
        assertThat(told)
            .as("no manifest means no claims about how to use it")
            .isEmpty();
    }

    /** The shape that arrived live on 2026-08-21: invoke sealed inside an IIFE. */
    private static final String UNRUNNABLE = """
        (function (exports) {
          function invoke(params) { return { ok: true }; }
          exports.manifest = {
            name: "library_query",
            version: "1.0.0",
            description: "Queries the library and speaks the result as a story.",
            author: "did:wyrd:openhands",
            capabilities: [],
            embodiment: { silent: false, emits: ["body_language"],
                          descriptor_template: "{actor} works the tool" },
            commands: [ { label: "Search the library", args: "your-query-string" } ]
          };
        })(exports);
        """;

    /**
     * The cruellest version of the failure, and the reason this gate exists.
     *
     * <p>Placement happens BEFORE registration, so the description was written by
     * something that did not yet know whether the item was real. On 2026-08-21 the
     * manifest's declared commands were rendered as literal lines to type, the bridge then
     * refused the file for having no callable {@code invoke()}, and the steward — following
     * an instruction the world itself had given him — typed {@code use library_query} and
     * was told there was no such object.
     *
     * <p>The item may still be placed; the work is his. It may not tell him to type
     * something that cannot work.
     */
    @Test
    void an_item_that_will_not_register_does_not_tell_you_to_use_it() throws Exception {
        var told = CodingTaskItemBridge.manifestDescription(
            artifactFor("library_query.js", UNRUNNABLE), "library_query").orElseThrow();
        assertThat(told).contains("Queries the library");
        assertThat(told).doesNotContain("`use library_query");
        assertThat(told).contains("unfinished");
    }

    /** And the same file, written so it runs, does get its usage lines. */
    @Test
    void a_runnable_item_still_says_what_to_type() throws Exception {
        var told = CodingTaskItemBridge.manifestDescription(
            artifactFor("librarian.js", LIBRARIAN), "librarian").orElseThrow();
        assertThat(told).contains("`use librarian`");
        assertThat(told).doesNotContain("unfinished");
    }
}
