package org.wyrdsekai.core.room;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for room template creation and structure validation.
 * Verifies that instantiated rooms have correct exits, objects, imprints,
 * and are ready for ZoneGuardian registration.
 */
class RoomTemplateIntegrationTest {

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
    void createLibraryWithConnection() {
        var seed = library.instantiate("library", "star-archive",
            Map.of("name", "The Star Archive", "description", "A library of cosmic knowledge"),
            "nexus");

        assertEquals("star-archive", seed.roomId());
        assertEquals("The Star Archive", seed.name());
        assertEquals("A library of cosmic knowledge", seed.description());

        // Exit back to nexus
        assertEquals(1, seed.exits().size());
        assertEquals("out", seed.exits().getFirst().direction());
        assertEquals("nexus", seed.exits().getFirst().targetRoom());

        // Default objects from template
        assertTrue(seed.objects().size() >= 2, "Library should have catalog + reading desk");
        var objNames = seed.objects().stream().map(o -> o.name()).toList();
        assertTrue(objNames.contains("card catalog"), "Should have card catalog");
        assertTrue(objNames.contains("reading desk"), "Should have reading desk");

        // All objects visible
        assertTrue(seed.objects().stream().allMatch(o -> o.visible()));

        // Imprint
        assertNotNull(seed.imprint(), "Library should have imprint");
        assertTrue(seed.imprint().traits().containsKey("curiosity"));
    }

    @Test
    void createGardenWithSeasonConfig() {
        var seed = library.instantiate("garden", "zen-garden",
            Map.of("name", "Zen Garden", "description", "A tranquil rock garden"),
            "nexus");

        assertEquals("Zen Garden", seed.name());
        assertNotNull(seed.imprint());
        assertTrue(seed.imprint().traits().containsKey("calm"));

        // Garden has bench and fountain
        var objNames = seed.objects().stream().map(o -> o.name()).toList();
        assertTrue(objNames.contains("stone bench"));
        assertTrue(objNames.contains("fountain"));
    }

    @Test
    void createWorkshopWithCraftingObjects() {
        var seed = library.instantiate("workshop", "merlins-workshop",
            Map.of("name", "Merlin's Workshop", "description", "Where enchanted artifacts are forged and mended"),
            "the-stables");

        var objNames = seed.objects().stream().map(o -> o.name()).toList();
        assertTrue(objNames.contains("workbench"), "Workshop needs workbench");
        assertTrue(objNames.contains("template catalog"), "Workshop needs catalog");
        assertTrue(objNames.contains("blueprint rack"), "Workshop needs blueprints");

        // Imprint should promote creativity
        assertTrue(seed.imprint().traits().containsKey("creativity"));
    }

    @Test
    void createHallForGovernance() {
        var seed = library.instantiate("hall", "court-of-camelot",
            Map.of("name", "The Court of Camelot",
                   "description", "A grand hall where knights and lords debate the fate of the realm"),
            "nexus");

        assertEquals("The Court of Camelot", seed.name());
        var objNames = seed.objects().stream().map(o -> o.name()).toList();
        assertTrue(objNames.contains("speaker platform"));
        assertTrue(objNames.contains("agenda board"));
    }

    @Test
    void createGateWithSecurity() {
        var seed = library.instantiate("gate", "temple-gate",
            Map.of("name", "Temple Gate"), "nexus");

        var objNames = seed.objects().stream().map(o -> o.name()).toList();
        assertTrue(objNames.contains("warden post"));
        assertTrue(seed.imprint().traits().containsKey("vigilance"));
    }

    @Test
    void createObservatory() {
        var seed = library.instantiate("observatory", "watchtower",
            Map.of("name", "The Watchtower"), "nexus");

        var objNames = seed.objects().stream().map(o -> o.name()).toList();
        assertTrue(objNames.contains("observation lens"));
        assertTrue(objNames.contains("pattern board"));
        assertTrue(seed.imprint().traits().containsKey("curiosity"));
        assertTrue(seed.imprint().traits().containsKey("focus"));
    }

