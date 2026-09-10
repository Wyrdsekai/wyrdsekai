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
}
