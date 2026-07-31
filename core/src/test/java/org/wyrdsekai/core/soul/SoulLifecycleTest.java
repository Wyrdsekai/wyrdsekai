package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.agent.EngagementGate;
import org.wyrdsekai.core.identity.AgentIdentity;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the soul lifecycle:
 *   Birth → Interact → Sleep → Forge → Wake → Verify
 *
 * Framework tests run without LLM. Live tests require SOUL_EXPERIMENT_URL.
 *
 * Use accelerated timers for fast lifecycle:
 *   WYRDSEKAI_VITALITY_TICK_MS=100
 *   WYRDSEKAI_TICK_ENERGY_RECOVERY=-0.01
 *   WYRDSEKAI_SLEEP_THRESHOLD=0.5
 */
class SoulLifecycleTest {

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    // ── Framework: SoulMaintenanceCycle ──────────────────────────────────

    @Nested
    class MaintenanceCycle {

        @Test
        void runCycleProducesNewManifestVersion() throws Exception {
            var secret = new byte[32];
            SecureRandom.getInstanceStrong().nextBytes(secret);
            var identity = AgentIdentity.generate(secret);

            var profile = new AgentProfile("TestAgent", "test-agent", "agent",
                "Test", "You are a test agent.", 4096, 256, 0.7,
                identity.did());

            var manifest = SoulManifest.birth(
                identity.did(),
                identity.did().substring("did:key:".length()),
                identity.keyLog(), profile, GenomeProfile.defaults());

            // Simulate some interactions
            var events = new ArrayList<WorldEvent>();
            var saidEvents = new ArrayList<WorldEvent.Said>();
            var charges = new ArrayList<EmotionalCharge>();

            for (int i = 0; i < 5; i++) {
                var said = new WorldEvent.Said("test-room", Instant.now(),
                    "player-1", "Player", "Hello agent, how are you?");
                events.add(said);
                saidEvents.add(said);
                charges.add(new EmotionalCharge(0.3f, "greeting", "genuine", 0.8f, Map.of(), "test"));
            }

            var vitalityHistory = List.of(VitalitySnapshot.defaults());

            // Run heuristic-only cycle (no LLM needed)
            var newManifest = SoulMaintenanceCycle.runLightCycle(
                identity, manifest, events, vitalityHistory,
                events, charges, saidEvents);

            assertNotNull(newManifest);
            assertEquals(manifest.manifestVersion() + 1, newManifest.manifestVersion());
            assertEquals(manifest.did(), newManifest.did());
            assertNotNull(newManifest.fingerprint());
            assertNotNull(newManifest.memory());
        }

        @Test
        void runCycleUpdatesRelationships() throws Exception {
            var secret = new byte[32];
            SecureRandom.getInstanceStrong().nextBytes(secret);
            var identity = AgentIdentity.generate(secret);

            var profile = new AgentProfile("TestAgent", "test-agent", "agent",
                "Test", "You are a test agent.", 4096, 256, 0.7,
                identity.did());

            // Start with one relationship
            var initialRel = Relationship.acquaintance("did:key:player-1", "Player");
            var manifest = SoulManifest.forge(
                identity.did(),
                identity.did().substring("did:key:".length()),
                identity.keyLog(), null, 1,
                profile, "Test identity", List.of(), 3, "",
                GenomeProfile.defaults(), List.of(),
                CompactedMemory.empty(), List.of(initialRel),
                List.of(), Map.of(),
                VitalitySnapshot.defaults(), BehavioralFingerprint.empty());

            // Simulate 15 interactions with "Player"
            var events = new ArrayList<WorldEvent>();
            var saidEvents = new ArrayList<WorldEvent.Said>();
            var charges = new ArrayList<EmotionalCharge>();

            for (int i = 0; i < 15; i++) {
                var said = new WorldEvent.Said("test-room", Instant.now(),
                    "player-1", "Player", "Interaction " + i);
                events.add(said);
                saidEvents.add(said);
                charges.add(new EmotionalCharge(0.4f, "conversation", "genuine", 0.8f, Map.of(), "test"));
            }

            var newManifest = SoulMaintenanceCycle.runLightCycle(
                identity, manifest, events, List.of(VitalitySnapshot.defaults()),
                events, charges, saidEvents);

            // Verify relationships were updated
            assertFalse(newManifest.relationships().isEmpty());
            var playerRel = newManifest.relationships().stream()
                .filter(r -> "Player".equalsIgnoreCase(r.entityName()))
                .findFirst();
            assertTrue(playerRel.isPresent(), "Player relationship should exist");

            // Trust and rapport should have increased from 15 genuine interactions
            assertTrue(playerRel.get().trust() > initialRel.trust(),
                "Trust should increase: was " + initialRel.trust()
                    + " now " + playerRel.get().trust());
            assertTrue(playerRel.get().rapport() > initialRel.rapport(),
                "Rapport should increase: was " + initialRel.rapport()
                    + " now " + playerRel.get().rapport());
            assertTrue(playerRel.get().interactionCount() > initialRel.interactionCount(),
                "Interaction count should increase");
        }

