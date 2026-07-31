package org.wyrdsekai.common.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MUD-style alias resolution with ordinal disambiguation.
 */
class AliasResolverTest {

    // --- ParsedQuery ---

    @Test
    void parseQuery_noOrdinal() {
        var parsed = AliasResolver.parseQuery("sword");
        assertEquals(0, parsed.ordinal());
        assertEquals("sword", parsed.query());
    }

    @Test
    void parseQuery_withOrdinal() {
        var parsed = AliasResolver.parseQuery("2.sword");
        assertEquals(2, parsed.ordinal());
        assertEquals("sword", parsed.query());
    }

    @Test
    void parseQuery_ordinalOne() {
        var parsed = AliasResolver.parseQuery("1.guard");
        assertEquals(1, parsed.ordinal());
        assertEquals("guard", parsed.query());
    }

    @Test
    void parseQuery_zeroOrdinalTreatedAsNoOrdinal() {
        var parsed = AliasResolver.parseQuery("0.sword");
        assertEquals(0, parsed.ordinal());
        assertEquals("0.sword", parsed.query());
    }

    @Test
    void parseQuery_nullInput() {
        var parsed = AliasResolver.parseQuery(null);
        assertEquals(0, parsed.ordinal());
        assertEquals("", parsed.query());
    }

    @Test
    void parseQuery_blankInput() {
        var parsed = AliasResolver.parseQuery("  ");
        assertEquals(0, parsed.ordinal());
        assertEquals("", parsed.query());
    }

    @Test
    void parseQuery_multiWordWithOrdinal() {
        var parsed = AliasResolver.parseQuery("2.iron sword");
        assertEquals(2, parsed.ordinal());
        assertEquals("iron sword", parsed.query());
    }

    // --- Object resolution ---

    @Test
    void resolveObject_exactAliasMatch() {
        var sword = new RoomObject("sword-1", "iron sword", "A sword", true, true, true,
            List.of("sword", "iron sword", "blade"));
        var result = AliasResolver.resolveObject(List.of(sword), "blade");
        assertTrue(result.isPresent());
        assertEquals("sword-1", result.get().id());
    }

    @Test
    void resolveObject_nameMatchWhenNoAlias() {
        var key = new RoomObject("key-1", "brass key", "A key", true);
        var result = AliasResolver.resolveObject(List.of(key), "brass key");
        assertTrue(result.isPresent());
        assertEquals("key-1", result.get().id());
    }

    @Test
    void resolveObject_caseInsensitive() {
        var crystal = new RoomObject("crystal-1", "crystal", "Glowing", false, true, true,
            List.of("crystal", "gem"));
        var result = AliasResolver.resolveObject(List.of(crystal), "GEM");
        assertTrue(result.isPresent());
        assertEquals("crystal-1", result.get().id());
    }

    @Test
    void resolveObject_ordinalDisambiguation() {
        var sword1 = new RoomObject("sword-1", "iron sword", "Iron", true, true, true,
            List.of("sword", "iron sword"));
        var sword2 = new RoomObject("sword-2", "crystal sword", "Crystal", true, true, true,
            List.of("sword", "crystal sword"));

        // First sword
        var first = AliasResolver.resolveObject(List.of(sword1, sword2), "sword");
        assertTrue(first.isPresent());
        assertEquals("sword-1", first.get().id());

        // Second sword via ordinal
        var second = AliasResolver.resolveObject(List.of(sword1, sword2), "2.sword");
        assertTrue(second.isPresent());
        assertEquals("sword-2", second.get().id());

        // Third sword doesn't exist
        var third = AliasResolver.resolveObject(List.of(sword1, sword2), "3.sword");
        assertTrue(third.isEmpty());
    }

    @Test
    void resolveObject_specificAliasBeatsOrdinal() {
        var ironSword = new RoomObject("sword-1", "iron sword", "Iron", true, true, true,
            List.of("sword", "iron sword"));
        var crystalSword = new RoomObject("sword-2", "crystal sword", "Crystal", true, true, true,
            List.of("sword", "crystal sword"));

        // "iron sword" matches exact alias on sword-1
        var result = AliasResolver.resolveObject(List.of(ironSword, crystalSword), "iron sword");
        assertTrue(result.isPresent());
        assertEquals("sword-1", result.get().id());

        // "crystal sword" matches exact alias on sword-2
        var result2 = AliasResolver.resolveObject(List.of(ironSword, crystalSword), "crystal sword");
        assertTrue(result2.isPresent());
        assertEquals("sword-2", result2.get().id());
    }

    @Test
    void resolveObject_partialNameMatch() {
        var catalog = new RoomObject("lib-catalog", "card catalog", "A catalog", false);
        var result = AliasResolver.resolveObject(List.of(catalog), "catalog");
        assertTrue(result.isPresent());
        assertEquals("lib-catalog", result.get().id());
    }

    @Test
    void resolveObject_noMatch() {
        var sword = new RoomObject("sword-1", "iron sword", "A sword", true, true, true,
            List.of("sword"));
        var result = AliasResolver.resolveObject(List.of(sword), "shield");
        assertTrue(result.isEmpty());
    }

    // --- Entity resolution ---

    @Test
    void resolveEntity_byAlias() {
        var wyrd = new Entity("comp-1", "Wyrd", "agent", "A companion", null,
            List.of("wyrd", "companion"));
        var result = AliasResolver.resolveEntity(List.of(wyrd), "companion");
        assertTrue(result.isPresent());
        assertEquals("comp-1", result.get().id());
    }

    @Test
    void resolveEntity_ordinalWithMultipleGuards() {
        var guard1 = new Entity("guard-1", "Town Guard", "npc", "A guard", null,
            List.of("guard", "town guard"));
        var guard2 = new Entity("guard-2", "Town Guard", "npc", "Another guard", null,
            List.of("guard", "town guard"));

        var first = AliasResolver.resolveEntity(List.of(guard1, guard2), "guard");
        assertTrue(first.isPresent());
        assertEquals("guard-1", first.get().id());

        var second = AliasResolver.resolveEntity(List.of(guard1, guard2), "2.guard");
        assertTrue(second.isPresent());
        assertEquals("guard-2", second.get().id());
    }

    // --- Count matches ---

    @Test
    void countMatches_multipleSwords() {
        var sword1 = new RoomObject("s1", "iron sword", "Iron", true, true, true,
            List.of("sword", "iron sword"));
        var sword2 = new RoomObject("s2", "crystal sword", "Crystal", true, true, true,
            List.of("sword", "crystal sword"));
        var shield = new RoomObject("sh1", "wooden shield", "Wood", true, true, true,
            List.of("shield"));

        int count = AliasResolver.countMatches(List.of(sword1, sword2, shield), "sword",
            RoomObject::aliases, RoomObject::name);
        assertEquals(2, count);
    }

    // --- Backward compatibility ---

    @Test
    void resolveObject_worksWithEmptyAliases() {
        // Objects with no aliases (legacy data) should still match by name
        var key = new RoomObject("key-1", "rusty key", "Old", true);
        assertEquals(List.of(), key.aliases()); // verify empty
        var result = AliasResolver.resolveObject(List.of(key), "rusty key");
        assertTrue(result.isPresent());
    }

    @Test
    void resolveEntity_worksWithEmptyAliases() {
        var player = new Entity("player-1", "Alice", "player", "A player");
        assertEquals(List.of(), player.aliases());
        var result = AliasResolver.resolveEntity(List.of(player), "Alice");
        assertTrue(result.isPresent());
    }
}
