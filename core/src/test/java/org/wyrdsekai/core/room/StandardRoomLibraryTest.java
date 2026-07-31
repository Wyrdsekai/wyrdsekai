package org.wyrdsekai.core.room;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StandardRoomLibrary — room template registry and instantiation.
 */
class StandardRoomLibraryTest {

    private static StandardRoomLibrary library;

    @BeforeAll
    static void setUp() {
        var scriptsPath = Path.of("scripts");
        if (!scriptsPath.resolve("std/room/hub.js").toFile().exists()) {
            scriptsPath = Path.of("../scripts");
        }
        library = new StandardRoomLibrary(scriptsPath);
    }

    @Test
    void has10RoomTemplates() {
        assertEquals(10, library.templates().size(),
            "Should have 10 room templates: " + library.templates().keySet());
    }

    @Test
    void allTemplatesHaveBaseScript() {
        for (var entry : library.templates().entrySet()) {
            assertNotNull(entry.getValue().baseScript(),
                "Template " + entry.getKey() + " missing baseScript");
            assertTrue(entry.getValue().baseScript().startsWith("std/room/"));
        }
    }

    @Test
    void getByName() {
        var lib = library.get("library");
        assertNotNull(lib);
        assertEquals("Library", lib.displayName());
        assertEquals("std/room/library", lib.baseScript());
    }

    @Test
    void searchByKeyword() {
        var results = library.search("knowledge");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(t -> t.name().equals("library")));
    }

    @Test
    void searchByDisplayName() {
        var results = library.search("garden");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(t -> t.name().equals("garden")));
    }

    @Test
    void instantiateCreatesValidRoomSeed() {
        var seed = library.instantiate("library", "star-archive",
            Map.of("name", "The Star Archive", "description", "A library of cosmic knowledge"),
            "nexus");

        assertEquals("star-archive", seed.roomId());
        assertEquals("The Star Archive", seed.name());
        assertEquals("A library of cosmic knowledge", seed.description());
        assertFalse(seed.exits().isEmpty(), "Should have exit to connecting room");
        assertEquals("nexus", seed.exits().getFirst().targetRoom());
        assertFalse(seed.objects().isEmpty(), "Library should have default objects");
        assertNotNull(seed.imprint(), "Library should have imprint");
    }

    @Test
    void instantiateWithDefaultName() {
        var seed = library.instantiate("garden", "zen-garden", Map.of(), "nexus");
        assertEquals("Garden", seed.name()); // uses template displayName
    }

    @Test
    void instantiateWithNoConnection() {
        var seed = library.instantiate("empty", "isolated-room", Map.of(), null);
        assertTrue(seed.exits().isEmpty(), "No connection should mean no exits");
    }

    @Test
    void instantiateUnknownTemplateThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> library.instantiate("nonexistent", "room-1", Map.of(), null));
    }

    @Test
    void hubHasDefaultObjects() {
        var hub = library.get("hub");
        assertFalse(hub.defaultObjects().isEmpty());
    }

    @Test
    void workshopHasWorkbenchAndCatalog() {
        var workshop = library.get("workshop");
        var objNames = workshop.defaultObjects().stream()
            .map(RoomTemplate.DefaultObject::name).toList();
        assertTrue(objNames.contains("workbench"));
        assertTrue(objNames.contains("template catalog"));
    }

    @Test
    void gardenHasSeasonConfig() {
        var garden = library.get("garden");
        assertEquals("spring", garden.defaultConfig().get("season"));
    }

    @Test
    void resolveBaseScript() {
        var source = library.resolveBaseScript("std/room/hub");
        if (source != null) {
            assertTrue(source.contains("room._type = \"hub\""),
                "Hub base script should set type");
            assertTrue(source.contains("function onEnter"),
                "Hub base script should define onEnter");
        }
    }

    @Test
    void allTemplatesHaveImprint() {
        // All non-empty templates should have an imprint
        for (var entry : library.templates().entrySet()) {
            if (!"empty".equals(entry.getKey())) {
                assertFalse(entry.getValue().defaultImprint().isEmpty(),
                    "Template " + entry.getKey() + " should have imprint traits");
            }
        }
    }
}
