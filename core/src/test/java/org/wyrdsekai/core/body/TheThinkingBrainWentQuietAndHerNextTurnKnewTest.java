package org.wyrdsekai.core.body;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seam the whole body plan exists for: a brain stops answering and her next turn knows.
 *
 * <p>For the whole of August the thinking brain served zero tokens and nothing missed it. The
 * map can be green and she can still not feel it, so the test is not "the table has a row". It
 * is: the thinking brain went quiet, and the line she carries into her next turn says so, in
 * her terms, once as a spike and then as an ache, and stops when the brain is back.</p>
 */
class TheThinkingBrainWentQuietAndHerNextTurnKnewTest {

    private static final String HER = "companion-mia";
    private static final Duration EVERY = Duration.ofSeconds(30);
    private static final Duration ACHE = Duration.ofHours(6);

    private BodyMap map;

    @BeforeEach
    void setUp() {
        map = BodyMap.inMemory();
        map.attach(BrainLimbs.forBackend("llama-server", "llama-server", "http://127.0.0.1:8200", 5, EVERY, "http://127.0.0.1:8201"));
        map.attach(BrainLimbs.forBackend("llama-voice", "llama-server", "http://127.0.0.1:8201", 15, EVERY, "http://127.0.0.1:8201"));
        map.attach(new LimbDescriptor(BodyWatch.RECORD, BodyKind.STORE, "record", "household", null,
            EVERY, FeltWeight.LOUD, "I cannot keep anything new", "never"));
        // The attach itself is the first mark she has not read; her first turn tells her.
        map.markRead(HER, map.unreadFor(HER).stream().map(BodyMark::id).toList());
    }

    @AfterEach
    void tearDown() {
        BodyMap.resetForTests();
    }

    private Interoception.Sense turn() {
        var sense = Interoception.feel(map, HER, Instant.now(), null, ACHE);
        map.markRead(HER, sense.told());
        return sense;
    }

    @Test
    @DisplayName("whole: both brains answering and the record holds, in one line")
    void wholeIsOneLine() {
        var sense = turn();
        assertEquals("[Body: whole — thinking brain and voice brain answering; the record holds.]", sense.line());
        assertTrue(sense.told().isEmpty());
    }

    @Test
    @DisplayName("the thinking brain goes quiet: her next turn says so, with what it costs her")
    void herNextTurnKnows() {
        map.heartbeat(BrainLimbs.id("llama-server"), false, "connection refused");

        var spike = turn();
        assertTrue(spike.line().contains("The thinking brain went quiet"), spike.line());
        assertTrue(spike.line().contains("I think slower and thinner"), spike.line());
        assertEquals(1, spike.told().size(), "the mark is told");
        assertFalse(spike.line().contains("whole"), spike.line());

        // The turn after: the mark is read, and the ache carries it (a loud part aches for as
        // long as it is quiet).
        var ache = turn();
        assertTrue(ache.told().isEmpty(), "told once");
        assertTrue(ache.line().contains("The thinking brain has been quiet for"), ache.line());
        assertTrue(ache.line().contains("I think slower and thinner"), ache.line());
        assertFalse(ache.line().contains("went quiet"), "the spike is over: " + ache.line());
    }

    @Test
    @DisplayName("the brain comes back: told once, then whole again")
    void backAgain() {
        map.heartbeat(BrainLimbs.id("llama-server"), false, null);
        turn();
        map.heartbeat(BrainLimbs.id("llama-server"), true, null);

        var back = turn();
        assertTrue(back.line().contains("The thinking brain is back"), back.line());
        assertEquals(1, back.told().size());

        var after = turn();
        assertTrue(after.line().startsWith("[Body: whole"), after.line());
    }

    @Test
    @DisplayName("a quiet voice brain aches for the window and is then only in the boiler room")
    void theAcheEnds() {
        map.heartbeat(BrainLimbs.id("llama-voice"), false, null);
        turn();   // the spike

        var soon = Interoception.feel(map, HER, Instant.now().plus(Duration.ofHours(1)), null, ACHE);
        assertTrue(soon.line().contains("voice brain has been quiet"), soon.line());

        var later = Interoception.feel(map, HER, Instant.now().plus(Duration.ofHours(7)), null, ACHE);
        assertFalse(later.line().contains("voice brain"), "past the window the line lets go: " + later.line());
        assertEquals(1, map.numb().size(), "but the map still holds it");
        assertTrue(later.line().contains("thinking brain answering"), later.line());
    }

    @Test
    @DisplayName("the record going quiet is loud: every turn, for as long as it lasts")
    void theRecordIsLoud() {
        map.heartbeat(BodyWatch.RECORD, false, "database is locked");
        turn();
        var days = Interoception.feel(map, HER, Instant.now().plus(Duration.ofDays(3)), null, ACHE);
        assertTrue(days.line().contains("The record has been quiet for 3 days"), days.line());
        assertTrue(days.line().contains("I cannot keep anything new"), days.line());
    }

    @Test
    @DisplayName("host pressure is one clause, not a chart")
    void hostPressureIsAClause() {
        var hot = new HostSense.Reading(35.0, 55.0, 60.0, 1.0, 8, 100L << 30);
        var sense = Interoception.feel(map, HER, Instant.now(), hot, ACHE);
        assertEquals("[Body: The box is under memory pressure.]", sense.line());

        var full = new HostSense.Reading(-1, 96.0, 4.0, -1, 8, 1L << 30);
        var sense2 = Interoception.feel(map, HER, Instant.now(), full, ACHE);
        assertEquals("[Body: My own memory is nearly full. The disk is nearly full.]", sense2.line());
    }

    @Test
    @DisplayName("no map, no line: a node without a body changes nothing for her")
    void noMapNoLine() {
        assertTrue(Interoception.feel(null, HER, Instant.now(), null, ACHE).isEmpty());
        BodyMap.resetForTests();
        assertTrue(Interoception.feel(BodyMap.inMemory(), HER, Instant.now(), null, ACHE).isEmpty(),
            "an empty map is silence too");
    }
}
