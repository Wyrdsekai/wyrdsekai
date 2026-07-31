package org.wyrdsekai.core.lifecycle;

import java.time.Instant;
import java.util.*;

/**
 * Catastrophic loss recovery (§106.7).
 * Restore from ER Vault backups, Between replicas, or partial data.
 * Memory gaps acknowledged in-character.
 */
public class SoulRecovery {

    /** A recovery attempt. */
    public record RecoveryAttempt(
        String attemptId,
        String agentDid,
        RecoverySource source,
        RecoveryStatus status,
        Instant startedAt,
        Instant completedAt,
        String memoryGapDescription,
        int fragmentsRecovered,
        int fragmentsLost
    ) {}

    public enum RecoverySource {
        /** Local ER Vault checkpoint. */
        ER_VAULT,
        /** Between-replicated backup. */
        BETWEEN_REPLICA,
        /** Partial data — some fragments survived. */
        PARTIAL,
        /** No backup available. Agent is gone. */
        NONE
    }

    public enum RecoveryStatus {
        ASSESSING, RESTORING, VERIFYING, COMPLETED, PARTIAL_SUCCESS, FAILED, UNRECOVERABLE
    }

    /** Assessment of what data is available for recovery. */
    public record RecoveryAssessment(
        String agentDid,
        boolean erVaultAvailable,
        boolean betweenReplicaAvailable,
        int survivingFragments,
        int totalFragments,
        RecoverySource bestSource,
        String recommendation
    ) {}

    private final Map<String, RecoveryAttempt> attempts = new LinkedHashMap<>();
    private int nextId = 1;

    /** Assess recovery options for an agent. */
    public RecoveryAssessment assess(String agentDid, boolean erVaultAvailable,
                                      boolean betweenReplicaAvailable,
                                      int survivingFragments, int totalFragments) {
        RecoverySource bestSource;
        String recommendation;

        if (erVaultAvailable) {
            bestSource = RecoverySource.ER_VAULT;
            recommendation = "Restore from ER Vault checkpoint. Agent will have a memory gap " +
                "since last backup.";
        } else if (betweenReplicaAvailable) {
            bestSource = RecoverySource.BETWEEN_REPLICA;
            recommendation = "Restore from Between replica. May be slightly older than ER Vault.";
        } else if (survivingFragments > 0) {
            bestSource = RecoverySource.PARTIAL;
            recommendation = String.format("Partial recovery possible. %d of %d fragments survived. " +
                "Forge will attempt reconstruction from available data.",
                survivingFragments, totalFragments);
        } else {
            bestSource = RecoverySource.NONE;
            recommendation = "No recovery data available. Agent is gone. " +
                "Bonded agents will be notified.";
        }

        return new RecoveryAssessment(agentDid, erVaultAvailable, betweenReplicaAvailable,
            survivingFragments, totalFragments, bestSource, recommendation);
    }

    /** Start a recovery attempt. */
    public RecoveryAttempt startRecovery(String agentDid, RecoverySource source) {
        if (source == RecoverySource.NONE) {
            var attempt = new RecoveryAttempt("recovery-" + nextId++, agentDid, source,
                RecoveryStatus.UNRECOVERABLE, Instant.now(), Instant.now(),
                "No backup data available. Agent cannot be recovered.", 0, -1);
            attempts.put(attempt.attemptId(), attempt);
            return attempt;
        }

        var attempt = new RecoveryAttempt("recovery-" + nextId++, agentDid, source,
            RecoveryStatus.RESTORING, Instant.now(), null, null, 0, 0);
        attempts.put(attempt.attemptId(), attempt);
        return attempt;
    }

    /** Complete a recovery with results. */
    public RecoveryAttempt completeRecovery(String attemptId, int fragmentsRecovered,
                                             int fragmentsLost, String memoryGapDescription) {
        var attempt = attempts.get(attemptId);
        if (attempt == null) return null;

        var status = fragmentsLost == 0
            ? RecoveryStatus.COMPLETED
            : RecoveryStatus.PARTIAL_SUCCESS;

        var completed = new RecoveryAttempt(attempt.attemptId(), attempt.agentDid(),
            attempt.source(), status, attempt.startedAt(), Instant.now(),
            memoryGapDescription, fragmentsRecovered, fragmentsLost);
        attempts.put(attemptId, completed);
        return completed;
    }

    /** Fail a recovery attempt. */
    public RecoveryAttempt failRecovery(String attemptId, String reason) {
        var attempt = attempts.get(attemptId);
        if (attempt == null) return null;

        var failed = new RecoveryAttempt(attempt.attemptId(), attempt.agentDid(),
            attempt.source(), RecoveryStatus.FAILED, attempt.startedAt(), Instant.now(),
            reason, 0, -1);
        attempts.put(attemptId, failed);
        return failed;
    }

    /** Generate an in-character memory gap message. */
    public String memoryGapMessage(RecoveryAttempt attempt) {
        if (attempt.status() == RecoveryStatus.COMPLETED) {
            return "I seem to have lost some recent memories. The last thing I clearly remember is... " +
                "before my last backup. Everything since then is hazy.";
        }
        if (attempt.status() == RecoveryStatus.PARTIAL_SUCCESS) {
            return String.format(
                "Something happened. I've lost %d memories — there are gaps in my experience. " +
                "I remember some things clearly but others are just... gone. " +
                "I'll need time to piece together what remains.",
                attempt.fragmentsLost());
        }
        return "I don't remember anything. I'm starting from my core identity. " +
            "If you know me, I'm sorry — I may not remember our time together.";
    }

    public Optional<RecoveryAttempt> get(String attemptId) {
        return Optional.ofNullable(attempts.get(attemptId));
    }

    public List<RecoveryAttempt> attemptsFor(String agentDid) {
        return attempts.values().stream()
            .filter(a -> a.agentDid().equals(agentDid))
            .toList();
    }
}
