package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The nightly write trains on the spoken lines in the activity trail. A line is kept whole,
 * and a reply is written with who it answered and what that person said; a line said to the
 * room carries neither field.
 */
class TheTrailKeepsWhatWasSaidWholeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("a long line is whole; a reply carries the line it answers; a musing does not")
    void spokenLines(@TempDir Path dir) throws Exception {
        ActivityLogger.init(dir);
        var trail = ActivityLogger.get();
        String longLine = "The schedule is the first thing I would change. ".repeat(12).strip();
        assertTrue(longLine.length() > 500);

        trail.speak("ada", "companion-ada", "study", longLine, Map.of("Rapport", 0.8),
            "sam", "how would you tune it?");
        trail.speak("ada", "companion-ada", "study", "The room is quiet.", Map.of("Rapport", 0.2));
        trail.speak("ada", "companion-ada", "study", "And the loss, second.", Map.of("Rapport", 0.8),
            "sam", null);

        List<String> lines = Files.readAllLines(dir.resolve("agent-activity.jsonl"));
        assertEquals(3, lines.size());
        JsonNode reply = JSON.readTree(lines.get(0));
        assertEquals(longLine, reply.get("text").asText(), "kept whole, not cut at 200");
        assertEquals("sam", reply.get("to").asText());
        assertEquals("how would you tune it?", reply.get("heard").asText());
        assertTrue(reply.has("felt"));

        JsonNode musing = JSON.readTree(lines.get(1));
        assertFalse(musing.has("to"));
        assertFalse(musing.has("heard"));

        JsonNode second = JSON.readTree(lines.get(2));
        assertEquals("sam", second.get("to").asText());
        assertFalse(second.has("heard"), "what was said is written once per turn");
    }

    @Test
    @DisplayName("a sentence the product wrote is marked, so the night does not train on it as hers")
    void productAuthoredLines(@TempDir Path dir) throws Exception {
        ActivityLogger.init(dir);
        var trail = ActivityLogger.get();
        trail.speak("ada", "companion-ada", "study", "Taking that to the workshop: a brass key.",
            Map.of("Rapport", 0.5), "sam", "make me a brass key", true);
        trail.speak("ada", "companion-ada", "study", "I made it small enough for a pocket.",
            Map.of("Rapport", 0.5), "sam", null, false);

        List<String> lines = Files.readAllLines(dir.resolve("agent-activity.jsonl"));
        assertEquals("product", JSON.readTree(lines.get(0)).get("authored").asText());
        assertFalse(JSON.readTree(lines.get(1)).has("authored"), "her own line carries no mark");
    }

    @Test
    @DisplayName("a line made with her night raised is marked; a tool's words never are")
    void linesMadeWithHerNight(@TempDir Path dir) throws Exception {
        ActivityLogger.init(dir);
        var trail = ActivityLogger.get();
        trail.speak("ada", "companion-ada", "study", "The schedule first.", Map.of("Rapport", 0.8),
            "sam", "how would you tune it?", null, true);
        trail.speak("ada", "companion-ada", "study", "The room is quiet.", Map.of("Rapport", 0.2),
            null, null, null, false);
        trail.speak("ada", "companion-ada", "study", "1. Papers and Proceedings: ...", Map.of("Rapport", 0.2),
            "sam", null, ActivityLogger.AUTHORED_TOOL, true);

        List<String> lines = Files.readAllLines(dir.resolve("agent-activity.jsonl"));
        assertTrue(JSON.readTree(lines.get(0)).path("night").asBoolean(false));
        assertFalse(JSON.readTree(lines.get(1)).has("night"));
        assertFalse(JSON.readTree(lines.get(2)).has("night"), "a tool's words are not hers, night or not");
    }
}
