package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One directory reached two ways is one directory.
 *
 * <p>On a .deb host {@code ~/.wyrdsekai} is a symlink to {@code /var/lib/wyrdsekai}, so the
 * user-items dir and the household-items dir are the same place. Every reload scanned it
 * twice and warned "duplicate item id … replaces …" for every item she has (live
 * 2026-09-03, about twenty warnings per reload, none of them a real duplicate).
 */
class ScriptedItemLoaderScansEachDirOnceTest {

    @TempDir Path root;

    @AfterEach
    void teardown() {
        ScriptedItemLoader.get().setSearchDirs(List.of());
    }

    @Test
    void a_symlink_to_a_scanned_dir_is_not_scanned_again() throws Exception {
        var real = Files.createDirectories(root.resolve("real-items"));
        var link = root.resolve("link-items");
        Files.createSymbolicLink(link, real);
        Files.writeString(real.resolve("test_lantern.js"), """
            exports.manifest = {
              name: "test_lantern",
              version: "1.0.0",
              description: "Lights the way.",
              author: "did:wyrd:test",
              capabilities: []
            };
            function invoke(p) { return { ok: true }; }
            """);

        var loader = ScriptedItemLoader.get();
        loader.setSearchDirs(List.of(link, real, real.resolve("../real-items")));

        assertEquals(1, loader.scannedDirs().size(),
            "three spellings of one directory must scan once");
        assertEquals(1, loader.reloadAll().size());
    }
}
