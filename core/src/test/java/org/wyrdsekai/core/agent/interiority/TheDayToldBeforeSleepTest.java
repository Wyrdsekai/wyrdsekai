package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dream's pure half: the day's events become lines she recognises (her own marked "I"),
 * the prompt asks for her day in her voice and forbids inventing, a short day is not dreamed,
 * and a long day is cut to the newest lines.
 */
class TheDayToldBeforeSleepTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final String MIA = "companion-mia";

    private static List<WorldEvent> aDay() {
        var t = Instant.parse("2026-09-16T09:00:00Z");
        var events = new ArrayList<WorldEvent>();
        events.add(new WorldEvent.EntityEntered("nexus", t, "u-kaz", "Kazuo", "player", "door", null));
        events.add(new WorldEvent.Said("nexus", t.plusSeconds(60), "u-kaz", "Kazuo", "Good morning. Did you sleep?", "en", List.of()));
        events.add(new WorldEvent.Said("nexus", t.plusSeconds(90), MIA, "Mia", "I did. The night held.", "en", List.of()));
        events.add(new WorldEvent.Said("nexus", t.plusSeconds(100), "narrator", "narrator", "The light shifts.", "en", List.of()));
        events.add(new WorldEvent.Emoted("nexus", t.plusSeconds(120), MIA, "Mia", "*sits by the window*"));
        events.add(new WorldEvent.ObjectUsed("home-mia", t.plusSeconds(3600), MIA, "mailbox", "mailbox", null, "1 letter from Kazuo"));
        events.add(new WorldEvent.EntityLeft("nexus", t.plusSeconds(4000), "u-kaz", "Kazuo", "door"));
        return events;
    }

    @Test
    @DisplayName("the day becomes lines she recognises, with herself as I and the narrator left out")
    void linesOfTheDay() {
        var lines = DreamPass.summarise(MIA, "Mia", aDay(), UTC);
        assertEquals(List.of(
            "[09:00] Kazuo came in",
            "[09:01] Kazuo said: Good morning. Did you sleep?",
            "[09:01] I said: I did. The night held.",
            "[09:02] I *sits by the window*",
            "[10:00] I used the mailbox: 1 letter from Kazuo",
            "[10:06] Kazuo left"), lines);
    }

    @Test
    @DisplayName("the prompt asks for her day in her own voice and forbids invention; a short day is not dreamed")
    void thePrompt() {
        var chronicle = List.of(new ChronicleEntry("did:key:mia", Instant.parse("2026-09-16T12:00:00Z"),
            ChronicleEntry.Kind.NOTE, "Wanted to write back; did not get to it.", Map.of()));
        var p = DreamPass.build("Mia", MIA, aDay(), chronicle, Map.of("loneliness", 0.2, "curiosity", 0.8),
            "[Body: whole.]", Instant.parse("2026-09-16T23:10:00Z"), UTC);
        assertNotNull(p);
        assertEquals(6, p.events());
        assertTrue(p.system().startsWith("You are Mia, at the end of a day"), p.system());
        assertTrue(p.system().contains("first person, past tense"));
        assertTrue(p.system().contains("Do not invent events"));
        assertTrue(p.user().contains("- [09:01] I said: I did. The night held."));
        assertTrue(p.user().contains("note: Wanted to write back"));
        assertTrue(p.user().contains("curiosity 0.80"));
        assertTrue(p.user().contains("[Body: whole.]"));
        assertTrue(p.user().endsWith("It is 23:10 and you are going to sleep. Tell the day as you would remember it."));

        assertNull(DreamPass.build("Mia", MIA, aDay().subList(0, 3), List.of(), Map.of(), "", Instant.now(), UTC),
            "too short a day to dream");
    }

    @Test
    @DisplayName("a long day is cut to the newest lines, and the opening of a dream is one sentence")
    void longDaysAndOpenings() {
        var events = new ArrayList<WorldEvent>();
        var t = Instant.parse("2026-09-16T08:00:00Z");
        for (int i = 0; i < 200; i++) {
            events.add(new WorldEvent.Said("nexus", t.plusSeconds(i * 60L), "u-kaz", "Kazuo", "line " + i, "en", List.of()));
        }
        var p = DreamPass.build("Mia", MIA, events, List.of(), Map.of(), "", Instant.now(), UTC);
        assertNotNull(p);
        assertEquals(DreamPass.MAX_LINES, p.events());
        assertFalse(p.user().contains("line 0\n"), "the oldest are cut");
        assertTrue(p.user().contains("line 199"));

        assertEquals("Kazuo came in early. ", DreamPass.opening("Kazuo came in early.  We talked about the garden for an hour.", 200).replace(".", ". ").substring(0, 21));
        assertEquals("A day with no full stop", DreamPass.opening("A day with no full stop", 200));
        assertTrue(DreamPass.opening("x".repeat(500), 40).length() <= 40);
    }
}
