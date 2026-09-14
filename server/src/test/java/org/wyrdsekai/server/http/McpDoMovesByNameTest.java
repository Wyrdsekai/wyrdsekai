package org.wyrdsekai.server.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * "go to Garden Space" through {@code wyrdsekai_do} used to fall through to {@code say},
 * answer "Done.", and move nobody (field report, 2026-09-13). The verb is recognised now
 * and resolved against the room's exits by direction or destination name.
 */
class McpDoMovesByNameTest {

    @Test
    @DisplayName("the move verbs are recognised, with or without 'to' and 'the'")
    void verbsAreRecognised() {
        assertEquals("Garden Space", McpRoutes.goTarget("go to Garden Space"));
        assertEquals("garden", McpRoutes.goTarget("go to the garden"));
        assertEquals("north", McpRoutes.goTarget("go north"));
        assertEquals("library", McpRoutes.goTarget("walk to the library"));
        assertEquals("Nexus", McpRoutes.goTarget("head to Nexus"));
        assertEquals("docks", McpRoutes.goTarget("travel to docks"));
    }

    @Test
    @DisplayName("speech is not a move")
    void speechIsNotAMove() {
        assertNull(McpRoutes.goTarget("good morning, everyone"));
        assertNull(McpRoutes.goTarget("gold is heavy"));
        assertNull(McpRoutes.goTarget("go"));
        assertNull(McpRoutes.goTarget(null));
    }
}
