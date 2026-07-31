package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryCompactorTest {

    @Test
    void under_budget_unchanged() {
        var buffer = "10:00 go_to_room Library\n10:01 library_search for dragons";
        var result = MemoryCompactor.compact(buffer, 10000);
        assertEquals(buffer, result);
    }

    @Test
    void null_and_blank_unchanged() {
        assertNull(MemoryCompactor.compact(null, 100));
        assertEquals("", MemoryCompactor.compact("", 100));
        assertEquals("  ", MemoryCompactor.compact("  ", 100));
    }

    @Test
    void deduplicates_same_action_type() {
        var entries = List.of(
            "10:00 go_to_room Library",
            "10:01 library_search for dragons",
            "10:02 go_to_room Forge",
            "10:03 library_search for fire"
        );
        var result = MemoryCompactor.deduplicateByAction(new ArrayList<>(entries));
        assertEquals(2, result.size());
        assertTrue(result.get(0).contains("Forge"), "Should keep last go_to_room");
        assertTrue(result.get(1).contains("fire"), "Should keep last library_search");
    }

    @Test
    void preserves_non_action_entries() {
        var entries = List.of(
            "10:00 Started a new task",
            "10:01 go_to_room Library",
            "10:02 go_to_room Forge"
        );
        var result = MemoryCompactor.deduplicateByAction(new ArrayList<>(entries));
        assertEquals(2, result.size());
        assertTrue(result.get(0).contains("Started a new task"));
        assertTrue(result.get(1).contains("Forge"));
    }

    @Test
    void drops_low_importance_entries() {
        var entries = new ArrayList<>(List.of(
            "10:00 looked around the room",
            "10:01 went somewhere nice",
            "10:02 told player about the book found",
            "10:03 navigated east",
            "10:04 checked something",
            "10:05 important commitment made",
            "10:06 recent entry 1",
            "10:07 recent entry 2",
            "10:08 recent entry 3",
            "10:09 recent entry 4",
            "10:10 recent entry 5"
        ));
        var result = MemoryCompactor.dropLowImportance(entries);
        // Should drop low-importance from beginning, keep high-importance + last 5
        assertTrue(result.size() < entries.size());
        // High-importance entries preserved
        assertTrue(result.stream().anyMatch(e -> e.contains("told")));
        assertTrue(result.stream().anyMatch(e -> e.contains("commitment")));
    }

    @Test
    void high_importance_keywords_detected() {
        assertTrue(MemoryCompactor.isHighImportance("Found an interesting book about dragons"));
        assertTrue(MemoryCompactor.isHighImportance("Made a commitment to search"));
        assertTrue(MemoryCompactor.isHighImportance("Told player about results"));
        assertTrue(MemoryCompactor.isHighImportance("Error: inference failed"));

        assertFalse(MemoryCompactor.isHighImportance("Looked around the room"));
        assertFalse(MemoryCompactor.isHighImportance("Went somewhere nice"));
    }

    @Test
    void hard_truncate_fits_budget() {
        var entries = List.of(
            "A".repeat(100), // 25 tokens
            "B".repeat(100), // 25 tokens
            "C".repeat(100)  // 25 tokens
        );
        // Budget of 30 tokens should only keep last entry
        var result = MemoryCompactor.hardTruncate(new ArrayList<>(entries), 30);
        assertTrue(result.startsWith("C"));
    }

    @Test
    void extract_action_type_works() {
        assertEquals("go_to_room", MemoryCompactor.extractActionType("10:00 go_to_room Library"));
        assertEquals("library_search", MemoryCompactor.extractActionType("10:01 library_search for dragons"));
        assertEquals("web_search", MemoryCompactor.extractActionType("10:02 web_search for news"));
        assertNull(MemoryCompactor.extractActionType("10:03 looked around"));
    }
}
