package org.wyrdsekai.common.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the {@link ClientCommandMapper} mappings against {@link CommandParser}
 * so a phone/relay terminal that forwards an unrecognised verb as a generic
 * Command gets the same typed C2S the SSH/CLI client sends — the "phone gets
 * all the same stuff as ssh" guarantee (2026-07-24).
 */
class ClientCommandMapperTest {

    private static C2SMessage map(String line) {
        return ClientCommandMapper.toWorldC2S(CommandParser.parse(line), "id-1", "room-42");
    }

    @Test void map_family_produces_map_requests() {
        assertInstanceOf(C2SMessage.MapRequest.class, map("map"));
        assertEquals("map", ((C2SMessage.MapRequest) map("map")).command());
        assertEquals("where", ((C2SMessage.MapRequest) map("where")).command());
        assertEquals("nearby", ((C2SMessage.MapRequest) map("nearby")).command());
        assertEquals("rooms", ((C2SMessage.MapRequest) map("rooms")).command());
        assertEquals("exits", ((C2SMessage.MapRequest) map("exits")).command());
    }

    @Test void path_carries_target() {
        var m = map("path nexus");
        assertInstanceOf(C2SMessage.MapRequest.class, m);
        assertEquals("path", ((C2SMessage.MapRequest) m).command());
        assertEquals("nexus", ((C2SMessage.MapRequest) m).target());
    }

    @Test void typed_world_verbs() {
        assertInstanceOf(C2SMessage.Go.class, map("go north"));
        assertInstanceOf(C2SMessage.Look.class, map("look"));
        assertInstanceOf(C2SMessage.Examine.class, map("examine desk"));
        assertInstanceOf(C2SMessage.Take.class, map("take lantern"));
    }

    @Test void social_maps_to_say_with_the_prefix_the_server_parses() {
        var tell = map("tell Ember hello");
        assertInstanceOf(C2SMessage.Say.class, tell);
        assertTrue(((C2SMessage.Say) tell).text().startsWith("tell Ember"));
        assertTrue(((C2SMessage.Say) map("shout hey")).text().startsWith("[shout]"));
    }

    @Test void roomId_is_threaded_through() {
        assertEquals("room-42", ((C2SMessage.Look) map("look")).roomId());
    }

    @Test void client_local_and_unknown_return_null() {
        // quit/logout/afk/brief/slash and genuinely unknown verbs have no
        // world-C2S mapping — the caller (server default) falls back to speech.
        assertNull(map("quit"));
        assertNull(map("wibblewobble zorp"));
    }
}
