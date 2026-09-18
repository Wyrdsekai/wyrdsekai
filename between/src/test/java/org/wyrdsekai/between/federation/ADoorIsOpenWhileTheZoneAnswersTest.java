package org.wyrdsekai.between.federation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A zone gives no liveness signal of its own, so the federation pings every active partner
 * once a minute with the agreement question it already answers. A reply, or any other word
 * from the partner, is the door open; silence is the door closing by the clock.
 */
class ADoorIsOpenWhileTheZoneAnswersTest {

    @Test
    @DisplayName("a ping reply marks the partner seen; a stranger's reply is ignored")
    void pingReply() {
        var doors = new ZoneDoors();
        var round = doors.nextRound(List.of("zone-b", "zone-c"));
        assertEquals(2, round.size());
        assertTrue(round.keySet().stream().allMatch(k -> k.startsWith("ping-")));
        var forB = round.entrySet().stream().filter(e -> e.getValue().equals("zone-b")).findFirst().orElseThrow().getKey();

        var t = Instant.parse("2026-09-17T08:00:00Z");
        assertTrue(doors.replied(forB, t));
        assertFalse(doors.replied(forB, t), "a duplicate reply is not ours any more");
        assertFalse(doors.replied("agreement-probe-xyz", t), "a reconcile probe's reply belongs to the probe");
        assertEquals(t, doors.lastSeen("zone-b"));
        assertNull(doors.lastSeen("zone-c"), "zone-c has not answered");
    }

    @Test
    @DisplayName("a door is not judged before it has been asked and given a fair chance to answer")
    void notBornNumb() {
        var doors = new ZoneDoors();
        var t0 = Instant.parse("2026-09-17T20:19:00Z");
        var chance = java.time.Duration.ofSeconds(150);
        assertFalse(doors.doors(List.of("zone-b"), Map.of()).get(0).knowable(t0, chance), "never asked: nothing to say yet");
        var id = doors.nextRound(List.of("zone-b"), t0).keySet().iterator().next();
        assertFalse(doors.doors(List.of("zone-b"), Map.of()).get(0).knowable(t0.plusSeconds(20), chance), "asked twenty seconds ago: too early to call it closed");
        assertTrue(doors.doors(List.of("zone-b"), Map.of()).get(0).knowable(t0.plusSeconds(151), chance), "silent through its fair chance: now it can be called closed");
        doors.replied(id, t0.plusSeconds(3));
        assertTrue(doors.doors(List.of("zone-b"), Map.of()).get(0).knowable(t0.plusSeconds(4), chance), "it answered: open, and known at once");
    }

    @Test
    @DisplayName("the next round forgets last round's unanswered pings")
    void unansweredPingsDie() {
        var doors = new ZoneDoors();
        var first = doors.nextRound(List.of("zone-b")).keySet().iterator().next();
        doors.nextRound(List.of("zone-b"));
        assertFalse(doors.replied(first, Instant.now()), "a late reply to an old ping is not evidence now");
    }

    @Test
    @DisplayName("a partner that asks us something is heard from too, and the doors carry the manifest's name")
    void heardFromAndNamed() {
        var doors = new ZoneDoors();
        var t = Instant.parse("2026-09-17T08:05:00Z");
        doors.heardFrom("zone-c", t);
        doors.heardFrom("", t);
        var list = doors.doors(List.of("zone-b", "zone-c"), Map.of("zone-c", "The Orchard"));
        assertEquals(2, list.size());
        assertEquals("zone-b", list.get(0).zoneName(), "no manifest: the id is the name");
        assertNull(list.get(0).lastSeen());
        assertEquals("The Orchard", list.get(1).zoneName());
        assertEquals(t, list.get(1).lastSeen());
    }
}
