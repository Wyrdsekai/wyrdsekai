package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * disk-based loader contract.
 */
class ScriptedItemLoaderTest {

    @TempDir Path itemsDir;
    private ScriptedItemLoader loader;

    @BeforeEach
    void setup() {
        loader = ScriptedItemLoader.get();
        loader.setSearchDirs(List.of(itemsDir));
        loader.reloadAll();
    }

    @AfterEach
    void teardown() {
        loader.stopWatching();
        loader.setSearchDirs(List.of());
        loader.reloadAll();
    }

    @Test
    void loads_valid_item_from_disk() throws IOException {
        var script = """
            exports.manifest = {
              name: "test_compass",
              version: "1.0.0",
              description: "Shows the time and zone.",
              author: "did:wyrd:test",
              capabilities: []
            };
            function invoke(p) { return { ok: true }; }
            """;
        Files.writeString(itemsDir.resolve("test_compass.js"), script);
        var loaded = loader.reloadAll();
        assertEquals(1, loaded.size());
        var def = loaded.getFirst();
        assertEquals("test_compass", def.itemId());
        assertEquals("1.0.0", def.manifest().version());
        assertNotNull(def.scriptSource());
        assertTrue(def.scriptSource().contains("function invoke"));
    }

    @Test
    void rejects_malformed_manifest_with_warn_skip() throws IOException {
        var script = """
            exports.manifest = {
              name: "BAD-NAME",
              version: "not-semver",
              description: "",
              author: "no-did-here",
              capabilities: ["unknown_capability"]
            };
            function invoke() {}
            """;
        Files.writeString(itemsDir.resolve("bad_item.js"), script);
        var loaded = loader.reloadAll();
        assertTrue(loaded.isEmpty(), "malformed manifest should be skipped");
    }

    @Test
    void duplicate_item_ids_second_wins() throws IOException {
        var first = """
            exports.manifest = {
              name: "twin",
              version: "1.0.0",
              description: "First copy.",
              author: "did:wyrd:a",
              capabilities: []
            };
            function invoke() { return { who: "first" }; }
            """;
        var second = """
            exports.manifest = {
              name: "twin",
              version: "2.0.0",
              description: "Second copy.",
              author: "did:wyrd:b",
              capabilities: []
            };
            function invoke() { return { who: "second" }; }
            """;
        Files.writeString(itemsDir.resolve("aaaa_first.js"), first);
        Files.writeString(itemsDir.resolve("zzzz_second.js"), second);
        var loaded = loader.reloadAll();
        assertEquals(1, loaded.size(), "duplicates collapse to one entry");
        // Sorted alphabetically; second file replaces first
        assertEquals("2.0.0", loaded.getFirst().manifest().version());
    }

    @Test
    void reload_picks_up_new_files() throws IOException {
        Files.writeString(itemsDir.resolve("first.js"), """
            exports.manifest = {
              name: "first_item",
              version: "1.0.0",
              description: "First.",
              author: "did:wyrd:a",
              capabilities: []
            };
            function invoke() {}
            """);
        var initial = loader.reloadAll();
        assertEquals(1, initial.size());

        Files.writeString(itemsDir.resolve("second.js"), """
            exports.manifest = {
              name: "second_item",
              version: "1.0.0",
              description: "Second.",
              author: "did:wyrd:b",
              capabilities: []
            };
            function invoke() {}
            """);
        var afterAdd = loader.reloadAll();
        assertEquals(2, afterAdd.size());
    }

    @Test
    void empty_directory_returns_empty_list() {
        var loaded = loader.reloadAll();
        assertTrue(loaded.isEmpty());
    }

    @Test
    void missing_directory_does_not_throw() {
        loader.setSearchDirs(List.of(itemsDir.resolve("does-not-exist")));
        assertDoesNotThrow(() -> loader.reloadAll());
        assertTrue(loader.all().isEmpty());
    }

