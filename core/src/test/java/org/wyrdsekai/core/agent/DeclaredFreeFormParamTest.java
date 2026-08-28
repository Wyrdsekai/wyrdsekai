package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.item.ToolItem;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Where the person's request goes is the ITEM's decision, not the runtime's.
 *
 * <p>The dispatcher hardcoded {@code query} then {@code topic}. 41 of the 56
 * shipped scripts read {@code args || text || target}, so for those the person's
 * request was written to a key nothing read — every model-driven call arrived
 * with an empty argument, invisibly, forever. Live on 2026-08-07: "what did the
 * Librarian tell Kestan about velsharas?" reached {@code library_shelves} as
 * {@code {query: "<sentence>"}}, the script read {@code args} → {@code ""}, and
 * it returned its HELP SCREEN, which the companion spoke as the answer.</p>
 *
 * <p>Two things must NOT happen while fixing it, and both are pinned below:</p>
 *
 * <ul>
 *   <li>A command-style item ("history", "security") must keep its empty default.
 *       Injecting a sentence turns every one of them into "no such view".</li>
 *   <li>{@code text} must never be an injection target — for {@code journal} and
 *       {@code nostr_quill} it is content to WRITE, so injecting there would have
 *       her journal the question as though it were her own reflection.</li>
 * </ul>
 */
class DeclaredFreeFormParamTest {

    private static ToolItem scripted(List<ToolItem.ToolParam> params) {
        return ToolItem.scripted("item-1", "thing", "a thing",
            "exports.invoke = function(){};", params, "did:wyrd:test");
    }

    private static ToolItem.ToolParam param(String name, String type, String desc) {
        return new ToolItem.ToolParam(name, type, desc, false, null);
    }

    /** THE case: an author-declared free-form slot is where the request goes. */
    @Test
    void an_author_declared_string_slot_is_the_target() {
        var item = scripted(List.of(
            param("args", "string", "What to look for. A plain question is searched.")));

        assertThat(CompanionActor.declaredFreeFormParam(item)).isEqualTo("args");
    }

    /**
     * A schema generated from a manifest's {@code commands} list enumerates fixed
     * sub-verbs. An empty argument is the correct default view for those, so the
     * runtime must decline to inject and fall back to the old behaviour.
     */
    @Test
    void a_generated_enumerated_schema_is_not_an_injection_target() {
        var item = scripted(List.of(param("args", "string",
            "What to do with this item. Leave empty for: Read summary. "
            + "Options: \"details\" — Read details; \"security\" — Security ledger.")));

        assertThat(CompanionActor.declaredFreeFormParam(item))
            .as("injecting a sentence here turns every command item into "
                + "'no such view'")
            .isNull();
    }

    /** Non-string slots are not free text. */
    @Test
    void a_numeric_slot_is_not_a_free_form_target() {
        var item = scripted(List.of(param("limit", "number", "How many rows.")));

        assertThat(CompanionActor.declaredFreeFormParam(item)).isNull();
    }

    /** The first declared string slot wins — declaration order is the author's intent. */
    @Test
    void the_first_declared_string_slot_wins() {
        var item = scripted(List.of(
            param("count", "number", "How many."),
            param("topic", "string", "What about."),
            param("note", "string", "Anything else.")));

        assertThat(CompanionActor.declaredFreeFormParam(item)).isEqualTo("topic");
    }

    /** An item that declares nothing keeps the old query/topic fallback. */
    @Test
    void an_item_with_no_schema_declines_to_choose() {
        assertThat(CompanionActor.declaredFreeFormParam(scripted(List.of()))).isNull();
        assertThat(CompanionActor.declaredFreeFormParam(scripted(null))).isNull();
        assertThat(CompanionActor.declaredFreeFormParam(null)).isNull();
    }

    /** Blank and null names must not become injection keys. */
    @Test
    void skips_unnamed_slots() {
        var item = scripted(List.of(
            param("", "string", "nameless"),
            param(null, "string", "also nameless"),
            param("args", "string", "the real one")));

        assertThat(CompanionActor.declaredFreeFormParam(item)).isEqualTo("args");
    }

    /**
     * The shipped {@code library_shelves} manifest must actually declare the slot
     * — the fix is worthless if the one item it was written for doesn't opt in.
     */
    @Test
    void the_shipped_library_shelves_script_declares_its_slot() throws Exception {
        var fromCore = Paths.get("..", "scripts", "items", "library_shelves.js");
        var fromRoot = Paths.get("scripts", "items", "library_shelves.js");
        var path = Files.exists(fromCore) ? fromCore : fromRoot;
        var src = Files.readString(path);

        assertThat(src).as("must declare a params schema so the dispatcher can inject")
            .contains("params: [");
        assertThat(src).as("and it must read `query`, which is what gets injected")
            .contains("params.query");
    }
}
