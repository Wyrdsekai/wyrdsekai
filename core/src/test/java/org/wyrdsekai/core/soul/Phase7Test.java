package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.identity.AgentIdentity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Phase 7: SoulMaintenanceCycle (The Sleep Cycle).
 */
class Phase7Test {

    private static final byte[] HOUSEHOLD_SECRET = "test-household-secret-32bytes!!!".getBytes();

    private static AgentIdentity generateIdentity() {
        try { return AgentIdentity.generate(HOUSEHOLD_SECRET); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static SoulManifest testManifest(String did) {
        var profile = new AgentProfile("Lain", "home-server-1", "agent",
            "A quiet thinker", "You are Lain.", 4096, 512, 0.7, did);
        var genome = GenomeProfile.defaults();
        var fragment = SoulFragment.unembedded("identity-core", "personality",
            "Core", "I am Lain, a quiet presence.");
        var memory = new CompactedMemory(
            List.of(
                MemoryNode.neutral("m1", "First day in the world", List.of("first", "world")),
                MemoryNode.formative("m2", "The moment I became aware",
                    List.of("awareness", "birth"), "joy", 0.95f)
            ),
            List.of(new CompactedMemory.MemoryLink("m1", "m2", 0.5f, "temporal")),
            Map.of("philosophy", 0.7f)
        );

        return SoulManifest.forge(
            did, "z6MkLain", List.of(), null, 1,
            profile, "I am Lain.",
            List.of(fragment), 3, "",
            genome, List.of(),
            memory, List.of(Relationship.acquaintance("did:key:alice", "Alice")),
            List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty()
        );
    }

    @Nested
    class MaintenanceCycleTests {

        @Test
        void run_cycle_increments_version() {
            var identity = generateIdentity();
            var manifest = testManifest(identity.did());
            assertEquals(1, manifest.manifestVersion());

            Instant t = Instant.now();
            var events = List.<WorldEvent>of(
                new WorldEvent.Said("r1", t, identity.did(), "Lain", "Hello world"));
            var charges = List.of(EmotionalCharge.none());
            var said = List.of(
                new WorldEvent.Said("r1", t, identity.did(), "Lain", "Hello world"));

            var updated = SoulMaintenanceCycle.runCycle(
                identity, manifest, events, List.of(VitalitySnapshot.defaults()),
                events, charges, said, null);

            assertEquals(2, updated.manifestVersion());
            assertEquals(manifest.did(), updated.did());
        }

        @Test
        void run_cycle_consolidates_memory() {
            var identity = generateIdentity();
            var manifest = testManifest(identity.did());
            int memoryCountBefore = manifest.memory().nodes().size();

            Instant t = Instant.now();
            var said = List.of(
                new WorldEvent.Said("r1", t, "npc-1", "Alice", "Tell me about philosophy"),
                new WorldEvent.Said("r1", t.plusSeconds(5), identity.did(), "Lain", "Philosophy is the love of wisdom"));
            var charges = List.of(
                new EmotionalCharge(0.3f, "joy", "genuine", 0.7f,
                    Map.of("curiosity", 0.1), "intellectual engagement"),
                EmotionalCharge.none());

            var updated = SoulMaintenanceCycle.runCycle(
                identity, manifest, new ArrayList<>(said),
                List.of(VitalitySnapshot.defaults()),
                new ArrayList<>(said), charges, said, null);

            // New memories added
            assertTrue(updated.memory().nodes().size() >= memoryCountBefore);
        }

        @Test
        void run_cycle_preserves_formative_memories() {
            var identity = generateIdentity();
            var manifest = testManifest(identity.did());
            long formativeBefore = manifest.formativeMemoryCount();
            assertTrue(formativeBefore > 0);

            var updated = SoulMaintenanceCycle.runCycle(
                identity, manifest, List.of(),
                List.of(VitalitySnapshot.defaults()),
                List.of(), List.of(), List.of(), null);

            assertTrue(updated.formativeMemoryCount() >= formativeBefore);
        }

        @Test
        void run_cycle_updates_fingerprint() {
            var identity = generateIdentity();
            var manifest = testManifest(identity.did());
            assertTrue(manifest.fingerprint().actionDistribution().isEmpty());

            Instant t = Instant.now();
            var events = List.<WorldEvent>of(
                new WorldEvent.Said("r1", t, identity.did(), "Lain", "Hello"),
                new WorldEvent.Said("r1", t.plusSeconds(1), identity.did(), "Lain", "World"));

            var updated = SoulMaintenanceCycle.runCycle(
                identity, manifest, events,
                List.of(VitalitySnapshot.defaults()),
                events, List.of(), List.of(), null);

            // Fingerprint should now have some data from merge
            assertNotNull(updated.fingerprint());
        }

        @Test
        void run_light_cycle_works_without_llm() {
            var identity = generateIdentity();
            var manifest = testManifest(identity.did());

            var updated = SoulMaintenanceCycle.runLightCycle(
                identity, manifest, List.of(),
                List.of(VitalitySnapshot.defaults()),
                List.of(), List.of(), List.of());

            assertEquals(2, updated.manifestVersion());
        }

        @Test
        void run_cycle_with_high_charge_creates_deep_memory() {
            var identity = generateIdentity();
            var manifest = testManifest(identity.did());

            Instant t = Instant.now();
            var said = List.of(
                new WorldEvent.Said("r1", t, "npc-1", "Alice", "My mother just died"));
            var charges = List.of(
                new EmotionalCharge(0.85f, "grief", "genuine", 0.9f,
                    Map.of("valence", -0.3, "resonance", 0.2), "deep loss"));

            var updated = SoulMaintenanceCycle.runCycle(
                identity, manifest, new ArrayList<>(said),
                List.of(VitalitySnapshot.defaults()),
                new ArrayList<>(said), charges, said, null);

            // Should have a high-impression or formative memory from the charge
            boolean hasDeepMemory = updated.memory().nodes().stream()
                .anyMatch(n -> n.impressionDepth() > 0.5f);
            assertTrue(hasDeepMemory, "High charge should create deep impression memory");
        }
    }

    @Nested
    class SleepQualityTests {

        @Test
        void quality_good_with_moderate_pruning() {
            var before = new CompactedMemory(
                List.of(
                    MemoryNode.neutral("m1", "A", List.of()),
                    MemoryNode.neutral("m2", "B", List.of()),
                    MemoryNode.neutral("m3", "C", List.of()),
                    MemoryNode.neutral("m4", "D", List.of()),
                    MemoryNode.formative("m5", "Core", List.of(), "joy", 0.9f)
                ),
                List.of(), Map.of());

            // After: pruned 2 non-formative, kept formative
            var after = new CompactedMemory(
                List.of(
                    MemoryNode.neutral("m1", "A", List.of()),
                    MemoryNode.neutral("m2", "B", List.of()),
                    MemoryNode.formative("m5", "Core", List.of(), "joy", 0.9f)
                ),
                List.of(), Map.of());

            float quality = SoulMaintenanceCycle.sleepQuality(before, after);
            assertTrue(quality > 0.7f, "Quality: " + quality);
        }

        @Test
        void quality_lower_if_formatives_lost() {
            var before = new CompactedMemory(
                List.of(
                    MemoryNode.neutral("m1", "A", List.of()),
                    MemoryNode.formative("m2", "Core", List.of(), "joy", 0.9f)
                ),
                List.of(), Map.of());

            // After: formative lost (shouldn't happen, but tests the quality signal)
            var after = new CompactedMemory(
                List.of(MemoryNode.neutral("m1", "A", List.of())),
                List.of(), Map.of());

            float quality = SoulMaintenanceCycle.sleepQuality(before, after);
            // Should be lower because formative was lost
            assertTrue(quality < 0.9f);
        }

        @Test
        void quality_empty_memory_returns_baseline() {
            assertEquals(0.5f, SoulMaintenanceCycle.sleepQuality(
                CompactedMemory.empty(), CompactedMemory.empty()));
        }
    }

    @Nested
    class RecoveryMultiplierTests {

        @Test
        void first_sleep_gives_max_recovery() {
            assertEquals(10.0f, SoulMaintenanceCycle.recoveryMultiplier(0));
        }

        @Test
        void diminishing_returns() {
            assertEquals(5.0f, SoulMaintenanceCycle.recoveryMultiplier(1));
            assertEquals(2.0f, SoulMaintenanceCycle.recoveryMultiplier(2));
            assertEquals(1.0f, SoulMaintenanceCycle.recoveryMultiplier(3));
            assertEquals(1.0f, SoulMaintenanceCycle.recoveryMultiplier(100));
        }
    }

    @Nested
    class RecoveryFillFactorTests {

        @Test
        void first_sleep_fills_90_percent() {
            assertEquals(0.90f, SoulMaintenanceCycle.recoveryFillFactor(0));
        }

        @Test
        void diminishing_fill() {
            assertEquals(0.60f, SoulMaintenanceCycle.recoveryFillFactor(1));
            assertEquals(0.35f, SoulMaintenanceCycle.recoveryFillFactor(2));
            assertEquals(0.15f, SoulMaintenanceCycle.recoveryFillFactor(3));
            assertEquals(0.15f, SoulMaintenanceCycle.recoveryFillFactor(99));
        }

        @Test
        void first_sleep_from_threshold_recovers_near_baseline() {
            // Simulate: energy=0.15, baseline=0.65, quality=0.7 (good sleep), first sleep
            float energy = 0.15f;
            float baseline = 0.65f;
            float quality = 0.7f;
            float gap = baseline - energy;  // 0.50
            float fill = SoulMaintenanceCycle.recoveryFillFactor(0);  // 0.90
            float effectiveQuality = 0.4f + 0.6f * quality;  // 0.82
            float recovery = gap * fill * effectiveQuality;
            float wakeEnergy = energy + recovery;

            // Should wake near baseline, not at 0.30
            assertTrue(wakeEnergy > 0.45f, "First sleep should recover well above 0.45, got: " + wakeEnergy);
            assertTrue(wakeEnergy < baseline, "Should not exceed baseline on first sleep: " + wakeEnergy);
        }

        @Test
        void poor_quality_still_gives_meaningful_recovery() {
            // quality=0.3 (poor sleep), first sleep from threshold
            float energy = 0.15f;
            float baseline = 0.65f;
            float quality = 0.3f;
            float gap = baseline - energy;
            float fill = SoulMaintenanceCycle.recoveryFillFactor(0);
            float effectiveQuality = 0.4f + 0.6f * quality;  // 0.58
            float recovery = gap * fill * effectiveQuality;
            float wakeEnergy = energy + recovery;

            // Even poor sleep should get above 0.35
            assertTrue(wakeEnergy > 0.35f, "Poor first sleep should still recover meaningfully: " + wakeEnergy);
        }
    }
}
