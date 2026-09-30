package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.persistence.ConversationTurnStore;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A conversation turn carries the exchange between the companion and one person, her day
 * from the record, and an identity from her record. It carries no tool catalog, no length
 * cap, and none of what she said to the room in between.
 */
class ConversationLaneTest {

    private static ConversationTurnStore.Turn turn(String role, String text, long atSeconds) {
        return new ConversationTurnStore.Turn(atSeconds, role, text, atSeconds * 1000, "study");
    }

    @Test
    @DisplayName("the exchange keeps the person's lines and her replies, not her room talk")
    void exchange() {
        var rows = List.of(
            turn("SPOKEN", "The room is quiet.", 0),
            turn("HEARD", "how would you tune a text diffusion model?", 100),
            turn("SPOKEN", "Start with the masking schedule.", 104),
            turn("SPOKEN", "Then the loss weighting.", 110),
            turn("SPOKEN", "A third thing said in the same breath.", 115),
            turn("SPOKEN", "Something settles in me.", 900),
            turn("HEARD", "we were talking about text diffusion", 1000),
            turn("SPOKEN", "Yes. The schedule.", 1003));
        var ex = ConversationLane.exchange(new ArrayList<>(rows).reversed());
        assertEquals(List.of(
            new ConversationLane.Turn(true, "how would you tune a text diffusion model?"),
            new ConversationLane.Turn(false, "Start with the masking schedule."),
            new ConversationLane.Turn(false, "Then the loss weighting."),
            new ConversationLane.Turn(true, "we were talking about text diffusion"),
            new ConversationLane.Turn(false, "Yes. The schedule.")), ex);
    }

    @Test
    @DisplayName("one leading system block, stable part first; the thread; the line last")
    void assemble() {
        var zone = ZoneId.of("UTC");
        var identity = ConversationLane.identity("ada", "", "the Hearth",
            Instant.parse("2026-07-01T00:00:00Z"), zone);
        var about = ConversationLane.aboutPerson("sam", true, Instant.parse("2026-07-02T00:00:00Z"), 31, zone);
        var day = ConversationLane.day("We built a greenhouse.", List.of("09:02 I read Ozymandias"), 40);
        var thread = List.of(new ConversationLane.Turn(true, "hello"), new ConversationLane.Turn(false, "Hello, sam."));
        var msgs = ConversationLane.assemble("Reply in English.", identity, "How I speak: plainly.",
            about, "You remember: sam likes lighthouses.", day, "You are in the Study.", null, null,
            thread, "sam", "what did you read today?");

        assertEquals(4, msgs.size());
        var system = msgs.get(0).content();
        assertEquals("system", msgs.get(0).role());
        assertTrue(system.startsWith("Reply in English."));
        assertTrue(system.contains("You are ada. You live in the Hearth. You have lived here since 1 July 2026."));
        assertTrue(system.contains("sam is your bondholder, with you since 2 July 2026. You have talked on 31 days."));
        assertTrue(system.contains("09:02 I read Ozymandias"));
        assertTrue(system.indexOf("Your day") < system.indexOf("You are in the Study."),
            "what changes per turn comes after what is stable for the day");
        assertFalse(system.contains("One or two sentences"));
        assertFalse(system.toLowerCase().contains("tools to act"));
        assertEquals("sam says: hello", msgs.get(1).content());
        assertEquals("assistant", msgs.get(2).role());
        assertEquals("sam says: what did you read today?", msgs.get(3).content());
    }

    @Test
    @DisplayName("her drives line rides the turn, after the stable part and before what she remembers")
    void feltLine() {
        var zone = ZoneId.of("UTC");
        var identity = ConversationLane.identity("ada", "", null, null, zone);
        var drives = "[drives: seeking=0.0 care=0.0 play=0.0 vigilance=0.0 affiliation=0.0 grief=0.9 frustration=0.0"
            + " creativity=0.0 | energy=0.6 confidence=0.3 integrity=0.7 disgust=0.0]";
        var day = ConversationLane.day(null, List.of("09:02 I came into the Study"), 40);
        var system = ConversationLane.assemble("Reply in English.", identity, null, null,
            "You remember: sam likes lighthouses.", day, "You are in the Study.", drives, null,
            List.of(), "sam", "hey").get(0).content();
        assertTrue(system.contains(drives));
        assertTrue(system.indexOf("09:02 I came into the Study") < system.indexOf(drives),
            "the line changes every turn, so it stays out of the part that is stable for the day");
        assertTrue(system.indexOf("You are in the Study.") < system.indexOf(drives));
        assertTrue(system.indexOf(drives) < system.indexOf("You remember: sam likes lighthouses."));

        var without = ConversationLane.assemble("Reply in English.", identity, null, null, null, day,
            "You are in the Study.", null, null, List.of(), "sam", "hey").get(0).content();
        assertFalse(without.contains("[drives:"));
    }

    @Test
    @DisplayName("an empty record says so; the oldest turns are the first to go")
    void budgetsAndEmptyDay() {
        assertEquals("Your day: nothing is on record yet today.", ConversationLane.day(null, List.of(), 40));
        var unread = ConversationLane.day("We built a greenhouse.", List.of(), 40, List.of());
        assertTrue(unread.contains("You have not read anything in the library lately."));
        var read = ConversationLane.day(null, List.of(), 40,
            List.of("17 Sep — you asked: Ozymandias — you concluded: a ruin in a desert."));
        assertTrue(read.contains("What you have read lately:\n17 Sep — you asked: Ozymandias"));
        assertFalse(read.contains("have not read"));
        var thread = new ArrayList<ConversationLane.Turn>();
        for (int i = 0; i < 100; i++) thread.add(new ConversationLane.Turn(i % 2 == 0, "line " + i + " " + "x".repeat(400)));
        var kept = ConversationLane.fit(thread, ConversationLane.THREAD_TOKEN_BUDGET);
        assertTrue(kept.size() < 100 && kept.size() > 10);
        assertTrue(kept.getLast().text().startsWith("line 99 "));
    }
}
