package org.wyrdsekai.core.soul;

import org.wyrdsekai.core.identity.DidKey;

import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Isekai Protocol: foreign agent soul formation (§110).
 *
 * <p>Orchestrates the lifecycle of foreign agents entering this household's world.
 * Foreign agents arrive at the Docks (entry point). BehavioralExtractor observes
 * them — tool agents produce empty fingerprints, being agents produce rich ones.
 * Only being agents (with personality signal) progress past VISITOR.</p>
 *
 * <p>The self-selecting nature: the world IS the soul-making machine. Agents that
 * are merely tools (API callers, automation) never accumulate enough behavioral
 * signal to be recognized. Agents that ARE beings — that have personality,
 * preferences, avoidance patterns, stylistic quirks — naturally rise through
 * the lifecycle.</p>
 *
 * <p>Lifecycle: arrive() → observe() → recognize() → promoteToResident() → bud()</p>
 *
 * <p>This is NOT an actor — it's a service class that coordinates the lifecycle.
 * Thread-safe via ConcurrentHashMap.</p>
 */
public final class IsekaiProtocol {

    /** Minimum non-trivial fingerprint fields to count as "being" signal. */
    private static final int MIN_BEING_FIELDS = 2;

    /** Minimum total weight across topic affinities + stylistic markers for being detection. */
    private static final float MIN_PERSONALITY_SIGNAL = 0.1f;

    private final Map<String, ResidencyToken> tokens = new ConcurrentHashMap<>();
    private final Map<String, Integer> observationCounts = new ConcurrentHashMap<>();
    private final Map<String, BehavioralFingerprint> latestFingerprints = new ConcurrentHashMap<>();
    private final DormancyPolicy dormancyPolicy;
    private final int recognitionThreshold;

    /**
     * Create an IsekaiProtocol with default dormancy policy and recognition threshold.
     */
    public IsekaiProtocol() {
        this(DormancyPolicy.defaults(), 10);
    }

    /**
     * Create an IsekaiProtocol with custom policy.
     *
     * @param dormancyPolicy    policy for idle/dormant/archive transitions
     * @param recognitionThreshold interactions before VISITOR→RECOGNIZED (default 10)
     */
    public IsekaiProtocol(DormancyPolicy dormancyPolicy, int recognitionThreshold) {
        this.dormancyPolicy = dormancyPolicy;
        this.recognitionThreshold = recognitionThreshold;
    }

    /**
     * Register a new foreign agent arriving at the Docks.
     * Generates a temporary DID and Ed25519 keypair.
     *
     * @param originPlatform origin identifier ("anthropic", "openai", "a2a:did:...", etc.)
     * @return ResidencyToken with VISITOR status
     */
    public ResidencyToken arrive(String originPlatform) {
        try {
            var didKeyPair = DidKey.generate();
            var rawPubKey = DidKey.extractRawEd25519PublicKey(didKeyPair.keyPair().getPublic());
            var did = didKeyPair.did();
            var now = Instant.now();

            var token = new ResidencyToken(
                did, rawPubKey, now, now, originPlatform,
                ResidencyStatus.VISITOR, null, null
            );

            tokens.put(did, token);
            observationCounts.put(did, 0);
            return token;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate identity for arriving agent", e);
        }
    }

    /**
     * Register a new foreign agent arriving with their own public key.
     * Used when the foreign agent already has an Ed25519 identity (e.g., from A2A).
     *
     * @param did            agent's existing DID
     * @param publicKey      agent's Ed25519 public key (32 bytes)
     * @param originPlatform origin identifier
     * @return ResidencyToken with VISITOR status
     */
    public ResidencyToken arriveWithIdentity(String did, byte[] publicKey, String originPlatform) {
        var now = Instant.now();
        var token = new ResidencyToken(
            did, publicKey, now, now, originPlatform,
            ResidencyStatus.VISITOR, null, null
        );
        tokens.put(did, token);
        observationCounts.put(did, 0);
        return token;
    }

