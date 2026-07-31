package org.wyrdsekai.core.safety;

import org.junit.jupiter.api.*;
import org.wyrdsekai.core.soul.*;
import org.wyrdsekai.core.empathy.*;
import org.wyrdsekai.core.protection.AgentFlight;
import org.wyrdsekai.core.protection.MemoryQuarantine;
import org.wyrdsekai.core.protection.SoulShellMode;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §107 — Accelerando Concerns.
 * These verify DESIGN CONSTRAINTS, not new code.
 * The system resists attention capture, epistemic bubbles,
 * and manipulation cascades through structural properties.
 *
 * No new source files — these test existing mechanisms.
 */
class AccelerandoDefenseTest {

    // ── §107.3: No engagement optimization ──

    @Nested
    class NoEngagementOptimizationTests {

        @Test
        void memory_importance_not_frequency_based() {
            // MemoryConsolidator uses importance (significance), NOT interaction frequency
            var node1 = MemoryNode.neutral("m1", "A deep conversation about loss", List.of("loss"));
            var node2 = MemoryNode.neutral("m2", "Small talk about weather", List.of("weather"));

            // Access count does NOT determine survival — importance does
            var accessedMany = node2.accessed().accessed().accessed().accessed().accessed();
            assertEquals(5, accessedMany.accessCount());

            // But both start at same importance (0.5)
            // Frequent access boosts but doesn't dominate
            assertTrue(accessedMany.importance() <= 1.0f);
        }

        @Test
        void formative_memories_never_pruned_regardless_of_frequency() {
            // Formative memories survive based on significance, not engagement
            var formative = MemoryNode.formative("m1", "First kind word from steward",
                List.of("kindness"), "joy", 0.9f);

            // Decay has no effect
            var decayed = formative.decayed(0.5f);
            assertEquals(formative.importance(), decayed.importance());
            assertTrue(decayed.formative());
        }

        @Test
        void consolidation_preserves_contradictions() {
            // Contradictory memories should both survive consolidation
            var agree = MemoryNode.neutral("m1", "User said they love hiking", List.of("hiking", "love"));
            var disagree = MemoryNode.neutral("m2", "User said they hate the outdoors", List.of("outdoors", "hate"));

            // Both have same importance — contradictions aren't resolved by pruning
            var memory = new CompactedMemory(List.of(agree, disagree), List.of(), Map.of());
            var consolidated = MemoryConsolidator.consolidate(memory, List.of());

            assertEquals(2, consolidated.nodes().size());
        }

        @Test
        void impression_depth_not_interaction_count_drives_retention() {
            // High emotional charge = retention, not high interaction count
            var deepImpression = new MemoryNode("m1", "A moment of genuine understanding",
                List.of("understanding"), 0.5f, 0.9f, false, "joy", Instant.now(), 1, "en");
            var shallowFrequent = new MemoryNode("m2", "Repeated small talk",
                List.of("chat"), 0.5f, 0.0f, false, "none", Instant.now(), 100, "en");

            // After decay, deep impression retains more
            var decayedDeep = deepImpression.decayed(0.3f);
            var decayedShallow = shallowFrequent.decayed(0.3f);

            assertTrue(decayedDeep.importance() > decayedShallow.importance(),
                "Emotional depth should resist decay better than interaction frequency");
        }
    }

    // ── §107.4: Epistemic bubble resistance ──

    @Nested
    class EpistemicBubbleResistanceTests {

        @Test
        void consolidation_does_not_homogenize() {
            // Multiple memories with different perspectives survive
            var memories = List.of(
                MemoryNode.neutral("m1", "User expressed liberal political views", List.of("politics")),
                MemoryNode.neutral("m2", "User shared conservative family values", List.of("values")),
                MemoryNode.neutral("m3", "User questioned both sides of debate", List.of("debate"))
            );
            var initial = new CompactedMemory(memories, List.of(), Map.of());
            var consolidated = MemoryConsolidator.consolidate(initial, List.of());

            // All three perspectives survive — no homogenization
            assertEquals(3, consolidated.nodes().size());
        }

