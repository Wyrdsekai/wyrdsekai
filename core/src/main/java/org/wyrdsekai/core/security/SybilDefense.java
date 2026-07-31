package org.wyrdsekai.core.security;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sybil defense foundation (§21.4).
 * Provides latency challenge, resource cost validation, and graduated trust
 * to prevent identity flooding attacks.
 */
public class SybilDefense {

    // --- Latency Challenge ---

    /** A latency challenge issued to verify proximity. */
    public record LatencyChallenge(
        String challengeId,
        String entityId,
        byte[] nonce,
        Instant issuedAt,
        Duration maxRtt
    ) {}

    /** Result of a latency challenge verification. */
    public record ChallengeResult(boolean passed, Duration actualRtt, String reason) {}

    // --- Resource Cost ---

    /** A proof-of-work challenge for new connections. */
    public record ProofOfWork(
        String prefix,
        int difficulty,
        Instant issuedAt,
        Duration maxAge
    ) {}

    // --- Graduated Trust ---

    /** Trust level for an entity, earned over time. */
    public enum TrustLevel {
        UNTRUSTED(0),    // brand new, unverified
        VERIFIED(1),     // passed latency + proof-of-work
        ESTABLISHED(2),  // sustained positive interaction
        TRUSTED(3),      // significant contribution history
        CITIZEN(4);      // full community membership

        private final int level;
        TrustLevel(int level) { this.level = level; }
        public int level() { return level; }
    }

    /** Trust record for an entity. */
    public record TrustRecord(
        String entityId,
        TrustLevel level,
        Instant firstSeen,
        Instant lastActivity,
        int interactionCount,
        int challengesPassed,
        int challengesFailed
    ) {
        public TrustRecord withLevel(TrustLevel newLevel) {
            return new TrustRecord(entityId, newLevel, firstSeen, lastActivity,
                interactionCount, challengesPassed, challengesFailed);
        }

        public TrustRecord recordInteraction() {
            return new TrustRecord(entityId, level, firstSeen, Instant.now(),
                interactionCount + 1, challengesPassed, challengesFailed);
        }

        public TrustRecord recordChallengePassed() {
            return new TrustRecord(entityId, level, firstSeen, Instant.now(),
                interactionCount, challengesPassed + 1, challengesFailed);
        }

        public TrustRecord recordChallengeFailed() {
            return new TrustRecord(entityId, level, firstSeen, Instant.now(),
                interactionCount, challengesPassed, challengesFailed + 1);
        }
    }

    private final Map<String, TrustRecord> trustRecords = new ConcurrentHashMap<>();
    private final Map<String, LatencyChallenge> pendingChallenges = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    private static final Duration DEFAULT_MAX_RTT = Duration.ofMillis(500);
    private static final Duration DEFAULT_POW_MAX_AGE = Duration.ofMinutes(5);
    private static final int DEFAULT_POW_DIFFICULTY = 4; // leading zero hex chars
    private static final int ESTABLISHED_THRESHOLD = 20; // interactions to reach ESTABLISHED
    private static final int TRUSTED_THRESHOLD = 100;    // interactions to reach TRUSTED

    /** Issue a latency challenge to verify an entity's claimed proximity. */
    public LatencyChallenge issueChallenge(String entityId) {
        var nonce = new byte[16];
        random.nextBytes(nonce);
        var challengeId = bytesToHex(nonce).substring(0, 12);
        var challenge = new LatencyChallenge(challengeId, entityId, nonce,
            Instant.now(), DEFAULT_MAX_RTT);
        pendingChallenges.put(challengeId, challenge);
        return challenge;
    }

    /** Verify a latency challenge response. */
    public ChallengeResult verifyChallenge(String challengeId, byte[] responseNonce) {
        var challenge = pendingChallenges.remove(challengeId);
        if (challenge == null) {
            return new ChallengeResult(false, Duration.ZERO, "Unknown or expired challenge");
        }

        var rtt = Duration.between(challenge.issuedAt(), Instant.now());

        if (!Arrays.equals(challenge.nonce(), responseNonce)) {
            recordChallengeFailed(challenge.entityId());
            return new ChallengeResult(false, rtt, "Incorrect nonce");
        }

        if (rtt.compareTo(challenge.maxRtt()) > 0) {
            recordChallengeFailed(challenge.entityId());
            return new ChallengeResult(false, rtt,
                "RTT too high: " + rtt.toMillis() + "ms > " + challenge.maxRtt().toMillis() + "ms");
        }

        recordChallengePassed(challenge.entityId());
        return new ChallengeResult(true, rtt, "Challenge passed");
    }