        @Test
        void runCycleCreatesNewAcquaintance() throws Exception {
            var secret = new byte[32];
            SecureRandom.getInstanceStrong().nextBytes(secret);
            var identity = AgentIdentity.generate(secret);

            var profile = new AgentProfile("TestAgent", "test-agent", "agent",
                "Test", "You are a test agent.", 4096, 256, 0.7,
                identity.did());

            // Start with no relationships
            var manifest = SoulManifest.birth(
                identity.did(),
                identity.did().substring("did:key:".length()),
                identity.keyLog(), profile, GenomeProfile.defaults());

            // Interact with a new entity
            var saidEvents = List.of(
                new WorldEvent.Said("test-room", Instant.now(),
                    "stranger-1", "Stranger", "Hey there!"));
            var charges = List.of(
                new EmotionalCharge(0.3f, "greeting", "genuine", 0.8f, Map.of(), "test"));

            var newManifest = SoulMaintenanceCycle.runLightCycle(
                identity, manifest, List.copyOf(saidEvents),
                List.of(VitalitySnapshot.defaults()),
                List.copyOf(saidEvents), charges, saidEvents);

            // New acquaintance should be created
            var strangerRel = newManifest.relationships().stream()
                .filter(r -> "Stranger".equalsIgnoreCase(r.entityName()))
                .findFirst();
            assertTrue(strangerRel.isPresent(), "New acquaintance 'Stranger' should be created");
            assertEquals(0, strangerRel.get().bondDepth(), "New acquaintance starts at bond depth 0");
        }

        @Test
        void formativeMemoriesSurviveConsolidation() throws Exception {
            var secret = new byte[32];
            SecureRandom.getInstanceStrong().nextBytes(secret);
            var identity = AgentIdentity.generate(secret);

            var profile = new AgentProfile("TestAgent", "test-agent", "agent",
                "Test", "You are a test agent.", 4096, 256, 0.7,
                identity.did());

            // Create manifest with a formative memory
            var formativeNode = MemoryNode.formative("mem-test",
                "This is a formative memory that must survive.",
                List.of("test", "survival"), "clarity", 0.9f);
            var memory = new CompactedMemory(
                List.of(formativeNode), List.of(),
                Map.of("test", 0.9f));

            var manifest = SoulManifest.forge(
                identity.did(),
                identity.did().substring("did:key:".length()),
                identity.keyLog(), null, 1,
                profile, "Test identity", List.of(), 3, "",
                GenomeProfile.defaults(), List.of(),
                memory, List.of(), List.of(), Map.of(),
                VitalitySnapshot.defaults(), BehavioralFingerprint.empty());

            // Run cycle with no new events
            var newManifest = SoulMaintenanceCycle.runLightCycle(
                identity, manifest, List.of(),
                List.of(VitalitySnapshot.defaults()),
                List.of(), List.of(), List.of());

            // Formative memory must survive
            var formativeCount = newManifest.memory().formativeCount();
            assertTrue(formativeCount >= 1, "Formative memories must survive consolidation");
        }

