package org.wyrdsekai.core.skill;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * State for a collaborative skill crafting session.
 *
 * Multiple buds co-create skills via shared FamilyLocker state.
 * Stored as a SoulItem (category "craft-session") in FamilyLocker.
 */
public record CraftSession(
    String sessionId,
    String goal,
    List<String> participants,
    List<Contribution> contributions,
    String currentArtifact,
    SessionStatus status,
    Instant createdAt,
    Instant updatedAt
) {

    public enum SessionStatus {
        OPEN,         // Waiting for participants
        IN_PROGRESS,  // Actively crafting
        REVIEWING,    // Artifact ready for review
        COMPLETE,     // Skill packaged
        ABANDONED     // Session abandoned
    }

    /** A contribution from a participant. */
    public record Contribution(
        String participantDid,
        String role,        // "design", "implement", "test", "review"
        String content,
        Instant timestamp
    ) {}

    /** Add a participant. */
    public CraftSession addParticipant(String did) {
        if (participants.contains(did)) return this;
        var newParticipants = new ArrayList<>(participants);
        newParticipants.add(did);
        return new CraftSession(sessionId, goal, newParticipants, contributions,
            currentArtifact, status, createdAt, Instant.now());
    }

    /** Add a contribution. */
    public CraftSession addContribution(String participantDid, String role, String content) {
        var newContributions = new ArrayList<>(contributions);
        newContributions.add(new Contribution(participantDid, role, content, Instant.now()));
        var newStatus = status == SessionStatus.OPEN ? SessionStatus.IN_PROGRESS : status;
        return new CraftSession(sessionId, goal, participants, newContributions,
            currentArtifact, newStatus, createdAt, Instant.now());
    }

    /** Update the current artifact. */
    public CraftSession withArtifact(String artifact) {
        return new CraftSession(sessionId, goal, participants, contributions,
            artifact, status, createdAt, Instant.now());
    }

    /** Transition to reviewing status. */
    public CraftSession submitForReview(String artifact) {
        return new CraftSession(sessionId, goal, participants, contributions,
            artifact, SessionStatus.REVIEWING, createdAt, Instant.now());
    }

    /** Mark as complete. */
    public CraftSession complete() {
        return new CraftSession(sessionId, goal, participants, contributions,
            currentArtifact, SessionStatus.COMPLETE, createdAt, Instant.now());
    }

    /** Abandon the session. */
    public CraftSession abandon() {
        return new CraftSession(sessionId, goal, participants, contributions,
            currentArtifact, SessionStatus.ABANDONED, createdAt, Instant.now());
    }

    /** Create a new session. */
    public static CraftSession create(String sessionId, String goal, String initiatorDid) {
        return new CraftSession(sessionId, goal, List.of(initiatorDid), List.of(),
            null, SessionStatus.OPEN, Instant.now(), Instant.now());
    }

    /** Whether the session is still active. */
    public boolean isActive() {
        return status == SessionStatus.OPEN || status == SessionStatus.IN_PROGRESS
            || status == SessionStatus.REVIEWING;
    }

    /** Number of contributions. */
    public int contributionCount() {
        return contributions.size();
    }
}