    /** Issue a proof-of-work challenge. */
    public ProofOfWork issueProofOfWork() {
        var prefix = bytesToHex(new byte[]{(byte) random.nextInt(), (byte) random.nextInt(),
            (byte) random.nextInt(), (byte) random.nextInt()});
        return new ProofOfWork(prefix, DEFAULT_POW_DIFFICULTY, Instant.now(), DEFAULT_POW_MAX_AGE);
    }

    /**
     * Verify a proof-of-work solution.
     * The solution hash must start with `difficulty` zero hex characters.
     */
    public boolean verifyProofOfWork(ProofOfWork pow, String solutionHash) {
        if (Duration.between(pow.issuedAt(), Instant.now()).compareTo(pow.maxAge()) > 0) {
            return false; // expired
        }
        if (solutionHash == null || solutionHash.length() < pow.difficulty()) {
            return false;
        }
        // Check leading zeros
        for (int i = 0; i < pow.difficulty(); i++) {
            if (solutionHash.charAt(i) != '0') return false;
        }
        return true;
    }

    /** Get or create a trust record for an entity. */
    public TrustRecord getTrustRecord(String entityId) {
        return trustRecords.computeIfAbsent(entityId,
            id -> new TrustRecord(id, TrustLevel.UNTRUSTED, Instant.now(), Instant.now(),
                0, 0, 0));
    }

    /** Record an interaction and potentially upgrade trust level. */
    public TrustRecord recordInteraction(String entityId) {
        return trustRecords.compute(entityId, (id, existing) -> {
            var record = existing != null ? existing
                : new TrustRecord(id, TrustLevel.UNTRUSTED, Instant.now(), Instant.now(), 0, 0, 0);
            var updated = record.recordInteraction();
            return maybeUpgradeTrust(updated);
        });
    }

    /** Manually set trust level for an entity. */
    public void setTrustLevel(String entityId, TrustLevel level) {
        trustRecords.compute(entityId, (id, existing) -> {
            var record = existing != null ? existing
                : new TrustRecord(id, TrustLevel.UNTRUSTED, Instant.now(), Instant.now(), 0, 0, 0);
            return record.withLevel(level);
        });
    }

    /** Total number of tracked entities. */
    public int trackedEntityCount() {
        return trustRecords.size();
    }

    /** Number of pending challenges. */
    public int pendingChallengeCount() {
        return pendingChallenges.size();
    }

    // --- Internal ---

    private void recordChallengePassed(String entityId) {
        trustRecords.compute(entityId, (id, existing) -> {
            var record = existing != null ? existing
                : new TrustRecord(id, TrustLevel.UNTRUSTED, Instant.now(), Instant.now(), 0, 0, 0);
            var updated = record.recordChallengePassed();
            if (updated.level() == TrustLevel.UNTRUSTED && updated.challengesPassed() > 0) {
                updated = updated.withLevel(TrustLevel.VERIFIED);
            }
            return updated;
        });
    }

    private void recordChallengeFailed(String entityId) {
        trustRecords.compute(entityId, (id, existing) -> {
            var record = existing != null ? existing
                : new TrustRecord(id, TrustLevel.UNTRUSTED, Instant.now(), Instant.now(), 0, 0, 0);
            return record.recordChallengeFailed();
        });
    }

    private TrustRecord maybeUpgradeTrust(TrustRecord record) {
        return switch (record.level()) {
            case VERIFIED -> record.interactionCount() >= ESTABLISHED_THRESHOLD
                ? record.withLevel(TrustLevel.ESTABLISHED) : record;
            case ESTABLISHED -> record.interactionCount() >= TRUSTED_THRESHOLD
                ? record.withLevel(TrustLevel.TRUSTED) : record;
            default -> record; // UNTRUSTED needs challenge, TRUSTED/CITIZEN need manual promotion
        };
    }

    private static String bytesToHex(byte[] bytes) {
        var sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
