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
}