    @Test
    void createHubAsZoneCenter() {
        var seed = library.instantiate("hub", "round-table-hall",
            Map.of("name", "The Round Table Hall",
                   "description", "Where knights gather to share tales and forge alliances. Also great mead."),
            null); // no connection — it IS the center

        assertEquals("The Round Table Hall", seed.name());
        assertTrue(seed.exits().isEmpty(), "Hub with null connection has no exits");
        assertTrue(seed.imprint().traits().containsKey("social"));
    }

    @Test
    void createStudyPrivateRoom() {
        var seed = library.instantiate("study", "study-merlin",
            Map.of("name", "Merlin's Quarters",
                   "description", "An austere chamber with a meditation circle and a single shelf of ancient texts"),
            "tower");

        var objNames = seed.objects().stream().map(o -> o.name()).toList();
        assertTrue(objNames.contains("desk"));
        assertTrue(objNames.contains("journal"));
        assertTrue(objNames.contains("shelves"));
        assertTrue(objNames.contains("dashboard crystal"));
    }

    @Test
    void createEmptyRoom() {
        var seed = library.instantiate("empty", "blank-room",
            Map.of("name", "The Void"), "nexus");

        assertEquals("The Void", seed.name());
        assertTrue(seed.objects().isEmpty(), "Empty template has no objects");
        assertNull(seed.imprint(), "Empty template has no imprint");
    }

    @Test
    void createMarketRoom() {
        var seed = library.instantiate("market", "trading-post",
            Map.of("name", "Trading Post"), "nexus");

        var objNames = seed.objects().stream().map(o -> o.name()).toList();
        assertTrue(objNames.contains("market board"));
        assertTrue(objNames.contains("merchant stall"));
        assertTrue(seed.imprint().traits().containsKey("social"));
    }

    @Test
    void arthurianZoneRooms() {
        // Simulate creating an Arthurian themed zone — 5 rooms
        var rooms = Map.of(
            "library", Map.of("name", "The Great Library of Camelot", "description", "Ancient knowledge of the Old Magic"),
            "hub", Map.of("name", "The Round Table Hall", "description", "Where knights gather to forge alliances"),
            "hall", Map.of("name", "The Court of Camelot", "description", "Where chivalry meets governance"),
            "workshop", Map.of("name", "Merlin's Workshop", "description", "Enchanted artifacts line the walls"),
            "garden", Map.of("name", "The Tournament Grounds", "description", "Verdant fields. Strong in the Old Magic.")
        );

        var hallSeed = library.instantiate("hub", "round-table", rooms.get("hub"), null);
        var archiveSeed = library.instantiate("library", "archives", rooms.get("library"), "round-table");
        var courtSeed = library.instantiate("hall", "court", rooms.get("hall"), "round-table");
        var workshopSeed = library.instantiate("workshop", "merlins-workshop", rooms.get("workshop"), "round-table");
        var groundsSeed = library.instantiate("garden", "tournament-grounds", rooms.get("garden"), "round-table");

        // Verify all 5 rooms created with themed names
        assertEquals("The Round Table Hall", hallSeed.name());
        assertEquals("The Great Library of Camelot", archiveSeed.name());
        assertEquals("The Court of Camelot", courtSeed.name());
        assertEquals("Merlin's Workshop", workshopSeed.name());
        assertEquals("The Tournament Grounds", groundsSeed.name());

        // All non-hub rooms connect back to round-table
        assertEquals("round-table", archiveSeed.exits().getFirst().targetRoom());
        assertEquals("round-table", courtSeed.exits().getFirst().targetRoom());
        assertEquals("round-table", workshopSeed.exits().getFirst().targetRoom());
        assertEquals("round-table", groundsSeed.exits().getFirst().targetRoom());

        // Each room has appropriate objects from its template
        assertTrue(archiveSeed.objects().stream().anyMatch(o -> o.name().equals("card catalog")));
        assertTrue(workshopSeed.objects().stream().anyMatch(o -> o.name().equals("workbench")));
        assertTrue(courtSeed.objects().stream().anyMatch(o -> o.name().equals("speaker platform")));
        assertTrue(groundsSeed.objects().stream().anyMatch(o -> o.name().equals("stone bench")));
    }
}
