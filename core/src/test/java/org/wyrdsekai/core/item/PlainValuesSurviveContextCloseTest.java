package org.wyrdsekai.core.item;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an item script hands the bridge must not still point at the script context once the
 * invocation is over.
 *
 * <p>Household node, 2026-09-12 14:43: {@code RoomEventReplicator -- Failed to publish event
 * for room …: The Context is already closed. (through reference chain:
 * WorldEvent$ScriptTriggered["context"])}. The script's {@code world.room.emit(type, data)}
 * put the live JS object into the event; the replicator serialized it later, after the
 * context had been closed.</p>
 */
class PlainValuesSurviveContextCloseTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("a copied JS object serializes after its context is closed; the live view does not")
    void copySurvivesTheClose() throws Exception {
        Map<?, ?> live;
        Object copied;
        var ctx = Context.newBuilder("js").allowAllAccess(true).build();
        try {
            var v = ctx.eval("js", """
                ({ summary: "The floor was grounded.", count: 3, ratio: 0.5,
                   tags: ["held", "quiet"], nested: { ok: true, none: null },
                   fn: function () { return 1; } })
                """);
            live = v.as(Map.class);
            copied = PlainValues.deepCopy(live);
        } finally {
            ctx.close();
        }
        var json = JSON.writeValueAsString(copied);
        assertTrue(json.contains("\"summary\":\"The floor was grounded.\""), json);
        assertTrue(json.contains("\"count\":3"), "whole numbers stay whole: " + json);
        assertTrue(json.contains("\"ratio\":0.5"), json);
        assertTrue(json.contains("\"tags\":[\"held\",\"quiet\"]"), json);
        assertTrue(json.contains("\"nested\":{\"ok\":true,\"none\":null}"), json);
        assertFalse(json.contains("fn"), "functions are not data: " + json);

        // The seam itself: the live view is dead once the context is closed.
        final var dead = live;
        assertThrows(Exception.class, () -> JSON.writeValueAsString(dead));
    }

    @Test
    @DisplayName("plain Java values pass through unchanged, and the copy is not the original")
    void plainValuesPassThrough() {
        var in = Map.of("a", List.of(1, 2, Map.of("b", "c")), "d", 2.0, "e", true);
        var out = PlainValues.deepCopy(in);
        assertEquals("c", ((Map<?, ?>) ((List<?>) out.get("a")).get(2)).get("b"));
        assertEquals(2L, out.get("d"), "an integral double is written as a whole number");
        assertEquals(true, out.get("e"));
        assertTrue(out.get("a") != in.get("a"), "lists are copied, not shared");
        assertEquals(Map.of(), PlainValues.deepCopy((Map<?, ?>) null));
        assertNull(PlainValues.deepCopy((Object) null));
    }
}