    @Test
    void watch_service_reloads_after_file_create() throws Exception {
        loader.startWatching();
        Thread.sleep(200);

        var initialReloadAt = loader.lastReloadAt();
        Files.writeString(itemsDir.resolve("watched.js"), """
            exports.manifest = {
              name: "watched_item",
              version: "1.0.0",
              description: "Hot-reloaded.",
              author: "did:wyrd:test",
              capabilities: []
            };
            function invoke() {}
            """);

        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (loader.lastReloadAt() > initialReloadAt
                    && loader.get("watched_item").isPresent()) {
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(loader.get("watched_item").isPresent(),
            "watcher should have reloaded after file create");
        assertTrue(loader.lastReloadAt() > initialReloadAt,
            "lastReloadAt should advance after watcher fires");
    }

    @Test
    void watch_service_debounces_burst_writes_into_one_reload() throws Exception {
        loader.startWatching();
        Thread.sleep(200);

        // Write three files in quick succession — debounce should coalesce them
        // into a single reload.
        var initial = loader.lastReloadAt();
        for (int i = 0; i < 3; i++) {
            Files.writeString(itemsDir.resolve("burst" + i + ".js"), """
                exports.manifest = {
                  name: "burst_%d",
                  version: "1.0.0",
                  description: "Burst write.",
                  author: "did:wyrd:test",
                  capabilities: []
                };
                function invoke() {}
                """.formatted(i));
        }

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (loader.all().size() == 3) break;
            Thread.sleep(100);
        }
        assertEquals(3, loader.all().size(),
            "all three burst items should be loaded after debounced reload");
        assertTrue(loader.lastReloadAt() > initial,
            "lastReloadAt should advance once after the burst");
    }

    @Test
    void watch_service_starts_idempotently() throws Exception {
        loader.startWatching();
        loader.startWatching();  // second call must be a no-op
        Thread.sleep(100);
        loader.stopWatching();
        // No assertion needed — we're proving "doesn't throw / leak threads".
    }

    @Test
    void scripted_item_def_converts_to_tool_item() throws IOException {
        var script = """
            exports.manifest = {
              name: "scribe",
              version: "1.2.3",
              description: "Writes scrolls.",
              author: "did:wyrd:test",
              capabilities: ["library.search"]
            };
            function invoke(p) { return { ok: true }; }
            """;
        Files.writeString(itemsDir.resolve("scribe.js"), script);
        var def = loader.reloadAll().getFirst();
        var tool = def.toToolItem();
        assertEquals("scribe", tool.id());
        assertTrue(tool.isScripted());
        assertEquals("did:wyrd:test", tool.creatorDid());
    }
    /**
     * Live 2026-09-08: a copy of the journal in the data directory called world.journal.list —
     * renamed long before — and shadowed the working bundled copy by name. `use journal` died
     * on its first call. A mis-wired copy must never replace a working one; alone, it loads
     * with the fix in the log and sits in the audit.
     */
    @Test
    void a_mis_wired_copy_never_replaces_a_working_one_and_is_audited_when_alone(@TempDir Path bundled, @TempDir Path household) throws IOException {
        var good = """
            exports.manifest = { name: "journal", version: "1.0.0", description: "A journal.", author: "did:wyrd:test", capabilities: ["journal.write"] };
            function invoke(p) { return { entries: world.journal.recent(5) }; }
            """;
        var stale = """
            exports.manifest = { name: "journal", version: "1.0.0", description: "A journal.", author: "did:wyrd:test", capabilities: ["journal.write"] };
            function invoke(p) { return { entries: world.journal.list() }; }
            """;
        Files.writeString(bundled.resolve("journal.js"), good);
        Files.writeString(household.resolve("journal.js"), stale);
        loader.setSearchDirs(List.of(bundled, household));
        var loaded = loader.reloadAll();
        assertEquals(1, loaded.size());
        assertEquals(bundled.resolve("journal.js"), loaded.getFirst().sourcePath(), "the working copy is kept");
        assertTrue(loader.wiringAudit().isEmpty(), "a shadowed broken copy is not what runs, so it is not audited as running");

        // the broken copy alone: loaded (nothing better), but named in the audit with the fix
        loader.setSearchDirs(List.of(household));
        loaded = loader.reloadAll();
        assertEquals(1, loaded.size());
        assertEquals(1, loader.wiringAudit().size());
        var entry = loader.wiringAudit().getFirst();
        assertEquals("journal", entry.itemId());
        assertTrue(entry.unresolved().getFirst().contains("world.journal.list does not exist"), entry.unresolved().toString());
        assertTrue(entry.unresolved().getFirst().contains("has: recent, search, write"), entry.unresolved().toString());

        // a working copy arriving later replaces the broken one and clears the audit
        loader.setSearchDirs(List.of(household, bundled));
        loaded = loader.reloadAll();
        assertEquals(bundled.resolve("journal.js"), loaded.getFirst().sourcePath());
        assertTrue(loader.wiringAudit().isEmpty());
    }

