package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.identity.AgentIdentity;
import org.wyrdsekai.core.identity.DidKey;
import org.wyrdsekai.core.identity.KeriEvent;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Phase 10: SoulVerifier + BehavioralVerifier.
 */
class Phase10Test {

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
        var memory = CompactedMemory.empty();

        return SoulManifest.forge(
            did, "z6MkLain", List.of(), null, 1,
            profile, "I am Lain.",
            List.of(fragment), 3, "",
            genome, List.of(),
            memory, List.of(),
            List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty()
        );
    }

    private static SoulManifest signedManifest(AgentIdentity identity) {
        var manifest = testManifest(identity.did());
        try {
            String sigBase64 = identity.sign(manifest.canonicalBytes(), HOUSEHOLD_SECRET);
            byte[] sigBytes = Base64.getDecoder().decode(sigBase64);
            return manifest.signed(sigBytes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- In-memory SoulStore for tests ---
    private static class InMemorySoulStore implements SoulStore {
        private final Map<String, List<SoulManifest>> store = new HashMap<>();

        @Override public void store(SoulManifest manifest) {
            store.computeIfAbsent(manifest.did(), _ -> new ArrayList<>()).add(manifest);
        }
        @Override public Optional<SoulManifest> load(String did, int version) {
            return store.getOrDefault(did, List.of()).stream()
                .filter(m -> m.manifestVersion() == version).findFirst();
        }
        @Override public Optional<SoulManifest> latest(String did) {
            var list = store.get(did);
            return list != null && !list.isEmpty() ? Optional.of(list.getLast()) : Optional.empty();
        }
        @Override public List<SoulManifest> history(String did) {
            return store.getOrDefault(did, List.of());
        }
        @Override public void archive(String did, String reason) {
            store.remove(did);
        }
        @Override public boolean exists(String did) { return store.containsKey(did); }
        @Override public int count() { return store.size(); }
    }

    @Nested
    class SignatureVerificationTests {

        @Test
        void signed_manifest_verifies() {
            var identity = generateIdentity();
            var manifest = signedManifest(identity);
            assertTrue(SoulVerifier.verifySignature(manifest, identity));
        }

        @Test
        void unsigned_manifest_fails() {
            var identity = generateIdentity();
            var manifest = testManifest(identity.did());
            assertFalse(SoulVerifier.verifySignature(manifest, identity));
        }

        @Test
        void tampered_manifest_fails() {
            var identity = generateIdentity();
            var manifest = signedManifest(identity);

            // Tamper: change version (signature was over version 1)
            var tampered = SoulManifest.forge(
                manifest.did(), manifest.publicKeyMultibase(),
                manifest.keyLog(), manifest.parentDid(),
                999, // tampered version
                manifest.profile(), manifest.residentIdentity(),
                manifest.soulFragments(), manifest.retrievalK(),
                manifest.soulSpecCompat(), manifest.genome(),
                manifest.mirrorCalibration(), manifest.memory(),
                manifest.relationships(), manifest.learnedPatterns(),
                manifest.worldKnowledge(), manifest.vitalitySnapshot(),
                manifest.fingerprint()
            ).signed(manifest.signature());

            assertFalse(SoulVerifier.verifySignature(tampered, identity));
        }

        @Test
        void wrong_identity_fails() {
            var identity1 = generateIdentity();
            var identity2 = generateIdentity();
            var manifest = signedManifest(identity1);
            // Verify with wrong identity
            assertFalse(SoulVerifier.verifySignature(manifest, identity2));
        }

        @Test
        void quick_verify_works() {
            var identity = generateIdentity();
            var manifest = signedManifest(identity);
            assertTrue(SoulVerifier.quickVerify(manifest, identity));
        }
    }

    @Nested
    class KeriLogTests {

        @Test
        void valid_log_passes() {
            var identity = generateIdentity();
            var result = SoulVerifier.verifyKeriLog(identity.keyLog());
            assertTrue(result.isEmpty(), "Expected valid log, got: " + result.orElse(""));
        }

        @Test
        void empty_log_fails() {
            var result = SoulVerifier.verifyKeriLog(List.of());
            assertTrue(result.isPresent());
            assertTrue(result.get().contains("Empty key log"));
        }

        @Test
        void null_log_fails() {
            var result = SoulVerifier.verifyKeriLog(null);
            assertTrue(result.isPresent());
        }
    }

    @Nested
    class ParentChainTests {

        @Test
        void original_soul_passes() {
            var identity = generateIdentity();
            var manifest = testManifest(identity.did());
            var store = new InMemorySoulStore();
            var result = SoulVerifier.verifyParentChain(manifest, store);
            assertTrue(result.isEmpty()); // No parent = valid
        }

        @Test
        void child_with_existing_parent_passes() {
            var parentIdentity = generateIdentity();
            var parentManifest = testManifest(parentIdentity.did());
            var store = new InMemorySoulStore();
            store.store(parentManifest);

            // Create child manifest with parent DID
            var childDid = "did:key:child1";
            var profile = new AgentProfile("Lain-Bud", "home-server-bud-1", "agent",
                "A bud", "You are Lain's bud.", 4096, 512, 0.7, childDid);
            var childManifest = SoulManifest.forge(
                childDid, "z6MkChild", List.of(), parentIdentity.did(), 1,
                profile, "I am Lain's bud.",
                List.of(), 1, "",
                GenomeProfile.defaults(), List.of(),
                CompactedMemory.empty(), List.of(),
                List.of(), Map.of(),
                VitalitySnapshot.defaults(), BehavioralFingerprint.empty()
            );

            var result = SoulVerifier.verifyParentChain(childManifest, store);
            assertTrue(result.isEmpty());
        }

        @Test
        void child_with_missing_parent_fails() {
            var childDid = "did:key:child1";
            var profile = new AgentProfile("Lain-Bud", "home-server-bud-1", "agent",
                "A bud", "You are Lain's bud.", 4096, 512, 0.7, childDid);
            var childManifest = SoulManifest.forge(
                childDid, "z6MkChild", List.of(), "did:key:nonexistent", 1,
                profile, "I am Lain's bud.",
                List.of(), 1, "",
                GenomeProfile.defaults(), List.of(),
                CompactedMemory.empty(), List.of(),
                List.of(), Map.of(),
                VitalitySnapshot.defaults(), BehavioralFingerprint.empty()
            );

            var store = new InMemorySoulStore();
            var result = SoulVerifier.verifyParentChain(childManifest, store);
            assertTrue(result.isPresent());
            assertTrue(result.get().contains("not found"));
        }
    }

    @Nested
    class FullVerificationTests {

        @Test
        void signed_with_valid_keri_reaches_signature_keri_trust() {
            var identity = generateIdentity();
            var manifest = signedManifest(identity);
            var store = new InMemorySoulStore();

            var result = SoulVerifier.verify(manifest, identity, store, false, null);
            assertTrue(result.isValid());
            assertEquals(SoulVerifier.TrustLevel.SIGNATURE_KERI, result.trustLevel());
            assertTrue(result.passed().contains("signature"));
            assertTrue(result.passed().contains("keri"));
        }

        @Test
        void with_origin_confirmation_reaches_higher_trust() {
            var identity = generateIdentity();
            var manifest = signedManifest(identity);
            var store = new InMemorySoulStore();

            var result = SoulVerifier.verify(manifest, identity, store, true, null);
            assertEquals(SoulVerifier.TrustLevel.SIGNATURE_KERI_ORIGIN, result.trustLevel());
        }

        @Test
        void with_behavioral_match_reaches_full_trust() {
            var identity = generateIdentity();
            var manifest = signedManifest(identity);
            var store = new InMemorySoulStore();

            var result = SoulVerifier.verify(manifest, identity, store, true, true);
            assertEquals(SoulVerifier.TrustLevel.FULL, result.trustLevel());
        }

        @Test
        void unsigned_manifest_skips_signature() {
            var identity = generateIdentity();
            var manifest = testManifest(identity.did()); // unsigned
            var store = new InMemorySoulStore();

            var result = SoulVerifier.verify(manifest, identity, store, false, null);
            // Unsigned skips, doesn't fail
            assertTrue(result.skipped().stream().anyMatch(s -> s.contains("signature")));
        }
    }

    @Nested
    class BehavioralVerifierTests {

        @Test
        void empty_observations_returns_default() {
            var fp = BehavioralFingerprint.empty();
            var result = BehavioralVerifier.verify(fp,
                Map.of(), List.of(), Map.of());
            assertTrue(result.passed());
            assertEquals(0, result.observationCount());
        }

        @Test
        void matching_actions_have_low_divergence() {
            var fp = new BehavioralFingerprint(
                Map.of(), Map.of(), Map.of(),
                Map.of("say", 0.7f, "move", 0.2f, "emote", 0.1f),
                Map.of(), Map.of(), 50.0f, 1.0f, List.of(), Map.of()
            );

            var observed = Map.of("say", 70, "move", 20, "emote", 10);
            float div = BehavioralVerifier.actionDivergence(
                fp.actionDistribution(), observed);
            assertTrue(div < 0.05f, "Matching distributions should have low divergence: " + div);
        }

        @Test
        void mismatched_actions_have_high_divergence() {
            var fp = new BehavioralFingerprint(
                Map.of(), Map.of(), Map.of(),
                Map.of("say", 0.9f, "move", 0.1f),
                Map.of(), Map.of(), 50.0f, 1.0f, List.of(), Map.of()
            );

            // Observed: mostly moves, few says → high divergence
            var observed = Map.of("say", 10, "move", 90);
            float div = BehavioralVerifier.actionDivergence(
                fp.actionDistribution(), observed);
            assertTrue(div > 0.3f, "Mismatched distributions should have high divergence: " + div);
        }

        @Test
        void style_divergence_detects_length_mismatch() {
            var fp = new BehavioralFingerprint(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                100.0f, // expects ~100 word responses
                1.0f, List.of(), Map.of()
            );

            // Observed: very short responses (~5 words each)
            var texts = List.of("Yes.", "No.", "Maybe.", "I don't know.", "Sure.");
            float div = BehavioralVerifier.styleDivergence(fp, texts);
            assertTrue(div > 0.3f, "Length mismatch should cause divergence: " + div);
        }

        @Test
        void stylistic_markers_matched() {
            var fp = new BehavioralFingerprint(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                7.0f, 1.0f, // ~7 words expected (matches our test texts)
                List.of("indeed", "fascinating", "quite so"),
                Map.of()
            );

            var texts = List.of(
                "That is indeed a fascinating question.",
                "Quite so, I agree wholeheartedly.");
            float div = BehavioralVerifier.styleDivergence(fp, texts);
            assertTrue(div < 0.3f, "Matching markers should show low style divergence: " + div);
        }

        @Test
        void full_verification_passes_with_consistent_behavior() {
            var fp = new BehavioralFingerprint(
                Map.of(), Map.of(), Map.of(),
                Map.of("say", 0.8f, "emote", 0.2f),
                Map.of("philosophy", 0.6f),
                Map.of(), 30.0f, 1.0f,
                List.of("perhaps", "consider"),
                Map.of()
            );

            var actions = Map.of("say", 80, "emote", 20);
            var texts = List.of(
                "Perhaps we should consider the deeper meaning of this.",
                "I find this quite interesting, perhaps there is more to explore.",
                "Consider the implications carefully before deciding.");
            var topics = Map.of("philosophy", 8, "mundane", 2);

            var result = BehavioralVerifier.verify(fp, actions, texts, topics);
            assertTrue(result.passed(), "Consistent behavior should pass: " + result.summary());
            assertEquals(3, result.observationCount());
        }

        @Test
        void full_verification_fails_with_divergent_behavior() {
            var fp = new BehavioralFingerprint(
                Map.of(), Map.of(), Map.of(),
                Map.of("say", 0.9f),
                Map.of("philosophy", 0.8f),
                Map.of(), 100.0f, 1.0f,
                List.of("indeed", "fascinating"),
                Map.of()
            );

            // Completely different behavior: short, different topics, different style
            var actions = Map.of("move", 90, "say", 10);
            var texts = List.of("ok", "sure", "whatever");
            var topics = Map.of("sports", 9, "weather", 1);

            var result = BehavioralVerifier.verify(fp, actions, texts, topics);
            assertFalse(result.passed(), "Divergent behavior should fail: " + result.summary());
        }

        @Test
        void custom_threshold_works() {
            var fp = BehavioralFingerprint.empty();
            var texts = List.of("some text here");

            // Very strict threshold
            var strict = BehavioralVerifier.verify(fp, Map.of(), texts, Map.of(), 0.01f);
            // Very lenient threshold
            var lenient = BehavioralVerifier.verify(fp, Map.of(), texts, Map.of(), 0.99f);

            // Lenient should pass more easily
            assertTrue(lenient.passed());
        }

        @Test
        void default_threshold_is_50_percent() {
            assertEquals(0.50f, BehavioralVerifier.DEFAULT_THRESHOLD);
        }
    }

    // --- verifyInbound tests (Phase 10 transit integration) ---

    /** Create a manifest with a real Ed25519 keypair and valid multibase key. */
    private static SoulManifest manifestWithRealKey() {
        var identity = generateIdentity();
        var multibaseKey = identity.did().substring("did:key:".length());
        var profile = new AgentProfile("Lain", "home-server-1", "agent",
            "A quiet thinker", "You are Lain.", 4096, 512, 0.7, identity.did());
        return SoulManifest.forge(
            identity.did(), multibaseKey, identity.keyLog(), null, 1,
            profile, "I am Lain.",
            List.of(), 3, "",
            GenomeProfile.defaults(), List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty()
        );
    }

    /** Create a signed manifest with real keypair suitable for verifyInbound. */
    private static SoulManifest signedManifestWithRealKey() {
        var identity = generateIdentity();
        var multibaseKey = identity.did().substring("did:key:".length());
        var profile = new AgentProfile("Lain", "home-server-1", "agent",
            "A quiet thinker", "You are Lain.", 4096, 512, 0.7, identity.did());
        var manifest = SoulManifest.forge(
            identity.did(), multibaseKey, identity.keyLog(), null, 1,
            profile, "I am Lain.",
            List.of(), 3, "",
            GenomeProfile.defaults(), List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty()
        );
        try {
            String sigBase64 = identity.sign(manifest.canonicalBytes(), HOUSEHOLD_SECRET);
            byte[] sigBytes = Base64.getDecoder().decode(sigBase64);
            return manifest.signed(sigBytes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    class VerifyInboundTests {

        @Test
        void unsigned_manifest_with_valid_keri_reaches_signature_keri() {
            var manifest = manifestWithRealKey();
            var store = new InMemorySoulStore();

            var result = SoulVerifier.verifyInbound(manifest, store);
            assertTrue(result.isValid());
            assertEquals(SoulVerifier.TrustLevel.SIGNATURE_KERI, result.trustLevel());
            assertTrue(result.skipped().stream().anyMatch(s -> s.contains("signature")));
            assertTrue(result.passed().contains("keri"));
        }

        @Test
        void signed_manifest_with_real_key_verifies() {
            var manifest = signedManifestWithRealKey();
            var store = new InMemorySoulStore();

            var result = SoulVerifier.verifyInbound(manifest, store);
            assertTrue(result.isValid());
            assertEquals(SoulVerifier.TrustLevel.SIGNATURE_KERI, result.trustLevel());
            assertTrue(result.passed().contains("signature"));
            assertTrue(result.passed().contains("keri"));
        }

        @Test
        void verify_inbound_with_origin_confirmation() {
            var manifest = manifestWithRealKey();
            var store = new InMemorySoulStore();

            var result = SoulVerifier.verifyInbound(manifest, store, true);
            assertTrue(result.isValid());
            assertEquals(SoulVerifier.TrustLevel.SIGNATURE_KERI_ORIGIN, result.trustLevel());
        }

        @Test
        void verify_inbound_with_null_store() {
            var manifest = manifestWithRealKey();

            // null store should not crash — parent chain just uses null store
            var result = SoulVerifier.verifyInbound(manifest, null);
            assertTrue(result.isValid());
        }

        @Test
        void invalid_multibase_key_returns_none_trust() {
            // Manifest with invalid multibase key
            var profile = new AgentProfile("Lain", "home-server-1", "agent",
                "A quiet thinker", "You are Lain.", 4096, 512, 0.7, "did:key:bad");
            var manifest = SoulManifest.forge(
                "did:key:bad", "INVALID", List.of(), null, 1,
                profile, "I am Lain.",
                List.of(), 3, "",
                GenomeProfile.defaults(), List.of(),
                CompactedMemory.empty(), List.of(), List.of(), Map.of(),
                VitalitySnapshot.defaults(), BehavioralFingerprint.empty()
            );

            var result = SoulVerifier.verifyInbound(manifest, null);
            assertFalse(result.isValid());
            assertEquals(SoulVerifier.TrustLevel.NONE, result.trustLevel());
            assertTrue(result.failed().stream().anyMatch(s -> s.contains("identity-reconstruction")));
        }

        @Test
        void behavioral_is_always_skipped_in_inbound() {
            var manifest = manifestWithRealKey();
            var result = SoulVerifier.verifyInbound(manifest, null);
            assertTrue(result.skipped().stream().anyMatch(s -> s.contains("behavioral")));
        }
    }
}
