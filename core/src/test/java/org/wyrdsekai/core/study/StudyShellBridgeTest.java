package org.wyrdsekai.core.study;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.InventoryService;
import org.wyrdsekai.core.test.TestDb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W1 — host-side handling of the Study's fs_mount/fs_unmount/take command
 * verbs (previously dropped into RoomActor's honest-narrate fallback while
 * the script claimed success).
 */
@Tag("integration")
class StudyShellBridgeTest {

    private static final String ROOM = "study-1";

    @TempDir Path tmp;

    private Path shelfDir;
    private String jdbcUrl;
    private String previousJdbcProp;

    @BeforeEach
    void setUp() throws Exception {
        shelfDir = Files.createDirectories(tmp.resolve("shelf"));
        Files.writeString(shelfDir.resolve("notes.txt"), "hello wyrd\n");
        StudyMountRegistry.install(new StudyMountRegistry(tmp.resolve("mounts.json")));
        jdbcUrl = TestDb.createInMemory();
        previousJdbcProp = System.getProperty("wyrdsekai.jdbc.url");
        System.setProperty("wyrdsekai.jdbc.url", jdbcUrl);
    }

    @AfterEach
    void tearDown() {
        StudyMountRegistry.install(null);
        if (previousJdbcProp == null) {
            System.clearProperty("wyrdsekai.jdbc.url");
        } else {
            System.setProperty("wyrdsekai.jdbc.url", previousJdbcProp);
        }
    }

    @Test
    void canHandle_covers_the_three_shelf_verbs_only() {
        assertTrue(StudyShellBridge.canHandle("fs_mount"));
        assertTrue(StudyShellBridge.canHandle("fs_unmount"));
        assertTrue(StudyShellBridge.canHandle("take"));
        assertFalse(StudyShellBridge.canHandle("deploy"));
        assertFalse(StudyShellBridge.canHandle(null));
    }

    @Test
    void fs_mount_records_in_registry_and_narrates() {
        var lines = StudyShellBridge.handle("fs_mount",
            Map.of("target", shelfDir.toString(), "label", "docs", "actor", "alice"), ROOM);
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains("docs"), lines.getFirst());
        assertEquals(shelfDir.toString(),
            StudyMountRegistry.get().mountsFor(ROOM).get("docs"));
    }

    @Test
    void fs_mount_bad_path_narrates_teaching_refusal() {
        var lines = StudyShellBridge.handle("fs_mount",
            Map.of("target", tmp.resolve("ghost").toString(), "label", "docs",
                "actor", "alice"), ROOM);
        assertTrue(lines.getFirst().contains("does not exist"), lines.getFirst());
        assertTrue(StudyMountRegistry.get().mountsFor(ROOM).isEmpty());
    }

    @Test
    void fs_unmount_removes_and_reports_unknown() {
        StudyMountRegistry.get().mount(ROOM, "docs", shelfDir.toString());
        var removed = StudyShellBridge.handle("fs_unmount",
            Map.of("target", "docs", "actor", "alice"), ROOM);
        assertTrue(removed.getFirst().contains("unmounted"), removed.getFirst());

        var missing = StudyShellBridge.handle("fs_unmount",
            Map.of("target", "docs", "actor", "alice"), ROOM);
        assertTrue(missing.getFirst().contains("No shelf named 'docs'"), missing.getFirst());
    }

    @Test
    void take_imports_file_content_into_inventory() {
        StudyMountRegistry.get().mount(ROOM, "docs", shelfDir.toString());
        var lines = StudyShellBridge.handle("take",
            Map.of("target", "docs/notes.txt", "actor", "alice"), ROOM);
        assertTrue(lines.getFirst().startsWith("Taken: notes.txt"), lines.getFirst());

        var inventory = new InventoryService(jdbcUrl);
        var items = inventory.listItems("alice");
        assertEquals(1, items.size());
        assertEquals("notes.txt", items.getFirst().objectName());
        assertTrue(items.getFirst().description().contains("hello wyrd"));
    }

    @Test
    void take_missing_file_is_not_a_false_success() {
        StudyMountRegistry.get().mount(ROOM, "docs", shelfDir.toString());
        var lines = StudyShellBridge.handle("take",
            Map.of("target", "docs/ghost.txt", "actor", "alice"), ROOM);
        assertTrue(lines.getFirst().contains("Nothing at"), lines.getFirst());
        assertTrue(new InventoryService(jdbcUrl).listItems("alice").isEmpty());
    }

    @Test
    void take_from_unmounted_shelf_teaches_mounting() {
        var lines = StudyShellBridge.handle("take",
            Map.of("target", "ghost/notes.txt", "actor", "alice"), ROOM);
        assertTrue(lines.getFirst().contains("mount"), lines.getFirst());
    }

    @Test
    void take_without_actor_refuses_honestly() {
        StudyMountRegistry.get().mount(ROOM, "docs", shelfDir.toString());
        var lines = StudyShellBridge.handle("take",
            Map.of("target", "docs/notes.txt"), ROOM);
        assertTrue(lines.getFirst().contains("no acting entity"), lines.getFirst());
    }
}
