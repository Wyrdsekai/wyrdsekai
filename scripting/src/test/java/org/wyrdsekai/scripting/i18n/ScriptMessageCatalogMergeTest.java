package org.wyrdsekai.scripting.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScriptMessageCatalogMergeTest {

    @TempDir Path tempDir;

    @AfterEach
    void tearDown() {
        ScriptMessageCatalog.clearCaches();
    }

    @Test
    void mergeFromFile_adds_new_keys() throws Exception {
        var catalog = ScriptMessageCatalog.ofMap("en", new HashMap<>(Map.of(
            "core.greeting", "Hello"
        )));

        var extensionFile = tempDir.resolve("ext-en.json");
        Files.writeString(extensionFile, """
            {"ext.farewell": "Goodbye", "ext.thanks": "Thank you"}
            """);

        catalog.mergeFromFile(extensionFile);

        assertEquals("Goodbye", catalog.get("ext.farewell"));
        assertEquals("Thank you", catalog.get("ext.thanks"));
    }

    @Test
    void mergeFromFile_does_not_overwrite_existing_keys() throws Exception {
        var catalog = ScriptMessageCatalog.ofMap("en", new HashMap<>(Map.of(
            "shared.key", "Original value"
        )));

        var extensionFile = tempDir.resolve("ext-en.json");
        Files.writeString(extensionFile, """
            {"shared.key": "Extension value", "ext.new": "New"}
            """);

        catalog.mergeFromFile(extensionFile);

        // Original preserved
        assertEquals("Original value", catalog.get("shared.key"));
        // New key added
        assertEquals("New", catalog.get("ext.new"));
    }

    @Test
    void mergeFromFile_handles_missing_file_gracefully() {
        var catalog = ScriptMessageCatalog.ofMap("en", new HashMap<>(Map.of(
            "key", "value"
        )));

        // Should not throw
        catalog.mergeFromFile(tempDir.resolve("nonexistent.json"));
        assertEquals("value", catalog.get("key"));
    }

    @Test
    void mergeFromFile_handles_empty_json() throws Exception {
        var catalog = ScriptMessageCatalog.ofMap("en", new HashMap<>(Map.of(
            "key", "value"
        )));

        var emptyFile = tempDir.resolve("empty.json");
        Files.writeString(emptyFile, "{}");

        catalog.mergeFromFile(emptyFile);
        assertEquals(1, catalog.size());
    }

    @Test
    void merge_multiple_extension_files() throws Exception {
        var catalog = ScriptMessageCatalog.ofMap("en", new HashMap<>(Map.of(
            "core.key", "core"
        )));

        var ext1 = tempDir.resolve("ext1.json");
        Files.writeString(ext1, "{\"ext1.key\": \"from ext1\"}");
        var ext2 = tempDir.resolve("ext2.json");
        Files.writeString(ext2, "{\"ext2.key\": \"from ext2\"}");

        catalog.mergeFromFile(ext1);
        catalog.mergeFromFile(ext2);

        assertEquals("core", catalog.get("core.key"));
        assertEquals("from ext1", catalog.get("ext1.key"));
        assertEquals("from ext2", catalog.get("ext2.key"));
        assertEquals(3, catalog.size());
    }
}