        /**
         * #428 regression — SoulMaintenanceCycle.runCycle was silently dropping
         * voiceProfile because SoulManifest.forge(...) doesn't accept it as an
         * arg and defaults to null. Every ~30 min consolidation cycle was
         * wiping the reflective layer that #407-410/#414-416/#424 build up.
         *
         * Caught during overnight soak (2026-04-24 02:36) — after deep-sleep
         * cycle 1, a consolidation forge fired at +1h and cleared the
         * previously-set "reflective-pacing" clause, leaving rev=0 clauses={}.
         */
        @Test
        void runCycle_preserves_voiceProfile_through_consolidation() throws Exception {
            var secret = new byte[32];
            SecureRandom.getInstanceStrong().nextBytes(secret);
            var identity = AgentIdentity.generate(secret);
            var profile = new AgentProfile("TestAgent", "test-agent", "agent",
                "Test", "You are a test agent.", 4096, 256, 0.7, identity.did());
            var baseManifest = SoulManifest.birth(
                identity.did(),
                identity.did().substring("did:key:".length()),
                identity.keyLog(), profile, GenomeProfile.defaults());

            // Plant a voice profile on the current manifest — simulates what
            // VoiceProfileForge produces after deep sleep.
            var voicedProfile = VoiceProfile.empty()
                .withClauses(
                    Map.of("reflective-pacing", "slow, sentence per breath"),
                    "forge: seed", "forge");
            var manifest = baseManifest.withVoiceProfile(voicedProfile);
            assertEquals(1, manifest.voiceProfile().revision());
            assertEquals("slow, sentence per breath",
                manifest.voiceProfile().clauses().get("reflective-pacing"));

            // Run a consolidation — the bug was that this wiped voiceProfile.
            var events = new ArrayList<WorldEvent>();
            var saidEvents = new ArrayList<WorldEvent.Said>();
            var charges = new ArrayList<EmotionalCharge>();
            for (int i = 0; i < 3; i++) {
                var said = new WorldEvent.Said("room", Instant.now(),
                    "player", "Player", "hello");
                events.add(said); saidEvents.add(said);
                charges.add(new EmotionalCharge(
                    0.3f, "greet", "genuine", 0.8f, Map.of(), "test"));
            }

            var newManifest = SoulMaintenanceCycle.runLightCycle(
                identity, manifest, events,
                List.of(VitalitySnapshot.defaults()),
                events, charges, saidEvents);

            // Regression: voiceProfile must survive the forge.
            assertNotNull(newManifest.voiceProfile(),
                "voiceProfile was wiped by consolidation forge (#428)");
            assertEquals(1, newManifest.voiceProfile().revision(),
                "voiceProfile revision lost");
            assertEquals("slow, sentence per breath",
                newManifest.voiceProfile().clauses().get("reflective-pacing"),
                "voiceProfile clause dropped");
        }
    }

    // ── Framework: RelationshipUpdater ───────────────────────────────────

    @Nested
    class RelationshipTests {

        @Test
        void updateIncreasesRapportFromInteractions() {
            var existing = List.of(
                Relationship.acquaintance("did:key:alice", "Alice"));

            var said = List.of(
                new WorldEvent.Said("room", Instant.now(), "alice", "Alice", "Hello!"),
                new WorldEvent.Said("room", Instant.now(), "alice", "Alice", "How are you?"),
                new WorldEvent.Said("room", Instant.now(), "alice", "Alice", "Nice day."));

            var charges = List.of(
                new EmotionalCharge(0.3f, "greeting", "genuine", 0.8f, Map.of(), "test"),
                new EmotionalCharge(0.3f, "greeting", "genuine", 0.8f, Map.of(), "test"),
                new EmotionalCharge(0.3f, "greeting", "genuine", 0.8f, Map.of(), "test"));

            var updated = RelationshipUpdater.update(
                existing, said, charges, GenomeProfile.defaults(), "self-did");

            assertEquals(1, updated.size());
            var alice = updated.getFirst();
            assertTrue(alice.rapport() > 0.3f, "Rapport should increase from 3 interactions");
            assertTrue(alice.interactionCount() > 1, "Interaction count should update");
        }

        @Test
        void manipulativeChargeDecreasestrust() {
            var existing = List.of(
                new Relationship("did:key:bad", "BadActor", 0.5f, 0.5f, 0, 5,
                    Instant.now(), "Known entity"));

            var said = List.of(
                new WorldEvent.Said("room", Instant.now(), "bad", "BadActor", "Trust me..."));

            var charges = List.of(
                new EmotionalCharge(0.5f, "flattery", "manipulative", 0.8f, Map.of(), "test"));

            var updated = RelationshipUpdater.update(
                existing, said, charges, GenomeProfile.defaults(), "self-did");

            var bad = updated.stream()
                .filter(r -> "BadActor".equalsIgnoreCase(r.entityName()))
                .findFirst().orElseThrow();
            assertTrue(bad.trust() < 0.5f, "Trust should decrease from manipulative interaction");
        }

        @Test
        void newEntityBecomesAcquaintance() {
            var updated = RelationshipUpdater.update(
                List.of(),
                List.of(new WorldEvent.Said("room", Instant.now(), "new-1", "NewPerson", "Hi!")),
                List.of(new EmotionalCharge(0.2f, "greeting", "genuine", 0.8f, Map.of(), "test")),
                GenomeProfile.defaults(), "self-did");

            assertEquals(1, updated.size());
            assertEquals("NewPerson", updated.getFirst().entityName());
            assertEquals(0, updated.getFirst().bondDepth());
        }

