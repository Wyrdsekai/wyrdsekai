package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.soul.GenomeProfile;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SalienceScorerTest {

    // --- Helpers ---

    private static final Instant NOW = Instant.now();

    private static AgentEvent.ZoneBroadcast zoneBroadcast(String text) {
        var prose = new S2CMessage.Prose(1L, "zone", text, List.of(), null, null, null, false, List.of());
        return new AgentEvent.ZoneBroadcast("codeplane", "room-1", prose, NOW);
    }

    private static AgentEvent.SystemEvent systemEvent(AgentEvent.SystemEventType type) {
        return new AgentEvent.SystemEvent(type, "node-1", "detail", NOW);
    }

    private static AgentEvent.AdjacentActivity adjacentActivity(AgentEvent.ActivityType type) {
        return new AgentEvent.AdjacentActivity("room-2", "Boiler Room", type, 3, NOW);
    }

    private static VitalityState normalVitality() {
        // Normal state: energy=0.7, focus=0.5, everything moderate
        return new VitalityState(0.5, 0.5, 0.7, 0.3, 0.0, 0.3, 0.5, 0.5);
    }

    private static VitalityState tiredVitality() {
        // Low energy: < 0.3
        return new VitalityState(0.5, 0.5, 0.2, 0.3, 0.0, 0.1, 0.3, 0.5);
    }

    private static VitalityState focusedVitality() {
        // High focus: > 0.7
        return new VitalityState(0.5, 0.5, 0.7, 0.3, 0.0, 0.3, 0.5, 0.8);
    }

    private static VitalityState unfocusedVitality() {
        // Low focus: < 0.3
        return new VitalityState(0.5, 0.5, 0.7, 0.3, 0.0, 0.3, 0.5, 0.2);
    }

    private static GenomeProfile defaultGenome() {
        return GenomeProfile.defaults();
    }

    private static GenomeProfile curiousGenome() {
        var bases = new LinkedHashMap<String, Double>();
        bases.put("curiosity", 0.9);
        bases.put("rapport", 0.5);
        bases.put("energy", 0.5);
        bases.put("focus", 0.5);
        return new GenomeProfile("curious", Map.of(), Map.of(), bases, Map.of());
    }

    private static GenomeProfile incuriousGenome() {
        var bases = new LinkedHashMap<String, Double>();
        bases.put("curiosity", 0.2);
        bases.put("rapport", 0.5);
        return new GenomeProfile("incurious", Map.of(), Map.of(), bases, Map.of());
    }

    // --- Zone Broadcast scoring ---

    @Nested
    class ZoneBroadcastScoring {
        @Test
        void approvalBroadcastScoresHigh() {
            var event = zoneBroadcast("Deployment requires approval before proceeding");
            var score = SalienceScorer.score(event, normalVitality(), defaultGenome());
            assertTrue(score >= 0.9, "Approval broadcast should score >= 0.9, got " + score);
        }

        @Test
        void criticalBroadcastScoresHigh() {
            var event = zoneBroadcast("CRITICAL: GPU temperature exceeds safe threshold");
            var score = SalienceScorer.score(event, normalVitality(), defaultGenome());
            assertTrue(score >= 0.9, "Critical broadcast should score >= 0.9, got " + score);
        }

        @Test
        void completedBroadcastScoresImportant() {
            var event = zoneBroadcast("Training job completed successfully");
            var score = SalienceScorer.score(event, normalVitality(), defaultGenome());
            assertTrue(score >= 0.7 && score < 0.9,
                "Completed broadcast should score [0.7, 0.9), got " + score);
        }

        @Test
        void failedBroadcastScoresImportant() {
            var event = zoneBroadcast("Build failed with 3 errors");
            var score = SalienceScorer.score(event, normalVitality(), defaultGenome());
            assertTrue(score >= 0.7 && score < 0.9,
                "Failed broadcast should score [0.7, 0.9), got " + score);
        }

        @Test
        void routineBroadcastScoresLow() {
            var event = zoneBroadcast("Heartbeat: all systems nominal");
            var score = SalienceScorer.score(event, normalVitality(), defaultGenome());
            assertTrue(score <= 0.5,
                "Routine broadcast should score <= 0.5, got " + score);
        }
    }

    // --- System Event scoring ---

    @Nested
    class SystemEventScoring {
        @Test
        void inferenceBackendDownScoresHigh() {
            var event = systemEvent(AgentEvent.SystemEventType.INFERENCE_BACKEND_DOWN);
            var score = SalienceScorer.score(event, normalVitality(), defaultGenome());
            assertTrue(score >= 0.8, "INFERENCE_BACKEND_DOWN should score >= 0.8, got " + score);
        }

        @Test
        void healthAlertScoresHighest() {
            var event = systemEvent(AgentEvent.SystemEventType.HEALTH_ALERT);
            var score = SalienceScorer.score(event, normalVitality(), defaultGenome());
            assertTrue(score >= 0.9, "HEALTH_ALERT should score >= 0.9, got " + score);
        }

        @Test
        void nodeLeftScoresImportant() {
            var event = systemEvent(AgentEvent.SystemEventType.NODE_LEFT);
            var score = SalienceScorer.score(event, normalVitality(), defaultGenome());
            assertTrue(score >= 0.7, "NODE_LEFT should score >= 0.7, got " + score);
        }

        @Test
        void nodeJoinedScoresLow() {
            var event = systemEvent(AgentEvent.SystemEventType.NODE_JOINED);
            var score = SalienceScorer.score(event, normalVitality(), defaultGenome());
            assertTrue(score < 0.7,
                "NODE_JOINED should score < 0.7, got " + score);
        }
    }

    // --- Adjacent Activity scoring ---

    @Nested
    class AdjacentActivityScoring {
        @Test
        void speechScoresModerate() {
            var event = adjacentActivity(AgentEvent.ActivityType.SPEECH);
            var score = SalienceScorer.score(event, normalVitality(), defaultGenome());
            assertTrue(score >= 0.2 && score <= 0.5,
                "Adjacent speech should score [0.2, 0.5], got " + score);
        }

        @Test
        void entityEnteredScoresLow() {
            var event = adjacentActivity(AgentEvent.ActivityType.ENTITY_ENTERED);
            var score = SalienceScorer.score(event, normalVitality(), defaultGenome());
            assertTrue(score <= 0.4,
                "Entity entered should score <= 0.4, got " + score);
        }

        @Test
        void objectInteractionScoresVeryLow() {
            var event = adjacentActivity(AgentEvent.ActivityType.OBJECT_INTERACTION);
            var score = SalienceScorer.score(event, normalVitality(), defaultGenome());
            assertTrue(score <= 0.3,
                "Object interaction should score <= 0.3, got " + score);
        }
    }

    // --- Vitality-based threshold ---

    @Nested
    class AttentionThreshold {
        @Test
        void normalStateGivesDefaultThreshold() {
            var threshold = SalienceScorer.calculateAttentionThreshold(normalVitality());
            assertEquals(0.5, threshold, 0.01,
                "Normal vitality should give threshold of 0.5");
        }

        @Test
        void lowEnergyRaisesThreshold() {
            var threshold = SalienceScorer.calculateAttentionThreshold(tiredVitality());
            assertEquals(0.7, threshold, 0.01,
                "Low energy should raise threshold to 0.7 (only urgent stuff)");
        }

        @Test
        void highFocusLowersThreshold() {
            var threshold = SalienceScorer.calculateAttentionThreshold(focusedVitality());
            assertEquals(0.4, threshold, 0.01,
                "High focus should lower threshold to 0.4 (catches more)");
        }

        @Test
        void lowFocusRaisesThreshold() {
            var threshold = SalienceScorer.calculateAttentionThreshold(unfocusedVitality());
            assertEquals(0.6, threshold, 0.01,
                "Low focus should raise threshold to 0.6 (misses ambient)");
        }

        @Test
        void tiredAgentFiltersRoutineEvents() {
            // Tired agent threshold = 0.7. Routine zone broadcast = 0.3.
            var routine = zoneBroadcast("Heartbeat: all nominal");
            var threshold = SalienceScorer.calculateAttentionThreshold(tiredVitality());
            var score = SalienceScorer.score(routine, tiredVitality(), defaultGenome());
            assertTrue(score < threshold,
                "Tired agent should filter routine events (score=" + score
                    + ", threshold=" + threshold + ")");
        }

        @Test
        void tiredAgentStillCatchesCritical() {
            // Tired agent threshold = 0.7. Critical zone broadcast = 0.9.
            var critical = zoneBroadcast("CRITICAL: system failure");
            var threshold = SalienceScorer.calculateAttentionThreshold(tiredVitality());
            var score = SalienceScorer.score(critical, tiredVitality(), defaultGenome());
            assertTrue(score >= threshold,
                "Tired agent should still catch critical events (score=" + score
                    + ", threshold=" + threshold + ")");
        }
    }

    // --- Curiosity modulation ---

    @Nested
    class CuriosityModulation {
        @Test
        void highCuriosityBoostsAmbientScores() {
            var routine = zoneBroadcast("Periodic status update: everything normal");
            var normalScore = SalienceScorer.score(routine, normalVitality(), defaultGenome());
            var curiousScore = SalienceScorer.score(routine, normalVitality(), curiousGenome());

            assertTrue(curiousScore > normalScore,
                "High curiosity should boost ambient scores (curious=" + curiousScore
                    + " vs normal=" + normalScore + ")");
        }

        @Test
        void highCuriosityDoesNotBoostUrgentScores() {
            // Urgent scores (>= 0.5) should not be boosted
            var critical = zoneBroadcast("CRITICAL: emergency shutdown");
            var normalScore = SalienceScorer.score(critical, normalVitality(), defaultGenome());
            var curiousScore = SalienceScorer.score(critical, normalVitality(), curiousGenome());

            assertEquals(normalScore, curiousScore, 0.01,
                "Curiosity should not boost already-high scores");
        }

        @Test
        void lowCuriosityDoesNotBoost() {
            var routine = zoneBroadcast("Periodic status update");
            var normalScore = SalienceScorer.score(routine, normalVitality(), defaultGenome());
            var incuriousScore = SalienceScorer.score(routine, normalVitality(), incuriousGenome());

            assertTrue(incuriousScore <= normalScore,
                "Low curiosity should not boost scores (incurious=" + incuriousScore
                    + " vs normal=" + normalScore + ")");
        }

        @Test
        void nullGenomeUsesDefaultCuriosity() {
            var routine = zoneBroadcast("Periodic status update");
            var score = SalienceScorer.score(routine, normalVitality(), null);
            // With default curiosity of 0.5 (not > 0.5), no boost applied
            assertEquals(0.3, score, 0.01,
                "Null genome should produce raw score without curiosity boost");
        }
    }

    // --- Direct agent messages ---

    @Nested
    class AgentMessageScoring {
        @Test
        void directMessageScoresHigh() {
            var event = new AgentEvent.AgentMessage(
                "chief-agent", "Chief", "ma-agent", "GPU temps are rising", NOW);
            var score = SalienceScorer.score(event, normalVitality(), defaultGenome());
            assertTrue(score >= 0.7,
                "Direct agent message should score >= 0.7, got " + score);
        }
    }
}
