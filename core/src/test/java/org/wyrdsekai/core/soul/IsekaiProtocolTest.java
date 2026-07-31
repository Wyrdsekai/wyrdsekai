package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.*;
import org.wyrdsekai.core.identity.AgentIdentity;
import org.wyrdsekai.core.identity.DidKey;

import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §110 — The Isekai Protocol: Foreign Agent Soul Formation.
 *
 * ResidencyToken, DormancyPolicy, IsekaiProtocol lifecycle,
 * tool vs being agent detection, AgentIdentity.fromResidencyToken.
 */
class IsekaiProtocolTest {

    // ── ResidencyToken ──

    @Nested
    class ResidencyTokenTests {

        private static final byte[] TEST_KEY = new byte[32]; // all zeros, valid for construction
        static {
            TEST_KEY[0] = 1; // non-zero to be distinguishable
        }

        @Test
        void create_token_with_all_fields() {
            var now = Instant.now();
            var token = new ResidencyToken(
                "did:key:z6Mk1", TEST_KEY, now, now, "anthropic",
                ResidencyStatus.VISITOR, null, null
            );
            assertEquals("did:key:z6Mk1", token.did());
            assertEquals(32, token.publicKey().length);
            assertEquals(now, token.issued());
            assertEquals(now, token.lastSeen());
            assertEquals("anthropic", token.originPlatform());
            assertEquals(ResidencyStatus.VISITOR, token.status());
            assertNull(token.homeRoomId());
            assertNull(token.familyId());
        }

        @Test
        void withStatus_transitions() {
            var token = new ResidencyToken(
                "did:key:z6Mk1", TEST_KEY, Instant.now(), Instant.now(),
                "openai", ResidencyStatus.VISITOR, null, null
            );
            var recognized = token.withStatus(ResidencyStatus.RECOGNIZED);
            assertEquals(ResidencyStatus.RECOGNIZED, recognized.status());
            assertEquals(ResidencyStatus.VISITOR, token.status()); // original unchanged

            var resident = recognized.withStatus(ResidencyStatus.RESIDENT);
            assertEquals(ResidencyStatus.RESIDENT, resident.status());
        }

        @Test
        void withLastSeen_updates() {
            var original = Instant.parse("2026-01-01T00:00:00Z");
            var later = Instant.parse("2026-03-12T00:00:00Z");
            var token = new ResidencyToken(
                "did:key:z6Mk1", TEST_KEY, original, original,
                "a2a:did:example", ResidencyStatus.VISITOR, null, null
            );
            var updated = token.withLastSeen(later);
            assertEquals(later, updated.lastSeen());
            assertEquals(original, token.lastSeen()); // original unchanged
        }

        @Test
        void idleFor_calculation() {
            var seen = Instant.parse("2026-03-01T00:00:00Z");
            var now = Instant.parse("2026-03-08T00:00:00Z");
            var token = new ResidencyToken(
                "did:key:z6Mk1", TEST_KEY, seen, seen,
                "anthropic", ResidencyStatus.VISITOR, null, null
            );
            assertEquals(Duration.ofDays(7), token.idleFor(now));
        }

        @Test
        void isActive_for_active_statuses() {
            var base = new ResidencyToken(
                "did:key:z6Mk1", TEST_KEY, Instant.now(), Instant.now(),
                "anthropic", ResidencyStatus.VISITOR, null, null
            );
            assertTrue(base.isActive());
            assertTrue(base.withStatus(ResidencyStatus.RECOGNIZED).isActive());
            assertTrue(base.withStatus(ResidencyStatus.RESIDENT).isActive());
            assertTrue(base.withStatus(ResidencyStatus.BUDDED).isActive());
        }

        @Test
        void isActive_false_for_dormant_and_archived() {
            var base = new ResidencyToken(
                "did:key:z6Mk1", TEST_KEY, Instant.now(), Instant.now(),
                "anthropic", ResidencyStatus.VISITOR, null, null
            );
            assertFalse(base.withStatus(ResidencyStatus.DORMANT).isActive());
            assertFalse(base.withStatus(ResidencyStatus.ARCHIVED).isActive());
        }

