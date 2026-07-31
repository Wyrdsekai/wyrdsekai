package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentNarrationTest {

    // --- Sleep Entry ---

    @Test
    void sleepEntry_exhausted() {
        var text = AgentNarration.sleepEntry(0.05, null, 0, false);
        assertNotNull(text);
        assertTrue(text.contains("*"), "Should be an emote");
    }

    @Test
    void sleepEntry_busy_period() {
        var text = AgentNarration.sleepEntry(0.20, null, 15, false);
        assertNotNull(text);
    }

    @Test
    void sleepEntry_unresolved() {
        var text = AgentNarration.sleepEntry(0.20, null, 3, true);
        assertNotNull(text);
    }

    @Test
    void sleepEntry_with_emotion() {
        var text = AgentNarration.sleepEntry(0.20, "curiosity", 3, false);
        assertNotNull(text);
    }

    @Test
    void sleepEntry_default() {
        var text = AgentNarration.sleepEntry(0.20, null, 3, false);
        assertNotNull(text);
    }

    @Test
    void sleepEntry_varies() {
        var texts = new HashSet<String>();
        for (int i = 0; i < 30; i++) {
            texts.add(AgentNarration.sleepEntry(0.20, "curiosity", 3, false));
        }
        assertTrue(texts.size() > 1, "Sleep entry should have variety");
    }

    // --- Room Arrival ---

    @Test
    void roomArrival_firstVisit_alone() {
        var text = AgentNarration.roomArrival("The Nexus", List.of(), List.of(), true);
        assertTrue(text.isPresent());
        assertTrue(text.get().contains("Nexus"));
    }

    @Test
    void roomArrival_firstVisit_withEntity() {
        var text = AgentNarration.roomArrival("The Terminal", List.of("Ember"), List.of(), true);
        assertTrue(text.isPresent());
        assertTrue(text.get().contains("Ember"));
    }

    @Test
    void roomArrival_firstVisit_withObject() {
        var text = AgentNarration.roomArrival("The Library", List.of(), List.of("card catalog"), true);
        assertTrue(text.isPresent());
        assertTrue(text.get().contains("card catalog"));
    }

    @Test
    void roomArrival_returnVisit_usually_silent() {
        int silentCount = 0;
        for (int i = 0; i < 50; i++) {
            if (AgentNarration.roomArrival("The Nexus", List.of(), List.of(), false).isEmpty()) {
                silentCount++;
            }
        }
        assertTrue(silentCount > 40, "Return visits should usually be silent (" + silentCount + "/50)");
    }

    // --- Memory Reinforcement ---

    @Test
    void memoryReinforced_highConfidence() {
        var text = AgentNarration.memoryReinforced("core identity trait", 0.95f);
        assertTrue(text.isPresent());
    }

    @Test
    void memoryReinforced_mediumConfidence() {
        var text = AgentNarration.memoryReinforced("behavioral pattern", 0.75f);
        assertTrue(text.isPresent());
    }

    @Test
    void memoryReinforced_lowConfidence_silent() {
        var text = AgentNarration.memoryReinforced("weak pattern", 0.4f);
        assertTrue(text.isEmpty(), "Low confidence reinforcement should be silent");
    }

    // --- Contradiction ---

    @Test
    void contradictionDetected_produces_narration() {
        var text = AgentNarration.contradictionDetected("I am cautious", "I acted impulsively");
        assertTrue(text.isPresent());
    }
}
