package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.item.StandardItemLibrary;
import org.wyrdsekai.core.item.ToolItemStarterKit;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for agent archetypes — verifies equipment resolution,
 * drive boosts, themed variants, and zone composition.
 */
class AgentArchetypeIntegrationTest {

    private static StandardItemLibrary itemLibrary;

    @BeforeAll
    static void setUp() {
        var scriptsPath = Path.of("scripts");
        if (!scriptsPath.resolve("std/book.js").toFile().exists()) {
            scriptsPath = Path.of("../scripts");
        }
        itemLibrary = new StandardItemLibrary(scriptsPath);
    }

    @Test
    void scholarEquipmentResolvesFromItemLibrary() {
        var scholar = AgentArchetype.get("scholar");
        assertNotNull(scholar);

        // Each equipment item should exist in the item library or starter kit
        for (var equipName : scholar.defaultEquipment()) {
            // Check item library templates
            var template = itemLibrary.get(equipName);
            // Also check starter kit by ID
            var starterMatch = ToolItemStarterKit.standard().stream()
                .anyMatch(t -> t.id().equals(equipName));

            assertTrue(template != null || starterMatch,
                "Scholar equipment '" + equipName + "' should exist in item library or starter kit");
        }
    }

    @Test
    void guardianEquipmentResolvesFromItemLibrary() {
        var guardian = AgentArchetype.get("guardian");
        for (var equipName : guardian.defaultEquipment()) {
            var template = itemLibrary.get(equipName);
            var starterMatch = ToolItemStarterKit.standard().stream()
                .anyMatch(t -> t.id().equals(equipName));
            assertTrue(template != null || starterMatch,
                "Guardian equipment '" + equipName + "' should exist: " + equipName);
        }
    }

    @Test
    void allArchetypeEquipmentResolvable() {
        for (var entry : AgentArchetype.all().entrySet()) {
            var arch = entry.getValue();
            for (var equipName : arch.defaultEquipment()) {
                var template = itemLibrary.get(equipName);
                var starterMatch = ToolItemStarterKit.standard().stream()
                    .anyMatch(t -> t.id().equals(equipName));
                assertTrue(template != null || starterMatch,
                    "Archetype " + entry.getKey() + " equipment '" + equipName
                        + "' not found in item library or starter kit");
            }
        }
    }

    @Test
    void scholarAspectResolvesFromItemLibrary() {
        var scholar = AgentArchetype.get("scholar");
        assertNotNull(scholar.defaultAspect());
        var aspect = itemLibrary.get(scholar.defaultAspect());
        assertNotNull(aspect, "Scholar's aspect '" + scholar.defaultAspect()
            + "' should exist in item library");
        assertEquals("aspect", aspect.category());
    }

    @Test
    void guardianAspectResolvesFromItemLibrary() {
        var guardian = AgentArchetype.get("guardian");
        assertNotNull(guardian.defaultAspect());
        var aspect = itemLibrary.get(guardian.defaultAspect());
        assertNotNull(aspect, "Guardian's aspect should exist in item library");
        assertEquals("aspect", aspect.category());
    }

    @Test
    void driveBoostsAreReasonable() {
        for (var entry : AgentArchetype.all().entrySet()) {
            var arch = entry.getValue();
            for (var boost : arch.driveBoosts().entrySet()) {
                assertTrue(boost.getValue() > 0.0 && boost.getValue() <= 1.0,
                    "Archetype " + entry.getKey() + " drive boost '"
                        + boost.getKey() + "' should be 0-1, got " + boost.getValue());
            }
        }
    }

    @Test
    void themedArthurianArchetypes() {
        var scholar = AgentArchetype.get("scholar");
        var guardian = AgentArchetype.get("guardian");
        var diplomat = AgentArchetype.get("diplomat");

        var merlin = scholar.themed("Court Archivist",
            "You are a keeper of the Great Library of Camelot. The Old Magic guides your research into the ancient texts.");
        var knightWatch = guardian.themed("Knight of the Watch",
            "You guard the Great Library of Camelot. The Old Magic sharpens your awareness of threats.");
        var guinevere = diplomat.themed("Lady of the Lake",
            "You represent the people of the realm in the Court of Camelot. Diplomacy is your weapon.");

        // Themed names
        assertEquals("Court Archivist", merlin.displayName());
        assertEquals("Knight of the Watch", knightWatch.displayName());
        assertEquals("Lady of the Lake", guinevere.displayName());

        // Base names unchanged (for resolution)
        assertEquals("scholar", merlin.name());
        assertEquals("guardian", knightWatch.name());
        assertEquals("diplomat", guinevere.name());

        // Drives unchanged
        assertEquals(scholar.driveBoosts(), merlin.driveBoosts());
        assertEquals(guardian.driveBoosts(), knightWatch.driveBoosts());

        // Equipment unchanged
        assertEquals(scholar.defaultEquipment(), merlin.defaultEquipment());

        // Behavioral hints themed
        assertTrue(merlin.behavioralHint().contains("Camelot"));
        assertTrue(knightWatch.behavioralHint().contains("Camelot"));
        assertTrue(guinevere.behavioralHint().contains("Camelot"));
    }

    @Test
    void cyberpunkThemedArchetypes() {
        var explorer = AgentArchetype.get("explorer");
        var artisan = AgentArchetype.get("artisan");

        var runner = explorer.themed("Netrunner",
            "You jack into the net and explore virtual spaces. Data is your territory.");
        var techie = artisan.themed("Techie",
            "You build and mod cyberware. Chrome is your canvas.");

        assertEquals("Netrunner", runner.displayName());
        assertEquals("Techie", techie.displayName());
        assertTrue(runner.behavioralHint().contains("net"));
        assertTrue(techie.behavioralHint().contains("cyberware"));
    }

    @Test
    void zoneCompositionThreeAgents() {
        // Simulate spawning 3 agents for a zone
        var archetypes = List.of(
            AgentArchetype.get("scholar").themed("Archivist", null),
            AgentArchetype.get("guardian").themed("Watchkeeper", null),
            AgentArchetype.get("steward").themed("Curator", null)
        );

        assertEquals(3, archetypes.size());

        // No duplicate equipment across agents
        var allEquipment = archetypes.stream()
            .flatMap(a -> a.defaultEquipment().stream())
            .toList();
        // Some overlap is OK (channel_stone appears in diplomat + steward)
        // but the archetypes should have different primary tools
        var primaryTools = archetypes.stream()
            .map(a -> a.defaultEquipment().getFirst())
            .distinct()
            .toList();
        assertEquals(3, primaryTools.size(), "Each agent should have a different primary tool");
    }
}