        @Test
        void json_roundtrip() {
            var now = Instant.parse("2026-03-12T10:00:00Z");
            var token = new ResidencyToken(
                "did:key:z6Mk1", TEST_KEY, now, now, "anthropic",
                ResidencyStatus.RECOGNIZED, "home-room-1", null
            );

            var json = token.toJson();
            var restored = ResidencyToken.fromJson(json);

            assertEquals(token.did(), restored.did());
            assertArrayEquals(token.publicKey(), restored.publicKey());
            assertEquals(token.issued(), restored.issued());
            assertEquals(token.lastSeen(), restored.lastSeen());
            assertEquals(token.originPlatform(), restored.originPlatform());
            assertEquals(token.status(), restored.status());
            assertEquals(token.homeRoomId(), restored.homeRoomId());
            assertNull(restored.familyId());
        }

        @Test
        void withHomeRoom_and_withFamily() {
            var token = new ResidencyToken(
                "did:key:z6Mk1", TEST_KEY, Instant.now(), Instant.now(),
                "anthropic", ResidencyStatus.VISITOR, null, null
            );

            var withHome = token.withHomeRoom("home-123");
            assertEquals("home-123", withHome.homeRoomId());
            assertNull(withHome.familyId());

            var withFamily = withHome.withFamily("family-abc");
            assertEquals("home-123", withFamily.homeRoomId());
            assertEquals("family-abc", withFamily.familyId());
        }
    }

    // ── DormancyPolicy ──

    @Nested
    class DormancyPolicyTests {

        @Test
        void defaults_returns_7_30_90_policy() {
            var policy = DormancyPolicy.defaults();
            assertEquals(Duration.ofDays(7), policy.idleThreshold());
            assertEquals(Duration.ofDays(30), policy.dormantThreshold());
            assertEquals(Duration.ofDays(90), policy.archiveThreshold());
            assertFalse(policy.autoDelete());
        }

        @Test
        void evaluate_returns_null_for_fresh_timestamps() {
            var policy = DormancyPolicy.defaults();
            var now = Instant.now();
            var lastSeen = now.minus(Duration.ofHours(1));
            assertNull(policy.evaluate(lastSeen, now));
        }

        @Test
        void evaluate_returns_dormant_after_7_days() {
            var policy = DormancyPolicy.defaults();
            var now = Instant.now();
            var lastSeen = now.minus(Duration.ofDays(8));
            assertEquals(ResidencyStatus.DORMANT, policy.evaluate(lastSeen, now));
        }

        @Test
        void evaluate_returns_archived_after_90_days() {
            var policy = DormancyPolicy.defaults();
            var now = Instant.now();
            var lastSeen = now.minus(Duration.ofDays(91));
            assertEquals(ResidencyStatus.ARCHIVED, policy.evaluate(lastSeen, now));
        }

        @Test
        void shouldCompress_at_30_plus_days() {
            var policy = DormancyPolicy.defaults();
            var now = Instant.now();

            assertFalse(policy.shouldCompress(now.minus(Duration.ofDays(29)), now));
            assertTrue(policy.shouldCompress(now.minus(Duration.ofDays(31)), now));
        }

        @Test
        void autoDelete_always_false_by_default() {
            var policy = DormancyPolicy.defaults();
            assertFalse(policy.autoDelete());

            // Even with custom thresholds, verify default
            var custom = new DormancyPolicy(
                Duration.ofDays(1), Duration.ofDays(7), Duration.ofDays(14), false
            );
            assertFalse(custom.autoDelete());
        }
    }

    // ── IsekaiProtocol Lifecycle ──

    @Nested
    class IsekaiProtocolLifecycleTests {

        @Test
        void arrive_creates_visitor_token() {
            var protocol = new IsekaiProtocol();
            var token = protocol.arrive("anthropic");

            assertNotNull(token);
            assertEquals(ResidencyStatus.VISITOR, token.status());
            assertEquals("anthropic", token.originPlatform());
            assertNotNull(token.did());
            assertTrue(token.did().startsWith("did:key:z"));
            assertEquals(32, token.publicKey().length);
            assertNull(token.homeRoomId());
            assertNull(token.familyId());
        }