    @Test
    void another_authors_same_named_copy_replaces_a_bundled_item_only_when_newer(@TempDir Path bundled, @TempDir Path household) throws IOException {
        var ours = """
            exports.manifest = { name: "journal", version: "1.0.0", description: "The journal.", author: "did:wyrd:system", capabilities: ["journal.write"] };
            function invoke(p) { return { entries: world.journal.recent(5) }; }
            """;
        var theirs = """
            exports.manifest = { name: "journal", version: "1.0.0", description: "A journal.", author: "did:wyrd:openhands", capabilities: ["journal.write"] };
            function invoke(p) { return { entries: world.journal.recent(1) }; }
            """;
        Files.writeString(bundled.resolve("journal.js"), ours);
        Files.writeString(household.resolve("journal.js"), theirs);
        loader.setSearchDirs(List.of(bundled, household));
        var loaded = loader.reloadAll();
        assertEquals(1, loaded.size());
        assertEquals(bundled.resolve("journal.js"), loaded.getFirst().sourcePath(), "same version, different author: the loaded copy stands");
        assertEquals(1, loader.shadowed().size());
        var sh = loader.shadowed().getFirst();
        assertEquals("journal", sh.itemId());
        assertEquals("did:wyrd:openhands", sh.shadowedAuthor());
        assertEquals("did:wyrd:system", sh.keptAuthor());

        // register() of the same file says it did not register — the bridge must not stamp it
        assertTrue(loader.register(household.resolve("journal.js")).isEmpty());
        assertEquals(bundled.resolve("journal.js"), loader.all().getFirst().sourcePath());

        // a newer version from another author is a deliberate replacement and wins
        Files.writeString(household.resolve("journal.js"), theirs.replace("1.0.0", "1.1.0"));
        loaded = loader.reloadAll();
        assertEquals(household.resolve("journal.js"), loaded.getFirst().sourcePath());
        assertTrue(loader.shadowed().isEmpty());

        // the same author re-shipping the same version still replaces (a re-install, an edit in place)
        Files.writeString(household.resolve("journal.js"), ours.replace("recent(5)", "recent(7)"));
        loaded = loader.reloadAll();
        assertEquals(household.resolve("journal.js"), loaded.getFirst().sourcePath());
        assertTrue(loaded.getFirst().scriptSource().contains("recent(7)"));
    }

    @Test
    void an_agent_built_item_may_not_take_the_name_of_a_builtin_action() throws IOException {
        // The companion built "craft_from_template" — the verb she had meant to call — and it was
        // kept as a dead item beside the real action (household node, 2026-09-11).
        var agentBuilt = """
            exports.manifest = {
              name: "craft_from_template",
              version: "1.0.0",
              description: "a bonded object that arrives with its own history",
              author: "did:wyrd:openhands",
              capabilities: []
            };
            function invoke(p) { return { ok: true }; }
            """;
        Files.writeString(itemsDir.resolve("craft_from_template.js"), agentBuilt);
        var loaded = loader.reloadAll();
        assertTrue(loaded.stream().noneMatch(d -> d.itemId().equals("craft_from_template")),
            "an item named after a builtin action is not loaded");
        assertTrue(loader.register(itemsDir.resolve("craft_from_template.js")).isEmpty(),
            "register() says it did not register, so the bridge places it as inert");
        var audit = loader.wiringAudit();
        assertTrue(audit.stream().anyMatch(e -> e.itemId().equals("craft_from_template")
            && e.unresolved().getFirst().contains("Give it its own name")), audit.toString());

        // The runtime's own items are exempt — a bundled item may carry a verb's name.
        var ours = agentBuilt.replace("did:wyrd:openhands", "did:wyrd:system").replace("craft_from_template", "go_to_room");
        Files.writeString(itemsDir.resolve("go_to_room.js"), ours);
        assertTrue(loader.reloadAll().stream().anyMatch(d -> d.itemId().equals("go_to_room")));
        // And an ordinary agent-built name is untouched.
        Files.writeString(itemsDir.resolve("gift_of_work.js"), agentBuilt.replace("craft_from_template", "gift_of_work"));
        assertTrue(loader.reloadAll().stream().anyMatch(d -> d.itemId().equals("gift_of_work")));
    }
}