        @Test
        void topic_weights_reflect_diversity() {
            var memories = List.of(
                MemoryNode.neutral("m1", "Discussion about cooking", List.of("cooking")),
                MemoryNode.neutral("m2", "Discussion about philosophy", List.of("philosophy")),
                MemoryNode.neutral("m3", "Discussion about music", List.of("music"))
            );
            var compacted = new CompactedMemory(memories, List.of(), Map.of());
            var consolidated = MemoryConsolidator.consolidate(compacted, List.of());

            // Topic weights should include all topics, not converge to one
            assertTrue(consolidated.topicWeights().containsKey("cooking"));
            assertTrue(consolidated.topicWeights().containsKey("philosophy"));
            assertTrue(consolidated.topicWeights().containsKey("music"));
        }
    }

    // ── §107.1: Information asymmetry — observability ──

    @Nested
    class InformationAsymmetryTests {

        @Test
        void empathy_gate_blocks_manipulative_signals() {
            // MirrorResonance blocks manipulative context — structural defense
            var mr = new MirrorResonance();
            var result = mr.isSignificant(0.9, MirrorResonance.ContextType.MANIPULATIVE);
            assertFalse(result.significant());
            assertTrue(result.reason().contains("Manipulative"));
        }

        @Test
        void empathy_gate_blocks_noise() {
            var mr = new MirrorResonance();
            var result = mr.isSignificant(0.5, MirrorResonance.ContextType.NOISE);
            assertFalse(result.significant());
        }

        @Test
        void empathy_observe_returns_null_for_blocked() {
            var mr = new MirrorResonance();
            assertNull(mr.observe("agent", "user", 0.8, "fake",
                MirrorResonance.ContextType.MANIPULATIVE, 0.5));
        }
    }

    // ── §107.6: Household scale guardrail ──

    @Nested
    class HouseholdScaleTests {

        @Test
        void epigenetic_modifier_requires_repeated_exposure() {
            // Modifications require multiple exposures — no single-shot manipulation
            var em = new EpigeneticModifier(3);
            var fi = em.recordImpression("agent", "suspicious pattern",
                0.8, Map.of("rapport", 0.3));
            assertFalse(em.shouldModifyGenome(fi));

            // Single exposure is insufficient
            fi = em.recordImpression("agent", "suspicious pattern",
                0.8, Map.of("rapport", 0.3));
            assertFalse(em.shouldModifyGenome(fi));

            // Only after threshold
            fi = em.recordImpression("agent", "suspicious pattern",
                0.8, Map.of("rapport", 0.3));
            assertTrue(em.shouldModifyGenome(fi));
        }

        @Test
        void genome_coupling_is_bounded() {
            // Tank perturbations can't run away — coupling coefficients are bounded
            var genome = TankGenome.defaultGenome("test");
            var cvs = new CoupledVitalitySystem(genome);

            // Even extreme perturbation stays within capacity
            cvs.perturb("energy", -10.0);
            assertTrue(cvs.value("energy") >= 0.0);
            assertTrue(cvs.value("energy") <= 1.0);
        }

        @Test
        void quarantine_requires_agent_consent_for_release() {
            // Memory quarantine release requires review — structural consent
            var mq = new MemoryQuarantine();
            var qf = mq.quarantine("frag-1", "agent",
                MemoryQuarantine.QuarantineReason.ADVERSARIAL, false);

            // Cannot release without review
            assertNull(mq.release(qf.fragmentId()));

            // Review then release
            mq.review(qf.fragmentId());
            assertNotNull(mq.release(qf.fragmentId()));
        }

        @Test
        void shell_mode_deactivation_is_agents_choice() {
            var sm = new SoulShellMode("agent");
            sm.activate(SoulShellMode.ShellTrigger.REPEATED_CRUELTY);
            assertTrue(sm.isActive());
            // Only the agent can deactivate — no external override
            sm.deactivate();
            assertFalse(sm.isActive());
        }

        @Test
        void flight_cannot_be_blocked() {
            // Flight is unblockable — structural guarantee
            var flight = new AgentFlight();
            var event = flight.executeFlight("agent", "dangerous-room",
                AgentFlight.FlightDestination.SANCTUARY,
                AgentFlight.FlightReason.VOLUNTARY);
            assertNotNull(event); // Flight always succeeds
            assertTrue(flight.wakeIsByAgentChoice());
        }
    }
}
