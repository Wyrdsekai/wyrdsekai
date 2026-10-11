package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.core.soul.CompactedMemory;
import org.wyrdsekai.core.soul.EmotionalCharge;
import org.wyrdsekai.core.soul.MemoryConsolidator;
import org.wyrdsekai.core.soul.MemoryNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * On an English household a person wrote to her in Spanish and she answered in Spanish; the
 * language healer, which only looked at the words, rewrote that answer into English a night
 * later (listed after the 0.5.5 release, 2026-10-05). A line now carries the language of the
 * turn it answered: into the trail row ("lang"), the room's record of it, and the memory node
 * sleep makes of it ("spokenIn"). The healer leaves a line in its turn's language alone; the
 * night leaves out a line that drifted from its turn's language whoever it was said to.
 */
class AReplyInThePersonsLanguageIsHersTest {

    private static final String ES = "Claro que sí, la biblioteca está junto al jardín y la puerta queda abierta.";
    private static final String EN = "Of course, the library is next to the garden and the door stays open.";

    @Test
    @DisplayName("a node spoken in the language the turn was in is not an offender; one with no record still is")
    void healerHonoursTheSpokenLanguage() {
        var mem = new CompactedMemory(List.of(
            MemoryNode.neutral("reply", "I said: " + ES, List.of()).withSpokenIn("es"),
            MemoryNode.neutral("drift", "I said: " + ES, List.of()).withSpokenIn("en"),
            MemoryNode.neutral("old", "I said: " + ES, List.of()),
            MemoryNode.neutral("fine", "I said: " + EN, List.of()).withSpokenIn("en")),
            List.of(), Map.of());
        var picked = CompanionActor.memoryNodeLanguageOffenders(mem, "en", 10, Set.of(), "mia");
        assertThat(picked.stream().map(MemoryNode::id).toList())
            .as("the reply in the person's language is hers; the drift and the undated node are mended")
            .containsExactly("drift", "old");
    }

    @Test
    @DisplayName("sleep writes her own line with the language it was spoken in; what others said carries none")
    void sleepKeepsIt() {
        var when = Instant.parse("2026-10-09T10:00:00Z");
        var events = List.of(
            new WorldEvent.Said("library", when, "person-1", "sam", "¿dónde está la biblioteca?", "es"),
            new WorldEvent.Said("library", when.plusSeconds(5), "companion-mia", "mia", ES, "es"),
            new WorldEvent.Said("library", when.plusSeconds(60), "companion-mia", "mia", EN, "en"));
        var nodes = MemoryConsolidator.encodeEvents(events,
            List.of(EmotionalCharge.none(), EmotionalCharge.none(), EmotionalCharge.none()), "companion-mia");
        assertThat(nodes).hasSize(3);
        assertThat(nodes.get(0).content()).startsWith("sam said:");
        assertThat(nodes.get(0).spokenIn()).isNull();
        assertThat(nodes.get(1).content()).isEqualTo("I said: " + ES);
        assertThat(nodes.get(1).spokenIn()).isEqualTo("es");
        assertThat(nodes.get(2).spokenIn()).isEqualTo("en");
    }

    @Test
    @DisplayName("a node keeps its spoken language through decay and access; an older node reads as having none")
    void theNodeKeepsIt() {
        var n = MemoryNode.neutral("m", "I said: " + ES, List.of()).withSpokenIn("es");
        assertThat(n.decayed(0.1f).spokenIn()).isEqualTo("es");
        assertThat(n.accessed().spokenIn()).isEqualTo("es");
        var before = new MemoryNode("m", EN, List.of(), 0.5f, 0.0f, false, "none", Instant.now(), 0, "en");
        assertThat(before.spokenIn()).isNull();
    }

    @Test
    @DisplayName("the trail row says what language the line was spoken in")
    void theTrailRowCarriesIt(@TempDir Path dir) throws Exception {
        var trail = dir.resolve("agent-activity.jsonl");
        ActivityLogger.init(dir);
        var logger = ActivityLogger.get();
        logger.speak("mia", "companion-mia", "library", ES, Map.of("Rapport", 0.9),
            "sam", "person-1", "¿dónde está la biblioteca?", null, false, "es");
        logger.speak("mia", "companion-mia", "library", EN, Map.of("Rapport", 0.9),
            null, null, null, null, false, null);
        var rows = Files.readAllLines(trail);
        assertThat(rows.get(0)).contains("\"lang\":\"es\"").contains("\"to\":\"sam\"");
        assertThat(rows.get(1)).doesNotContain("\"lang\"");
    }

    @Test
    @DisplayName("the actor puts the turn's language on the line it speaks: the trail, the room, her own record")
    void theActorIsWired() throws Exception {
        var src = Files.readString(Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));
        assertThat(src).contains("final String spokenIn = answering != null && answering != NO_ONE && isHumanTrigger(answering)\n"
            + "            ? turnLanguage(answering.text(), true, true, locale, householdLocale)\n"
            + "            : turnLanguage(null, false, false, locale, householdLocale);");
        assertThat(src).contains("authoredBy, replyMadeWithHerNight, spokenIn);");
        assertThat(src).contains("profile.entityId(), profile.name(), adapted, spokenIn, roomResponseAdapter));");
        assertThat(src).contains("roomId, Instant.now(), profile.entityId(), profile.name(), adapted, spokenIn);");
        assertThat(src).as("a healed node is in the household's language now").contains("old.accessCount(), household, household));");
        // Her own time is the household's language; a person's Spanish line is answered in Spanish.
        assertThat(CompanionActor.turnLanguage(null, false, false, "es", "en")).isEqualTo("en");
        assertThat(CompanionActor.turnLanguage("¿dónde está la biblioteca?", true, true, "en", "en")).isEqualTo("es");
    }
}
