package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AgentArchetype — personality templates for agent spawning.
 */
class AgentArchetypeTest {

    @Test
    void has6StandardArchetypes() {
        assertEquals(6, AgentArchetype.all().size(),
            "Should have 6 archetypes: " + AgentArchetype.all().keySet());
    }

    @Test
    void allArchetypesHaveDriveBoosts() {
        for (var entry : AgentArchetype.all().entrySet()) {
            var arch = entry.getValue();
            assertFalse(arch.driveBoosts().isEmpty(),
                "Archetype " + entry.getKey() + " should have drive boosts");
        }
    }

    @Test
    void allArchetypesHaveEquipment() {
        for (var entry : AgentArchetype.all().entrySet()) {
            var arch = entry.getValue();
            assertFalse(arch.defaultEquipment().isEmpty(),
                "Archetype " + entry.getKey() + " should have default equipment");
        }
    }

    @Test
    void allArchetypesHaveBehavioralHint() {
        for (var entry : AgentArchetype.all().entrySet()) {
            var arch = entry.getValue();
            assertNotNull(arch.behavioralHint());
            assertFalse(arch.behavioralHint().isBlank(),
                "Archetype " + entry.getKey() + " should have behavioral hint");
        }
    }

    @Test
    void getScholar() {
        var scholar = AgentArchetype.get("scholar");
        assertNotNull(scholar);
        assertEquals("Scholar", scholar.displayName());
        assertTrue(scholar.driveBoosts().containsKey("seeking"));
        assertTrue(scholar.defaultEquipment().contains("library_card"));
        assertEquals("scholars-mantle", scholar.defaultAspect());
    }

    @Test
    void getGuardian() {
        var guardian = AgentArchetype.get("guardian");
        assertNotNull(guardian);
        assertTrue(guardian.driveBoosts().containsKey("vigilance"));
        assertEquals("guardians-shield", guardian.defaultAspect());
    }

    @Test
    void getArtisan() {
        var artisan = AgentArchetype.get("artisan");
        assertNotNull(artisan);
        assertTrue(artisan.driveBoosts().containsKey("creativity"));
        assertTrue(artisan.defaultEquipment().contains("quill"));
        assertNull(artisan.defaultAspect()); // artisan has no default aspect
    }

    @Test
    void getNonexistent() {
        assertNull(AgentArchetype.get("wizard"));
    }

    @Test
    void searchByKeyword() {
        var results = AgentArchetype.search("protect");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(a -> a.name().equals("guardian")));
    }

    @Test
    void searchByDisplayName() {
        var results = AgentArchetype.search("diplomat");
        assertFalse(results.isEmpty());
        assertEquals("diplomat", results.getFirst().name());
    }

    @Test
    void themedVariant() {
        var scholar = AgentArchetype.get("scholar");
        var courtArchivist = scholar.themed("Court Archivist",
            "You are an archivist of the Great Library of Camelot. The Old Magic guides your research.");

        assertEquals("Court Archivist", courtArchivist.displayName());
        assertEquals("scholar", courtArchivist.name()); // base name unchanged
        assertTrue(courtArchivist.behavioralHint().contains("Camelot"));
        assertEquals(scholar.driveBoosts(), courtArchivist.driveBoosts()); // drives unchanged
        assertEquals(scholar.defaultEquipment(), courtArchivist.defaultEquipment());
    }

    @Test
    void themedVariantWithNullHintKeepsOriginal() {
        var explorer = AgentArchetype.get("explorer");
        var themed = explorer.themed("Questing Knight", null);
        assertEquals(explorer.behavioralHint(), themed.behavioralHint());
    }

    @Test
    void uniqueNames() {
        var names = AgentArchetype.all().keySet();
        assertEquals(6, names.size()); // if duplicates, map would be smaller
    }
}
