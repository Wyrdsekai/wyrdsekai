package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The surface is read from the code that serves it, and a script's calls are checked against it
 * before the first `use` — the journal that broke on the household node (2026-09-08) called a
 * method that had been renamed, and nothing said so until a person typed `use journal`.
 */
class ItemApiSurfaceTest {

    @Test
    void the_surface_is_the_exported_world_object() {
        assertTrue(ItemApiSurface.namespaces().contains("journal"));
        assertTrue(ItemApiSurface.namespaces().contains("library"));
        assertTrue(ItemApiSurface.methods("journal").contains("recent"));
        assertTrue(ItemApiSurface.methods("journal").contains("write"));
        assertFalse(ItemApiSurface.methods("journal").contains("list"));
        assertTrue(ItemApiSurface.subNamespaces("soul").contains("fragments"), ItemApiSurface.subNamespaces("soul").toString());
    }

    @Test
    void the_renamed_journal_call_is_caught_with_the_fix_in_the_message() {
        var script = """
            exports.manifest = { name: "journal", version: "1.0.0", capabilities: ["journal.write"] };
            function invoke(p) {
              // world.journal.list() in a comment does not count
              const entries = world.journal.list();
              world.journal.write("x");
              return entries;
            }
            """;
        var bad = ItemApiSurface.check(script);
        assertEquals(1, bad.size(), bad.toString());
        assertEquals("world.journal.list", bad.getFirst().call());
        // "list" → "recent" is a rename, not a typo: no guess, but the fix is in the line — what exists
        assertTrue(bad.getFirst().reason().contains("world.journal has: recent, search, write"), bad.getFirst().reason());
        // a near-miss does get a guess
        var typo = ItemApiSurface.check("function invoke(){ return world.journal.recnt(3); }");
        assertTrue(typo.getFirst().reason().contains("did you mean recent?"), typo.getFirst().reason());
    }

    @Test
    void adapters_and_unknown_namespaces_are_left_alone_and_good_scripts_pass_clean() {
        var script = """
            function invoke(p) {
              var w = world.openweather.current("Osaka");     // an adapter namespace: dynamic
              var n = world.notes.list();
              var r = world.library.search("x", 3);
              var f = world.soul.fragments.list ? 1 : 0;
              return [w, n, r, f];
            }
            """;
        var bad = ItemApiSurface.check(script);
        assertTrue(bad.stream().noneMatch(u -> u.call().startsWith("world.openweather")), bad.toString());
        assertTrue(bad.stream().noneMatch(u -> u.call().equals("world.notes.list")), bad.toString());
        assertTrue(bad.stream().noneMatch(u -> u.call().equals("world.library.search")), bad.toString());
        assertTrue(ItemApiSurface.check("").isEmpty());
        assertTrue(ItemApiSurface.check(null).isEmpty());
    }

    @Test
    void a_wild_name_gets_no_guess() {
        var bad = ItemApiSurface.check("function invoke(){ return world.journal.summonTheDragon(); }");
        assertEquals(1, bad.size());
        assertFalse(bad.getFirst().reason().contains("did you mean"), bad.getFirst().reason());
    }

    @Test
    void a_call_with_the_wrong_number_of_arguments_is_caught_with_the_arities_it_takes() {
        var script = """
            exports.manifest = { name: "tray", version: "1.0.0", description: "x", author: "a", capabilities: [] };
            function invoke(p) {
              world.room.emit("tray.settle");                       // one short — no applicable overload at runtime
              world.room.emit("tray.settle", { who: p.entityId }); // fine
              world.journal.write("a, b (c)", { tags: ["x, y"] });  // commas inside strings/brackets are not arguments
              world.journal.write(`t ${p.a}`);                      // template literal, one argument
              world.journal.write.apply(null, p.args);              // not a direct call: silence
              world.journal.write(...p.args);                       // spread: count unknown, silence
              return world.journal.recent();
            }
            """;
        var bad = ItemApiSurface.check(script);
        assertEquals(1, bad.size(), bad.toString());
        assertEquals("world.room.emit", bad.getFirst().call());
        assertTrue(bad.getFirst().reason().contains("called with 1 argument; it takes 2"), bad.getFirst().reason());
        assertTrue(bad.getFirst().reason().contains("no applicable overload"), bad.getFirst().reason());
        assertEquals("1 or 2", ItemApiSurface.arityOf("journal", "write").orElseThrow());
    }

