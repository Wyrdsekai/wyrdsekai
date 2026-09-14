package org.wyrdsekai.server.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An MCP login's door is chosen by what it is and what it holds: a resident lands in their
 * Study, a vouched visitor at the Nexus, a stranger at the Docks, an anonymous guest in the
 * Nexus as before. The rule is a function so it can be read in one place (2026-09-13).
 */
class McpDoorTest {

    @Test
    @DisplayName("resident → Study; vouched visitor → Nexus; stranger → Docks; anon → Nexus")
    void doors() {
        assertEquals("study-u1", McpRoutes.doorFor(false, true, false, "study-u1"));
        assertEquals("study-u1", McpRoutes.doorFor(false, true, true, "study-u1"), "a resident is home whatever else they hold");
        assertEquals("nexus", McpRoutes.doorFor(false, false, true, "study-u2"));
        assertEquals("docks", McpRoutes.doorFor(false, false, false, "study-u2"));
        assertEquals("nexus", McpRoutes.doorFor(true, false, false, null));
    }

    @Test
    @DisplayName("during quiet hours a visitor may still look and leave, not speak")
    void silentVerbs() {
        assertTrue(McpRoutes.isSilentVerb("look"));
        assertTrue(McpRoutes.isSilentVerb("examine the stone"));
        assertTrue(McpRoutes.isSilentVerb("go to the docks"));
        assertFalse(McpRoutes.isSilentVerb("say good night"));
        assertFalse(McpRoutes.isSilentVerb("hello everyone"));
        assertFalse(McpRoutes.isSilentVerb(null));
    }

    @Test
    @DisplayName("the vouching grant has one name")
    void grantName() {
        assertEquals("home://household/mcp-door", McpRoutes.MCP_DOOR.toString());
    }
}
