package org.wyrdsekai.core.study;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W1 — persisted host-side mount table for the Study's shelves.
 */
class StudyMountRegistryTest {

    @TempDir Path tmp;

    private Path store;
    private Path shelfDir;
    private StudyMountRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        store = tmp.resolve("study-mounts.json");
        shelfDir = Files.createDirectories(tmp.resolve("shelf"));
        Files.writeString(shelfDir.resolve("notes.txt"), "hello wyrd\nsecond line\n");
        registry = new StudyMountRegistry(store);
    }

    @Test
    void mount_records_and_resolves() {
        registry.mount("study-1", "docs", shelfDir.toString());
        assertEquals(shelfDir.toString(), registry.mountsFor("study-1").get("docs"));
        assertTrue(registry.resolveRoot("study-1", "docs").isPresent());
    }

    @Test
    void mounts_persist_across_instances() {
        registry.mount("study-1", "docs", shelfDir.toString());
        var reloaded = new StudyMountRegistry(store);
        assertEquals(shelfDir.toString(), reloaded.mountsFor("study-1").get("docs"));
    }

    @Test
    void mounts_are_per_room() {
        registry.mount("study-1", "docs", shelfDir.toString());
        assertTrue(registry.mountsFor("study-2").isEmpty());
        assertTrue(registry.resolveRoot("study-2", "docs").isEmpty());
    }

    @Test
    void mount_refuses_missing_path_with_teaching_error() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> registry.mount("study-1", "docs", tmp.resolve("nope").toString()));
        assertTrue(ex.getMessage().contains("does not exist"), ex.getMessage());
    }

    @Test
    void mount_refuses_file_path_and_suggests_parent() throws Exception {
        var file = shelfDir.resolve("notes.txt");
        var ex = assertThrows(IllegalArgumentException.class,
            () -> registry.mount("study-1", "docs", file.toString()));
        assertTrue(ex.getMessage().contains("not a directory"), ex.getMessage());
    }

    @Test
    void mount_refuses_bad_label() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> registry.mount("study-1", "no/slashes", shelfDir.toString()));
        assertTrue(ex.getMessage().contains("shelf labels"), ex.getMessage());
    }

    @Test
    void unmount_removes_and_reports_missing() {
        registry.mount("study-1", "docs", shelfDir.toString());
        assertTrue(registry.unmount("study-1", "docs"));
        assertFalse(registry.unmount("study-1", "docs"));
        assertTrue(registry.mountsFor("study-1").isEmpty());
    }

    @Test
    void resolve_walks_into_shelf() {
        registry.mount("study-1", "docs", shelfDir.toString());
        var resolved = registry.resolve("study-1", "docs/notes.txt");
        assertEquals("docs", resolved.label());
        assertEquals("notes.txt", resolved.relPath());
    }

    @Test
    void resolve_refuses_traversal_out_of_shelf() {
        registry.mount("study-1", "docs", shelfDir.toString());
        var resolved = registry.resolve("study-1", "docs/../secret.txt");
        // The SandboxedFs inside refuses '..' at use time.
        var ex = assertThrows(IllegalArgumentException.class,
            () -> resolved.fs().resolve(resolved.relPath()));
        assertTrue(ex.getMessage().contains(".."), ex.getMessage());
    }

    @Test
    void resolve_teaches_about_host_paths() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> registry.resolve("study-1", "~/secrets.txt"));
        assertTrue(ex.getMessage().contains("mount"), ex.getMessage());
    }

    @Test
    void resolve_names_mounted_shelves_on_unknown_label() {
        registry.mount("study-1", "docs", shelfDir.toString());
        var ex = assertThrows(IllegalArgumentException.class,
            () -> registry.resolve("study-1", "nope/file.txt"));
        assertTrue(ex.getMessage().contains("docs"), ex.getMessage());
    }

    @Test
    void corrupt_store_starts_empty_instead_of_crashing() throws Exception {
        Files.writeString(store, "{not json");
        var reloaded = new StudyMountRegistry(store);
        assertTrue(reloaded.mountsFor("study-1").isEmpty());
        // and it can still record new mounts afterwards
        reloaded.mount("study-1", "docs", shelfDir.toString());
        assertEquals(1, reloaded.mountsFor("study-1").size());
    }
}