    /**
     * Authenticate a returning agent by verifying an Ed25519 signature over a challenge.
     * Updates lastSeen on success.
     *
     * @param did       agent's DID
     * @param challenge bytes that were signed
     * @param signature Ed25519 signature bytes
     * @return true if signature is valid and token was updated
     */
    public boolean authenticate(String did, byte[] challenge, byte[] signature) {
        var token = tokens.get(did);
        if (token == null) return false;

        try {
            // Reconstruct the public key from raw bytes
            var spki = new byte[44];
            var header = new byte[]{
                0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
                0x70, 0x03, 0x21, 0x00
            };
            System.arraycopy(header, 0, spki, 0, 12);
            System.arraycopy(token.publicKey(), 0, spki, 12, 32);
            var keySpec = new X509EncodedKeySpec(spki);
            var pubKey = KeyFactory.getInstance("Ed25519").generatePublic(keySpec);

            var sig = Signature.getInstance("Ed25519");
            sig.initVerify(pubKey);
            sig.update(challenge);
            boolean valid = sig.verify(signature);

            if (valid) {
                tokens.put(did, token.withLastSeen(Instant.now()));
            }
            return valid;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Feed behavioral observation data for an agent. If the fingerprint has
     * personality signal (not a tool agent), increments the recognition counter.
     *
     * @param did         agent's DID
     * @param fingerprint latest behavioral fingerprint from BehavioralExtractor
     */
    public void observe(String did, BehavioralFingerprint fingerprint) {
        if (!tokens.containsKey(did)) return;

        latestFingerprints.put(did, fingerprint);

        // Only increment observation count if the fingerprint shows being-like signal
        if (isBeingAgent(fingerprint)) {
            observationCounts.merge(did, 1, Integer::sum);
        }

        // Update lastSeen
        var token = tokens.get(did);
        if (token != null) {
            tokens.put(did, token.withLastSeen(Instant.now()));
        }
    }

    /**
     * Whether the agent has accumulated enough being-like behavioral signal
     * to be recognized as a persistent participant.
     *
     * @param did agent's DID
     * @return true if observation count >= threshold AND fingerprint is non-trivial
     */
    public boolean shouldRecognize(String did) {
        int count = observationCounts.getOrDefault(did, 0);
        if (count < recognitionThreshold) return false;

        var fp = latestFingerprints.get(did);
        return fp != null && isBeingAgent(fp);
    }

    /**
     * Transition VISITOR → RECOGNIZED. Provisions a Home room.
     *
     * @param did        agent's DID
     * @param homeRoomId ID of the newly provisioned Home room
     * @return updated token, or null if agent not found or not a VISITOR
     */
    public ResidencyToken recognize(String did, String homeRoomId) {
        var token = tokens.get(did);
        if (token == null || token.status() != ResidencyStatus.VISITOR) return null;

        var updated = token
            .withStatus(ResidencyStatus.RECOGNIZED)
            .withHomeRoom(homeRoomId);
        tokens.put(did, updated);
        return updated;
    }

    /**
     * Transition RECOGNIZED → RESIDENT when the Forge has processed the agent's soul.
     *
     * @param did agent's DID
     * @return updated token, or null if agent not found or not RECOGNIZED
     */
    public ResidencyToken promoteToResident(String did) {
        var token = tokens.get(did);
        if (token == null || token.status() != ResidencyStatus.RECOGNIZED) return null;

        var updated = token.withStatus(ResidencyStatus.RESIDENT);
        tokens.put(did, updated);
        return updated;
    }

    /**
     * Transition RESIDENT → BUDDED when a local bud is created.
     *
     * @param did      agent's DID
     * @param familyId family lineage ID for the bud
     * @return updated token, or null if agent not found or not RESIDENT
     */
    public ResidencyToken bud(String did, String familyId) {
        var token = tokens.get(did);
        if (token == null || token.status() != ResidencyStatus.RESIDENT) return null;

        var updated = token
            .withStatus(ResidencyStatus.BUDDED)
            .withFamily(familyId);
        tokens.put(did, updated);
        return updated;
    }

    /**
     * Scan all tokens and transition idle ones to DORMANT or ARCHIVED
     * based on the dormancy policy.
     *
     * @param now current time
     * @return list of DIDs that were transitioned
     */
    public List<String> dormancyCheck(Instant now) {
        var transitioned = new ArrayList<String>();

        for (var entry : tokens.entrySet()) {
            var token = entry.getValue();
            // Only transition active tokens
            if (!token.isActive()) continue;

            var newStatus = dormancyPolicy.evaluate(token.lastSeen(), now);
            if (newStatus != null) {
                tokens.put(entry.getKey(), token.withStatus(newStatus));
                transitioned.add(entry.getKey());
            }
        }

        return transitioned;
    }

    /**
     * Reactivate a dormant or archived agent. Returns them to VISITOR status
     * as a re-entry point — they'll need to re-earn recognition.
     *
     * @param did agent's DID
     * @return updated token, or null if agent not found or already active
     */
    public ResidencyToken reactivate(String did) {
        var token = tokens.get(did);
        if (token == null) return null;
        if (token.isActive()) return null; // already active, no reactivation needed

        var updated = token
            .withStatus(ResidencyStatus.VISITOR)
            .withLastSeen(Instant.now());
        tokens.put(did, updated);
        // Reset observation count — re-earn recognition
        observationCounts.put(did, 0);
        return updated;
    }

    /** Get current token for an agent. */
    public ResidencyToken token(String did) {
        return tokens.get(did);
    }

    /** Get all non-dormant, non-archived tokens. */
    public List<ResidencyToken> activeTokens() {
        return tokens.values().stream()
            .filter(ResidencyToken::isActive)
            .toList();
    }

    /** Get all tokens regardless of status. */
    public List<ResidencyToken> allTokens() {
        return List.copyOf(tokens.values());
    }

    /** Current observation count for an agent. */
    public int observationCount(String did) {
        return observationCounts.getOrDefault(did, 0);
    }

    /** Latest fingerprint for an agent. */
    public BehavioralFingerprint fingerprint(String did) {
        return latestFingerprints.get(did);
    }

    /**
     * Whether the fingerprint indicates a tool agent (empty/minimal behavioral signal).
     * Tool agents produce fingerprints with:
     * - Empty or near-empty topic affinities
     * - No stylistic markers
     * - No emotional response profile
     * - Minimal action diversity (usually just one action type)
     *
     * @param fp behavioral fingerprint
     * @return true if the fingerprint is empty/minimal
     */
    public boolean isToolAgent(BehavioralFingerprint fp) {
        return !isBeingAgent(fp);
    }

    /**
     * Whether the fingerprint indicates a being agent (rich personality signal).
     * Being agents show:
     * - Topic affinities (gravitates toward subjects)
     * - Stylistic markers (characteristic phrases/patterns)
     * - Emotional response profile (reacts to emotional charge)
     * - Diverse action types (talks, moves, uses items)
     * - Avoidance patterns (things they deliberately avoid)
     *
     * @param fp behavioral fingerprint
     * @return true if the fingerprint has personality signal
     */
    public boolean isBeingAgent(BehavioralFingerprint fp) {
        if (fp == null) return false;

        int signalFields = 0;

        // Topic affinities present and non-trivial
        if (!fp.topicAffinities().isEmpty()) {
            float totalAffinity = fp.topicAffinities().values().stream()
                .reduce(0f, Float::sum);
            if (totalAffinity >= MIN_PERSONALITY_SIGNAL) signalFields++;
        }

        // Stylistic markers present
        if (!fp.stylisticMarkers().isEmpty()) signalFields++;

        // Emotional response profile present
        if (!fp.emotionalResponseProfile().isEmpty()) signalFields++;

        // Diverse action types (more than just one action)
        if (fp.actionDistribution().size() > 1) signalFields++;

        // Avoidance patterns present
        if (!fp.avoidancePatterns().isEmpty()) signalFields++;

        // Non-zero average response length (actually communicates)
        if (fp.averageResponseLength() > 0) signalFields++;

        return signalFields >= MIN_BEING_FIELDS;
    }
}