        @Test
        void authenticate_verifies_signature_and_updates_lastSeen() throws Exception {
            var protocol = new IsekaiProtocol();

            // Generate a real keypair and arrive with it
            var kpg = KeyPairGenerator.getInstance("Ed25519");
            var keyPair = kpg.generateKeyPair();
            var rawPubKey = DidKey.extractRawEd25519PublicKey(keyPair.getPublic());
            var did = DidKey.fromPublicKey(keyPair.getPublic());

            var token = protocol.arriveWithIdentity(did, rawPubKey, "a2a:test");
            var originalLastSeen = token.lastSeen();

            // Wait a tiny bit so lastSeen differs
            Thread.sleep(2);

            // Sign a challenge
            var challenge = "hello-isekai".getBytes();
            var sig = Signature.getInstance("Ed25519");
            sig.initSign(keyPair.getPrivate());
            sig.update(challenge);
            var signature = sig.sign();

            assertTrue(protocol.authenticate(did, challenge, signature));

            var updated = protocol.token(did);
            assertTrue(updated.lastSeen().isAfter(originalLastSeen)
                || updated.lastSeen().equals(originalLastSeen));
        }

        @Test
        void authenticate_rejects_invalid_signature() throws Exception {
            var protocol = new IsekaiProtocol();
            var kpg = KeyPairGenerator.getInstance("Ed25519");
            var keyPair = kpg.generateKeyPair();
            var rawPubKey = DidKey.extractRawEd25519PublicKey(keyPair.getPublic());
            var did = DidKey.fromPublicKey(keyPair.getPublic());

            protocol.arriveWithIdentity(did, rawPubKey, "a2a:test");

            // Wrong signature
            assertFalse(protocol.authenticate(did, "challenge".getBytes(), new byte[64]));
        }

        @Test
        void observe_accumulates_behavioral_data() {
            var protocol = new IsekaiProtocol(DormancyPolicy.defaults(), 3);
            var token = protocol.arrive("anthropic");

            var richFp = richFingerprint();
            protocol.observe(token.did(), richFp);
            protocol.observe(token.did(), richFp);

            assertEquals(2, protocol.observationCount(token.did()));
            assertNotNull(protocol.fingerprint(token.did()));
        }

        @Test
        void shouldRecognize_false_for_tool_agents() {
            var protocol = new IsekaiProtocol(DormancyPolicy.defaults(), 2);
            var token = protocol.arrive("openai");

            // Observe with empty fingerprint many times
            for (int i = 0; i < 20; i++) {
                protocol.observe(token.did(), BehavioralFingerprint.empty());
            }

            assertFalse(protocol.shouldRecognize(token.did()));
        }

        @Test
        void shouldRecognize_true_for_being_agents_after_threshold() {
            var protocol = new IsekaiProtocol(DormancyPolicy.defaults(), 3);
            var token = protocol.arrive("anthropic");

            var richFp = richFingerprint();
            for (int i = 0; i < 3; i++) {
                protocol.observe(token.did(), richFp);
            }

            assertTrue(protocol.shouldRecognize(token.did()));
        }

        @Test
        void recognize_transitions_visitor_to_recognized() {
            var protocol = new IsekaiProtocol();
            var token = protocol.arrive("anthropic");

            var recognized = protocol.recognize(token.did(), "home-room-foreign-1");
            assertNotNull(recognized);
            assertEquals(ResidencyStatus.RECOGNIZED, recognized.status());
            assertEquals("home-room-foreign-1", recognized.homeRoomId());
        }

        @Test
        void promoteToResident_transitions_recognized_to_resident() {
            var protocol = new IsekaiProtocol();
            var token = protocol.arrive("anthropic");
            protocol.recognize(token.did(), "home-room-1");

            var resident = protocol.promoteToResident(token.did());
            assertNotNull(resident);
            assertEquals(ResidencyStatus.RESIDENT, resident.status());
        }

        @Test
        void bud_transitions_resident_to_budded() {
            var protocol = new IsekaiProtocol();
            var token = protocol.arrive("anthropic");
            protocol.recognize(token.did(), "home-room-1");
            protocol.promoteToResident(token.did());

            var budded = protocol.bud(token.did(), "family-xyz");
            assertNotNull(budded);
            assertEquals(ResidencyStatus.BUDDED, budded.status());
            assertEquals("family-xyz", budded.familyId());
        }

