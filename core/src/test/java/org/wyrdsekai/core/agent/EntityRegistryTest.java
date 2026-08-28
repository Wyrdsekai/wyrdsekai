package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EntityRegistryTest {

    private EntityRegistry registry;

    @BeforeEach
    void setup() {
        registry = new EntityRegistry();
    }

    @Test
    void enterAndLookup() {
        registry.enter("agent-1", "Ma", "agent", "nexus");
        assertEquals("nexus", registry.roomOf("agent-1").orElse(null));
        assertEquals("Ma", registry.nameOf("agent-1").orElse(null));
        assertTrue(registry.isAgent("agent-1"));
        assertTrue(registry.isOnline("agent-1"));
    }

    @Test
    void findByName() {
        registry.enter("agent-1", "Ma", "agent", "nexus");
        assertEquals("agent-1", registry.findByName("Ma").orElse(null));
        assertEquals("agent-1", registry.findByName("ma").orElse(null)); // case insensitive
        assertTrue(registry.findByName("Kai").isEmpty());
    }

    @Test
    void moveUpdatesRoom() {
        registry.enter("agent-1", "Ma", "agent", "nexus");
        registry.moved("agent-1", "bridge");
        assertEquals("bridge", registry.roomOf("agent-1").orElse(null));
    }

    @Test
    void removeEntity() {
        registry.enter("agent-1", "Ma", "agent", "nexus");
        registry.remove("agent-1");
        assertFalse(registry.isOnline("agent-1"));
        assertTrue(registry.findByName("Ma").isEmpty());
    }

    @Test
    void multipleEntities() {
        registry.enter("agent-1", "Ma", "agent", "nexus");
        registry.enter("agent-2", "Kai", "agent", "boiler-room");
        registry.enter("player-1", "Operator", "player", "nexus");

        assertEquals(3, registry.count());
        assertFalse(registry.isAgent("player-1"));
        assertTrue(registry.isAgent("agent-2"));
        assertEquals("boiler-room", registry.roomOf("agent-2").orElse(null));
    }

    @Test
    void leaveRemovesRoom() {
        registry.enter("agent-1", "Ma", "agent", "nexus");
        registry.leave("agent-1");
        assertTrue(registry.roomOf("agent-1").isEmpty());
        // Name still resolvable after leave (entity exists, just not in a room)
        assertEquals("agent-1", registry.findByName("Ma").orElse(null));
    }

    @Test
    void findByName_fuzzy_query_contains_registered() {
        registry.enter("companion-wyrd", "Wyrd", "agent", "nexus");
        // LLM generates "the Wyrd" — fuzzy should match
        assertEquals("companion-wyrd", registry.findByName("the Wyrd").orElse(null));
        assertEquals("companion-wyrd", registry.findByName("tell Wyrd hello").orElse(null));
    }

    @Test
    void findByName_fuzzy_registered_contains_query() {
        registry.enter("agent-1", "Ember the Explorer", "agent", "nexus");
        // Searching for just "Ember" should match
        assertEquals("agent-1", registry.findByName("Ember").orElse(null));
    }

    @Test
    void findByName_exact_takes_priority_over_fuzzy() {
        registry.enter("agent-1", "Ma", "agent", "nexus");
        registry.enter("agent-2", "Operator", "player", "nexus");
        // Exact match "Ma" should return agent-1, not fuzzy match on "Operator"
        assertEquals("agent-1", registry.findByName("Ma").orElse(null));
        assertEquals("agent-2", registry.findByName("Operator").orElse(null));
    }

    @Test
    void findByName_null_and_blank() {
        registry.enter("agent-1", "Wyrd", "agent", "nexus");
        assertTrue(registry.findByName(null).isEmpty());
        assertTrue(registry.findByName("").isEmpty());
        assertTrue(registry.findByName("   ").isEmpty());
    }

    // --- Alias support ---

    @Test
    void findByAlias_exactMatch() {
        registry.enter("companion-wyrd", "Wyrd", "agent", "nexus",
            List.of("wyrd", "companion"));
        assertEquals("companion-wyrd", registry.findByName("companion").orElse(null));
        assertEquals("companion-wyrd", registry.findByName("wyrd").orElse(null));
    }

    @Test
    void findByAlias_caseInsensitive() {
        registry.enter("companion-wyrd", "Wyrd", "agent", "nexus",
            List.of("wyrd", "companion"));
        assertEquals("companion-wyrd", registry.findByName("COMPANION").orElse(null));
        assertEquals("companion-wyrd", registry.findByName("Wyrd").orElse(null));
    }

    @Test
    void findByAlias_ordinalDisambiguation() {
        registry.enter("guard-1", "Town Guard", "npc", "nexus",
            List.of("guard", "town guard"));
        registry.enter("guard-2", "Town Guard", "npc", "nexus",
            List.of("guard", "town guard"));

        // First guard (no ordinal)
        assertEquals("guard-1", registry.findByName("guard").orElse(null));

        // Second guard via ordinal
        assertEquals("guard-2", registry.findByName("2.guard").orElse(null));
    }

    @Test
    void removeEntityCleansUpAliases() {
        registry.enter("companion-wyrd", "Wyrd", "agent", "nexus",
            List.of("wyrd", "companion"));
        assertEquals("companion-wyrd", registry.findByName("companion").orElse(null));

        registry.remove("companion-wyrd");
        assertTrue(registry.findByName("companion").isEmpty());
    }

    @Test
    void aliasAndNameCoexist() {
        // Entity has alias "comp" and name "Wyrd"
        registry.enter("comp-1", "Wyrd", "agent", "nexus",
            List.of("comp"));
        // Both should resolve
        assertEquals("comp-1", registry.findByName("comp").orElse(null));
        assertEquals("comp-1", registry.findByName("Wyrd").orElse(null));
    }

    @Test
    void aliasPreferredOverFuzzyName() {
        registry.enter("guard-1", "Palace Guard Captain", "npc", "nexus",
            List.of("guard", "captain"));
        // "guard" should match via alias, not fuzzy name
        assertEquals("guard-1", registry.findByName("guard").orElse(null));
        assertEquals("guard-1", registry.findByName("captain").orElse(null));
    }
}