    @Test
    void argument_counting_reads_the_source_the_way_the_engine_would() {
        assertEquals(0, ItemApiSurface.countArgs("world.x.y()", "world.x.y(".length()));
        assertEquals(0, ItemApiSurface.countArgs("world.x.y(  )", "world.x.y(".length()));
        assertEquals(1, ItemApiSurface.countArgs("f('a,b')", 2));
        assertEquals(2, ItemApiSurface.countArgs("f([1,2], {a:(1,2)})", 2));
        assertEquals(1, ItemApiSurface.countArgs("f(\"esc\\\"aped, \")", 2));
        assertEquals(-1, ItemApiSurface.countArgs("f(a, b", 2));
        assertEquals(-1, ItemApiSurface.countArgs("f(...rest)", 2));
    }

    @Test
    void a_chest_that_answers_every_command_the_same_way_is_flagged() {
        var script = """
            function invoke(params) {
              return { ok: true, summary: "The chest is open and waiting. Say `use chest create`." };
            }
            exports.invoke = invoke;
            exports.manifest = { name: "chest", version: "1.0.0", capabilities: [],
              commands: [ { label: "Pack a new backup snapshot", args: "create" },
                          { label: "Show current snapshots", args: "" } ] };
            """;
        var commands = java.util.List.of(new ItemManifest.Command("Pack a new backup snapshot", "create"),
            new ItemManifest.Command("Show current snapshots", ""));
        var finding = ItemApiSurface.commandsNeverRead(script, commands);
        assertTrue(finding.isPresent());
        assertTrue(finding.get().contains("never reads params.args"), finding.get());
        assertTrue(finding.get().contains("create"), finding.get());
    }

    @Test
    void an_item_that_branches_on_its_args_or_has_one_command_is_not_flagged() {
        var branching = """
            function invoke(params) {
              var args = (params.args || "").trim();
              if (args === "create") return { ok: true, summary: "packed" };
              return { ok: true, summary: "listed" };
            }
            """;
        var two = java.util.List.of(new ItemManifest.Command("Pack", "create"), new ItemManifest.Command("Show", ""));
        assertTrue(ItemApiSurface.commandsNeverRead(branching, two).isEmpty());
        var destructured = "function invoke({ args, agentDid }) { return { ok: true, summary: args }; }";
        assertTrue(ItemApiSurface.commandsNeverRead(destructured, two).isEmpty());
        var oneWay = "function invoke(params) { return { ok: true, summary: 'hi' }; }";
        assertTrue(ItemApiSurface.commandsNeverRead(oneWay, java.util.List.of(new ItemManifest.Command("Use", ""))).isEmpty());
    }

    @Test
    void a_mention_of_params_args_in_a_comment_is_not_a_read() {
        var twinChest = """
            /**
             * @param {Object} params — invoke arguments; params.args is the string
             *                          the user typed after `use chest`.
             */
            function invoke(params) {
              // params.args would be read here, but is not
              return { ok: true, summary: `The chest is open and waiting. Say \\`use chest create\\`.` };
            }
            """;
        var two = java.util.List.of(new ItemManifest.Command("Pack", "create"), new ItemManifest.Command("Show", ""));
        assertTrue(ItemApiSurface.commandsNeverRead(twinChest, two).isPresent());
        var url = "function invoke(params) { var u = \"http://x/\"; return { ok: true, summary: params.args }; }";
        assertTrue(ItemApiSurface.commandsNeverRead(url, two).isEmpty(), "a // inside a string is not a comment");
    }
}