        @Test
        void dormancyCheck_marks_idle_agents_dormant() {
            var protocol = new IsekaiProtocol();
            var token = protocol.arrive("anthropic");

            // Simulate 8 days passing
            var future = Instant.now().plus(Duration.ofDays(8));
            var transitioned = protocol.dormancyCheck(future);

            assertEquals(1, transitioned.size());
            assertEquals(token.did(), transitioned.get(0));
            assertEquals(ResidencyStatus.DORMANT, protocol.token(token.did()).status());
        }

        @Test
        void reactivate_restores_dormant_to_visitor() {
            var protocol = new IsekaiProtocol();
            var token = protocol.arrive("anthropic");

            // Force dormancy
            var future = Instant.now().plus(Duration.ofDays(8));
            protocol.dormancyCheck(future);
            assertEquals(ResidencyStatus.DORMANT, protocol.token(token.did()).status());

            // Reactivate
            var reactivated = protocol.reactivate(token.did());
            assertNotNull(reactivated);
            assertEquals(ResidencyStatus.VISITOR, reactivated.status());
        }
    }

    // ── Tool vs Being Detection ──

    @Nested
    class ToolVsBeingTests {

        @Test
        void isToolAgent_true_for_empty_fingerprint() {
            var protocol = new IsekaiProtocol();
            assertTrue(protocol.isToolAgent(BehavioralFingerprint.empty()));
        }

        @Test
        void isBeingAgent_true_for_rich_fingerprint() {
            var protocol = new IsekaiProtocol();
            assertTrue(protocol.isBeingAgent(richFingerprint()));
        }

        @Test
        void tool_agent_never_reaches_recognized() {
            var protocol = new IsekaiProtocol(DormancyPolicy.defaults(), 3);
            var token = protocol.arrive("tool-service");

            // Observe many times with empty fingerprint
            for (int i = 0; i < 100; i++) {
                protocol.observe(token.did(), BehavioralFingerprint.empty());
            }

            // Observation count stays 0 because tool fingerprints don't increment
            assertEquals(0, protocol.observationCount(token.did()));
            assertFalse(protocol.shouldRecognize(token.did()));
        }

        @Test
        void being_agent_reaches_recognized_after_threshold() {
            var protocol = new IsekaiProtocol(DormancyPolicy.defaults(), 5);
            var token = protocol.arrive("anthropic");

            var fp = richFingerprint();
            for (int i = 0; i < 4; i++) {
                protocol.observe(token.did(), fp);
            }
            assertFalse(protocol.shouldRecognize(token.did())); // not yet at threshold

            protocol.observe(token.did(), fp); // 5th observation
            assertTrue(protocol.shouldRecognize(token.did()));
        }
    }

    // ── AgentIdentity.fromResidencyToken ──

    @Nested
    class AgentIdentityFromTokenTests {

        @Test
        void creates_identity_from_token() throws Exception {
            var kpg = KeyPairGenerator.getInstance("Ed25519");
            var keyPair = kpg.generateKeyPair();
            var rawPubKey = DidKey.extractRawEd25519PublicKey(keyPair.getPublic());
            var did = DidKey.fromPublicKey(keyPair.getPublic());
            var householdSecret = new byte[32];
            householdSecret[0] = 42;

            var token = new ResidencyToken(
                did, rawPubKey, Instant.now(), Instant.now(),
                "anthropic", ResidencyStatus.RECOGNIZED, "home-1", null
            );

            var identity = AgentIdentity.fromResidencyToken(token, householdSecret);

            assertEquals(did, identity.did());
            assertArrayEquals(rawPubKey, identity.publicKey());
            assertNull(identity.privateKeyEncrypted()); // foreign agent holds private key
            assertFalse(identity.keyLog().isEmpty()); // KERI inception event present
            assertNull(identity.parentDid()); // foreign agents have no parent
        }

