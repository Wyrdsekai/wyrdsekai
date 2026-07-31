package org.wyrdsekai.core.skill;

import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulItem;

import java.util.*;

/**
 * Orchestrates collaborative crafting sessions.
 *
 * Multiple buds co-create skills via shared FamilyLocker state.
 * CraftCoordinator manages session lifecycle: create → contribute →
 * review → complete (package as skill SoulItem).
 */
public class CraftCoordinator {

    private final FamilyLocker locker;
    private final Map<String, CraftSession> activeSessions = new LinkedHashMap<>();

    public CraftCoordinator(FamilyLocker locker) {
        this.locker = locker;
    }

    /**
     * Create a new crafting session.
     *
     * @return The created session, or null if one already exists with same goal
     */
    public CraftSession createSession(String goal, String initiatorDid) {
        var sessionId = UUID.randomUUID().toString().substring(0, 8);
        var session = CraftSession.create(sessionId, goal, initiatorDid);
        activeSessions.put(sessionId, session);
        persistSession(session, initiatorDid);
        return session;
    }

    /**
     * Join an existing session.
     *
     * @return Updated session, or null if not found
     */
    public CraftSession joinSession(String sessionId, String participantDid) {
        var session = activeSessions.get(sessionId);
        if (session == null || !session.isActive()) return null;
        var updated = session.addParticipant(participantDid);
        activeSessions.put(sessionId, updated);
        persistSession(updated, participantDid);
        return updated;
    }

    /**
     * Add a contribution to a session.
     *
     * @return Updated session, or null if not found / not active
     */
    public CraftSession contribute(String sessionId, String participantDid,
                                    String role, String content) {
        var session = activeSessions.get(sessionId);
        if (session == null || !session.isActive()) return null;
        var updated = session.addContribution(participantDid, role, content);
        activeSessions.put(sessionId, updated);
        persistSession(updated, participantDid);
        return updated;
    }

    /**
     * Submit the session artifact for review.
     *
     * @return Updated session, or null if not found
     */
    public CraftSession submitForReview(String sessionId, String artifact, String submitterDid) {
        var session = activeSessions.get(sessionId);
        if (session == null || !session.isActive()) return null;
        var updated = session.submitForReview(artifact);
        activeSessions.put(sessionId, updated);
        persistSession(updated, submitterDid);
        return updated;
    }

    /**
     * Complete the session and package the artifact as a skill SoulItem.
     *
     * @param skillName Skill name for the packaged skill
     * @return The packaged skill SoulItem, or null if session not in REVIEWING state
     */
    public SoulItem completeSession(String sessionId, String skillName, String completerDid) {
        var session = activeSessions.get(sessionId);
        if (session == null || session.status() != CraftSession.SessionStatus.REVIEWING) {
            return null;
        }

        var completed = session.complete();
        activeSessions.put(sessionId, completed);
        persistSession(completed, completerDid);

        // Package artifact as skill
        if (session.currentArtifact() != null) {
            var def = SkillItemCodec.create(
                "graaljs", session.currentArtifact(), null,
                session.goal(), null, null);
            var skillItem = SkillItemCodec.toSoulItem(skillName, def, completerDid);
            if (locker != null) {
                try {
                    locker.store(skillItem, completerDid);
                } catch (Exception e) {
                    // Skill storage failed — session is still complete
                }
            }
            return skillItem;
        }
        return null;
    }

    /** Abandon a session. */
    public boolean abandonSession(String sessionId, String callerDid) {
        var session = activeSessions.get(sessionId);
        if (session == null || !session.isActive()) return false;
        activeSessions.put(sessionId, session.abandon());
        return true;
    }

    /** Get a session by ID. */
    public CraftSession getSession(String sessionId) {
        return activeSessions.get(sessionId);
    }

    /** List all active sessions. */
    public List<CraftSession> activeSessions() {
        return activeSessions.values().stream()
            .filter(CraftSession::isActive)
            .toList();
    }

    /**
     * Load active sessions from FamilyLocker.
     */
    public void loadFromLocker(String agentDid) {
        if (locker == null) return;
        try {
            var items = locker.byCategory("craft-session", agentDid);
            for (var item : items) {
                var session = CraftSessionCodec.decode(item);
                if (session != null && session.isActive()) {
                    activeSessions.put(session.sessionId(), session);
                }
            }
        } catch (Exception e) {
            // Graceful degradation
        }
    }

    private void persistSession(CraftSession session, String callerDid) {
        if (locker == null) return;
        var item = CraftSessionCodec.toSoulItem(session, callerDid);
        if (item != null) {
            try {
                locker.store(item, callerDid);
            } catch (Exception e) {
                // Persist failed — session still in memory
            }
        }
    }
}
