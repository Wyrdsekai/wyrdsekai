package org.wyrdsekai.core.pak;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PakManagerTest {

    @TempDir Path tempDir;

    private Path createTestPak(String name, String version) throws IOException {
        var pakFile = tempDir.resolve(name + ".wyrdpak");
        try (var zos = new ZipOutputStream(Files.newOutputStream(pakFile))) {
            // manifest.json
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write("""
                {
                  "name": "%s",
                  "version": "%s",
                  "description": "Test extension",
                  "author": "Test Author",
                  "rooms": ["rooms/test-room.js"],
                  "i18n": ["i18n/en.json"],
                  "souls": ["souls/testbot.json"]
                }
                """.formatted(name, version).getBytes());
            zos.closeEntry();

            // rooms/test-room.js
            zos.putNextEntry(new ZipEntry("rooms/test-room.js"));
            zos.write("// test room script\n".getBytes());
            zos.closeEntry();

            // i18n/en.json
            zos.putNextEntry(new ZipEntry("i18n/en.json"));
            zos.write("{\"test.greeting\": \"Hello from extension\"}".getBytes());
            zos.closeEntry();

            // souls/testbot.json
            zos.putNextEntry(new ZipEntry("souls/testbot.json"));
            zos.write("{\"name\": \"TestBot\"}".getBytes());
            zos.closeEntry();
        }
        return pakFile;
    }

    @Test
    void install_extracts_contents() throws IOException {
        var extDir = tempDir.resolve("extensions");
        var mgr = new PakManager(extDir);

        var pakFile = createTestPak("test-ext", "1.0.0");
        var manifest = mgr.install(pakFile);

        assertEquals("test-ext", manifest.name());
        assertEquals("1.0.0", manifest.version());
        assertTrue(Files.exists(extDir.resolve("test-ext/manifest.json")));
        assertTrue(Files.exists(extDir.resolve("test-ext/rooms/test-room.js")));
        assertTrue(Files.exists(extDir.resolve("test-ext/i18n/en.json")));
    }

    @Test
    void list_returns_installed_extensions() throws IOException {
        var extDir = tempDir.resolve("extensions");
        var mgr = new PakManager(extDir);

        mgr.install(createTestPak("alpha", "1.0.0"));
        mgr.install(createTestPak("beta", "2.0.0"));

        var list = mgr.list();
        assertEquals(2, list.size());
        assertEquals("alpha", list.get(0).name());
        assertEquals("beta", list.get(1).name());
    }

    @Test
    void remove_deletes_extension() throws IOException {
        var extDir = tempDir.resolve("extensions");
        var mgr = new PakManager(extDir);

        mgr.install(createTestPak("removeme", "1.0.0"));
        assertTrue(Files.exists(extDir.resolve("removeme")));

        assertTrue(mgr.remove("removeme"));
        assertFalse(Files.exists(extDir.resolve("removeme")));
    }

    @Test
    void remove_returns_false_for_nonexistent() throws IOException {
        var extDir = tempDir.resolve("extensions");
        var mgr = new PakManager(extDir);
        assertFalse(mgr.remove("nonexistent"));
    }

    @Test
    void install_replaces_existing() throws IOException {
        var extDir = tempDir.resolve("extensions");
        var mgr = new PakManager(extDir);

        mgr.install(createTestPak("replace-test", "1.0.0"));
        mgr.install(createTestPak("replace-test", "2.0.0"));

        var list = mgr.list();
        assertEquals(1, list.size());
        assertEquals("2.0.0", list.getFirst().version());
    }

    @Test
    void create_produces_valid_pak() throws IOException {
        // Build source directory
        var srcDir = tempDir.resolve("src-ext");
        Files.createDirectories(srcDir.resolve("rooms"));
        Files.writeString(srcDir.resolve("manifest.json"), """
            {"name": "created-ext", "version": "1.0.0", "rooms": ["rooms/r.js"]}
            """);
        Files.writeString(srcDir.resolve("rooms/r.js"), "// room");

        var outputFile = tempDir.resolve("created-ext.wyrdpak");
        var manifest = PakManager.create(srcDir, outputFile);

        assertEquals("created-ext", manifest.name());
        assertTrue(Files.exists(outputFile));
        assertTrue(Files.size(outputFile) > 0);

        // Install the created pak to verify it's valid
        var extDir = tempDir.resolve("extensions2");
        var mgr = new PakManager(extDir);
        mgr.install(outputFile);
        assertTrue(Files.exists(extDir.resolve("created-ext/rooms/r.js")));
    }

    @Test
    void allRoomScripts_finds_scripts() throws IOException {
        var extDir = tempDir.resolve("extensions");
        var mgr = new PakManager(extDir);
        mgr.install(createTestPak("scripted", "1.0.0"));

        var scripts = mgr.allRoomScripts();
        assertEquals(1, scripts.size());
        assertTrue(scripts.getFirst().toString().contains("test-room.js"));
    }

    @Test
    void allI18nFiles_finds_files() throws IOException {
        var extDir = tempDir.resolve("extensions");
        var mgr = new PakManager(extDir);
        mgr.install(createTestPak("i18n-ext", "1.0.0"));

        var files = mgr.allI18nFiles();
        assertEquals(1, files.size());
        assertTrue(files.getFirst().toString().contains("en.json"));
    }

    @Test
    void allSoulSeeds_finds_seeds() throws IOException {
        var extDir = tempDir.resolve("extensions");
        var mgr = new PakManager(extDir);
        mgr.install(createTestPak("soul-ext", "1.0.0"));

        var seeds = mgr.allSoulSeeds();
        assertEquals(1, seeds.size());
        assertTrue(seeds.getFirst().toString().contains("testbot.json"));
    }

    @Test
    void manifest_rejects_blank_name() {
        assertThrows(IllegalArgumentException.class, () ->
            new PakManifest("", "1.0", null, null, null, null,
                null, null, null, null, null));
    }

    @Test
    void manifest_rejects_null_version() {
        assertThrows(IllegalArgumentException.class, () ->
            new PakManifest("test", null, null, null, null, null,
                null, null, null, null, null));
    }

    @Test
    void list_returns_empty_when_no_extensions_dir() {
        var mgr = new PakManager(tempDir.resolve("nonexistent"));
        assertTrue(mgr.list().isEmpty());
    }
}