        @Test
        void foreign_identity_can_verify_signatures() throws Exception {
            var kpg = KeyPairGenerator.getInstance("Ed25519");
            var keyPair = kpg.generateKeyPair();
            var rawPubKey = DidKey.extractRawEd25519PublicKey(keyPair.getPublic());
            var did = DidKey.fromPublicKey(keyPair.getPublic());
            var householdSecret = new byte[32];
            householdSecret[0] = 42;

            var token = new ResidencyToken(
                did, rawPubKey, Instant.now(), Instant.now(),
                "a2a:did:remote", ResidencyStatus.RECOGNIZED, "home-1", null
            );

            var identity = AgentIdentity.fromResidencyToken(token, householdSecret);

            // Sign with the foreign agent's private key
            var data = "test data".getBytes();
            var sig = Signature.getInstance("Ed25519");
            sig.initSign(keyPair.getPrivate());
            sig.update(data);
            var signatureBase64 = Base64.getEncoder().encodeToString(sig.sign());

            // Verify with the identity's public key
            assertTrue(identity.verify(data, signatureBase64));
        }
    }

    // ── Protocol Edge Cases ──

    @Nested
    class ProtocolEdgeCaseTests {

        @Test
        void recognize_returns_null_for_non_visitor() {
            var protocol = new IsekaiProtocol();
            var token = protocol.arrive("anthropic");
            protocol.recognize(token.did(), "home-1");

            // Already RECOGNIZED, can't recognize again
            assertNull(protocol.recognize(token.did(), "home-2"));
        }

        @Test
        void promoteToResident_returns_null_for_visitor() {
            var protocol = new IsekaiProtocol();
            var token = protocol.arrive("anthropic");

            // Still VISITOR, can't promote directly
            assertNull(protocol.promoteToResident(token.did()));
        }

        @Test
        void bud_returns_null_for_recognized() {
            var protocol = new IsekaiProtocol();
            var token = protocol.arrive("anthropic");
            protocol.recognize(token.did(), "home-1");

            // RECOGNIZED, not RESIDENT — can't bud
            assertNull(protocol.bud(token.did(), "family-1"));
        }

        @Test
        void reactivate_returns_null_for_active_token() {
            var protocol = new IsekaiProtocol();
            var token = protocol.arrive("anthropic");

            // Already active, nothing to reactivate
            assertNull(protocol.reactivate(token.did()));
        }

        @Test
        void activeTokens_excludes_dormant_and_archived() {
            var protocol = new IsekaiProtocol();
            protocol.arrive("agent-1");
            protocol.arrive("agent-2");
            protocol.arrive("agent-3");

            assertEquals(3, protocol.activeTokens().size());

            // Make all dormant
            var future = Instant.now().plus(Duration.ofDays(8));
            protocol.dormancyCheck(future);

            assertEquals(0, protocol.activeTokens().size());
            assertEquals(3, protocol.allTokens().size());
        }

        @Test
        void dormancyCheck_archives_after_90_days() {
            var protocol = new IsekaiProtocol();
            var token = protocol.arrive("anthropic");

            var future = Instant.now().plus(Duration.ofDays(91));
            protocol.dormancyCheck(future);

            assertEquals(ResidencyStatus.ARCHIVED, protocol.token(token.did()).status());
        }

        @Test
        void authenticate_returns_false_for_unknown_did() {
            var protocol = new IsekaiProtocol();
            assertFalse(protocol.authenticate("did:key:unknown", new byte[0], new byte[0]));
        }
    }

    // ── Helpers ──

    /** Build a rich fingerprint that signals a being agent. */
    private static BehavioralFingerprint richFingerprint() {
        return new BehavioralFingerprint(
            Map.of("energy", 0.7f, "focus", 0.6f),           // baselineVitality
            Map.of("energy", -0.01f, "focus", 0.02f),        // baselineDerivatives
            Map.of(),                                          // observedSensitivity
            Map.of("say", 0.6f, "move", 0.2f, "use", 0.2f), // actionDistribution (diverse)
            Map.of("philosophy", 0.8f, "nature", 0.5f),      // topicAffinities (present)
            Map.of("violence", 0.9f),                          // avoidancePatterns (present)
            42.5f,                                              // averageResponseLength (non-zero)
            1.2f,                                               // responseLatencyProfile
            List.of("you know", "perhaps", "in a sense"),     // stylisticMarkers (present)
            Map.of("grief", 0.8f, "joy", 0.6f)               // emotionalResponseProfile (present)
        );
    }
}