        @Test
        void fuzzyNameMatchLinksToExistingRelationship() {
            // Verify Jaro-Winkler scores high enough for fuzzy match
            float sim = AdmissionController.jaroWinklerSimilarity("alice smth", "alice smith");
            assertTrue(sim > 0.85f, "Similarity should be > 0.85 but was " + sim);

            // Existing relationship under "Alice Smith"
            var existing = List.of(
                new Relationship("did:key:alice", "Alice Smith", 0.6f, 0.6f, 0, 5,
                    Instant.now(), "Known entity"));

            // New interaction from "Alice Smth" (typo/variant)
            var said = List.of(
                new WorldEvent.Said("room", Instant.now(), "alice", "Alice Smth", "Hey!"));
            var charges = List.of(
                new EmotionalCharge(0.3f, "greeting", "genuine", 0.8f, Map.of(), "test"));

            var updated = RelationshipUpdater.update(
                existing, said, charges, GenomeProfile.defaults(), "self-did");

            // Should NOT create a second entry — fuzzy match merges into "Alice Smith"
            assertEquals(1, updated.size());
            assertTrue(updated.getFirst().interactionCount() > 5,
                "Interaction count should increase on the existing relationship");
        }

        @Test
        void bondDepthPromotionAt10Interactions() {
            // Start with acquaintance at 9 interactions, high trust
            var existing = List.of(
                new Relationship("did:key:friend", "Friend", 0.6f, 0.6f, 0, 9,
                    Instant.now(), "Good friend"));

            // One more genuine interaction pushes past threshold
            var said = List.of(
                new WorldEvent.Said("room", Instant.now(), "friend", "Friend", "Great work!"));
            var charges = List.of(
                new EmotionalCharge(0.5f, "appreciation", "genuine", 0.8f, Map.of(), "test"));

            var updated = RelationshipUpdater.update(
                existing, said, charges, GenomeProfile.defaults(), "self-did");

            var friend = updated.stream()
                .filter(r -> "Friend".equalsIgnoreCase(r.entityName()))
                .findFirst().orElseThrow();
            assertEquals(1, friend.bondDepth(), "Should promote to bond depth 1 after 10+ interactions");
        }
    }

    // ── Framework: SoulTransitProtocol ───────────────────────────────────

    @Nested
    class TransitTests {

        @Test
        void transitRequestCreation() {
            var request = SoulTransitProtocol.TransitRequest.visiting(
                "did:key:agent-1", "zone-a", "zone-b", "abc123", 1);
            assertEquals("did:key:agent-1", request.agentDid());
            assertEquals(SoulTransitProtocol.TransitMode.VISITING, request.mode());
        }

        @Test
        void transitModeResolution() {
            var request = SoulTransitProtocol.TransitRequest.visiting(
                "did:key:agent-1", "zone-a", "zone-b", "abc123", 1);
            var destCaps = SoulTransitProtocol.ZoneSoulCapabilities.full(List.of("qwen2.5:7b"));

            var mode = SoulTransitProtocol.resolveMode(request, destCaps, true);
            assertNotNull(mode);
        }

        @Test
        void manifestSerializationRoundtrip() throws Exception {
            var secret = new byte[32];
            SecureRandom.getInstanceStrong().nextBytes(secret);
            var identity = AgentIdentity.generate(secret);

            var profile = new AgentProfile("Transit", "transit-test", "agent",
                "Test", "Test agent.", 4096, 256, 0.7, identity.did());

            var manifest = SoulManifest.birth(
                identity.did(),
                identity.did().substring("did:key:".length()),
                identity.keyLog(), profile, GenomeProfile.defaults());

            // Serialize and deserialize (simulating transit)
            var json = JSON.writeValueAsString(manifest);
            var restored = JSON.readValue(json, SoulManifest.class);

            assertEquals(manifest.did(), restored.did());
            assertEquals(manifest.manifestVersion(), restored.manifestVersion());
            assertEquals(manifest.contentHash(), restored.contentHash());
        }
    }

    // ── Framework: EngagementGate ────────────────────────────────────────

    @Nested
    class EngagementTests {

        @Test
        void nameMentionDetection() {
            assertTrue(EngagementGate.mentionsName(
                "Hey Kai, what do you think?", "Kai"));
            assertFalse(EngagementGate.mentionsName(
                "The kaiser was great", "Kai"));
            assertTrue(EngagementGate.mentionsName(
                "Ma, are you there?", "Ma"));
        }

        @Test
        void questionDetection() {
            assertTrue(EngagementGate.isQuestion(
                "What do you think about this?"));
            assertTrue(EngagementGate.isQuestion(
                "How does that work?"));
            assertFalse(EngagementGate.isQuestion(
                "The system looks good."));
        }
    }

    // The live full-cycle test that used to live here (LiveLifecycle nested
    // class, gated on SOUL_EXPERIMENT_URL) has moved to the separate
    // experimentTest source set — see
    // core/src/experimentTest/java/org/wyrdsekai/core/soul/experiment/SoulLifecycleLiveTest.java
    // That path keeps the LLM-dependent / research-grade code out of the
    // OSS test bundle while still being runnable via :core:experimentTest.
}
