package org.wyrdsekai.core.body;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Told afterwards, once. A mark the body leaves (a night, a pause, a reflex) reaches her on the
 * next turn and not again; a mark addressed to her is not told to another companion sharing the
 * body; a flood of marks is paced, not dumped.
 */
class AMarkIsToldOnceTest {

    private static final String MIA = "companion-mia";
    private static final String EMBER = "companion-ember";

    @AfterEach
    void tearDown() {
        BodyMap.resetForTests();
    }

    @Test
    @DisplayName("the slept mark: on waking she reads it, then it is read")
    void sleptOnce() {
        var map = BodyMap.inMemory();
        map.mark("slept", "sleep", MIA, "I slept at 03:12, for 40 s; 17 moments were consolidated.", null);

        var first = Interoception.feel(map, MIA, Instant.now(), null, Duration.ofHours(6));
        assertTrue(first.line().contains("I slept at 03:12"), first.line());
        map.markRead(MIA, first.told());

        var second = Interoception.feel(map, MIA, Instant.now(), null, Duration.ofHours(6));
        assertFalse(second.line().contains("I slept"), "told once: " + second.line());
        assertTrue(map.recentMarks(5).get(0).readBy(MIA));
    }

    @Test
    @DisplayName("her night is hers: another companion in the same body is not told it")
    void addressedMarksStayAddressed() {
        var map = BodyMap.inMemory();
        map.mark("slept", "sleep", MIA, "I slept.", null);
        map.mark("numb", "brain:x", null, "The far brain went quiet.", null);

        assertEquals(2, map.unreadFor(MIA).size());
        assertEquals(1, map.unreadFor(EMBER).size(), "only the body-wide mark");
        assertEquals("numb", map.unreadFor(EMBER).get(0).kind());

        map.markRead(EMBER, map.unreadFor(EMBER).stream().map(BodyMark::id).toList());
        assertEquals(2, map.unreadFor(MIA).size(), "one reader's reading is not the other's");
    }

    @Test
    @DisplayName("many marks are paced a few per turn, newest kept, none lost")
    void pacedNotDumped() {
        var map = BodyMap.inMemory();
        for (int i = 1; i <= 5; i++) map.mark("numb", "part" + i, null, "Part " + i + " went quiet.", null);

        var turn1 = Interoception.feel(map, MIA, Instant.now(), null, Duration.ofHours(6));
        assertEquals(Interoception.MARKS_PER_TURN, turn1.told().size());
        assertTrue(turn1.line().contains("Part 5"), "newest first: " + turn1.line());
        map.markRead(MIA, turn1.told());

        var turn2 = Interoception.feel(map, MIA, Instant.now(), null, Duration.ofHours(6));
        assertEquals(2, turn2.told().size(), "the rest come next turn");
        map.markRead(MIA, turn2.told());
        assertTrue(map.unreadFor(MIA).isEmpty());
    }
}
