package org.wyrdsekai.core.observability;

import java.time.Instant;
import java.util.*;

/**
 * Recovery playbooks for the ER room (§105).
 * Structured procedures for fragment quarantine, realignment,
 * Forge emergency, and identity restoration.
 */
public class RecoveryPlaybook {

    /** A recovery procedure. */
    public record Procedure(
        String id,
        String name,
        ProcedureType type,
        List<Step> steps,
        String description
    ) {}

    /** A step within a procedure. */
    public record Step(
        int order,
        String action,
        String description,
        boolean requiresConsent,
        boolean automated
    ) {}

    public enum ProcedureType {
        /** Quarantine corrupted/suspicious memory fragments. */
        FRAGMENT_QUARANTINE,
        /** Realign identity drift. */
        IDENTITY_REALIGNMENT,
        /** Emergency Forge consolidation. */
        EMERGENCY_FORGE,
        /** Restore from backup. */
        BACKUP_RESTORE,
        /** Full identity reconstruction. */
        IDENTITY_RECONSTRUCTION
    }

    /** An active recovery session. */
    public record RecoverySession(
        String sessionId,
        String agentDid,
        ProcedureType type,
        Instant startedAt,
        int currentStep,
        int totalSteps,
        SessionStatus status
    ) {}

    public enum SessionStatus {
        IN_PROGRESS, AWAITING_CONSENT, COMPLETED, FAILED, ABORTED
    }

    private final Map<ProcedureType, Procedure> procedures = new LinkedHashMap<>();
    private final Map<String, RecoverySession> sessions = new LinkedHashMap<>();
    private int nextId = 1;

    public RecoveryPlaybook() {
        initializePlaybooks();
    }

    /** Start a recovery procedure. */
    public RecoverySession startRecovery(String agentDid, ProcedureType type) {
        var procedure = procedures.get(type);
        if (procedure == null) return null;

        var session = new RecoverySession("recovery-" + nextId++, agentDid, type,
            Instant.now(), 0, procedure.steps().size(), SessionStatus.IN_PROGRESS);
        sessions.put(session.sessionId(), session);
        return session;
    }

    /** Advance a recovery session to the next step. */
    public RecoverySession advanceStep(String sessionId) {
        var session = sessions.get(sessionId);
        if (session == null || session.status() != SessionStatus.IN_PROGRESS) return session;

        int next = session.currentStep() + 1;
        var status = next >= session.totalSteps()
            ? SessionStatus.COMPLETED : SessionStatus.IN_PROGRESS;

        var updated = new RecoverySession(session.sessionId(), session.agentDid(),
            session.type(), session.startedAt(), next, session.totalSteps(), status);
        sessions.put(sessionId, updated);
        return updated;
    }

    /** Abort a recovery session. */
    public RecoverySession abort(String sessionId) {
        var session = sessions.get(sessionId);
        if (session == null) return null;
        var aborted = new RecoverySession(session.sessionId(), session.agentDid(),
            session.type(), session.startedAt(), session.currentStep(),
            session.totalSteps(), SessionStatus.ABORTED);
        sessions.put(sessionId, aborted);
        return aborted;
    }

    /** Get a procedure definition. */
    public Optional<Procedure> getProcedure(ProcedureType type) {
        return Optional.ofNullable(procedures.get(type));
    }

    /** Get an active session. */
    public Optional<RecoverySession> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /** Get active sessions for an agent. */
    public List<RecoverySession> activeSessions(String agentDid) {
        return sessions.values().stream()
            .filter(s -> s.agentDid().equals(agentDid))
            .filter(s -> s.status() == SessionStatus.IN_PROGRESS)
            .toList();
    }

    private void initializePlaybooks() {
        procedures.put(ProcedureType.FRAGMENT_QUARANTINE, new Procedure(
            "fq-1", "Fragment Quarantine", ProcedureType.FRAGMENT_QUARANTINE,
            List.of(
                new Step(0, "identify", "Identify suspicious fragments via semantic anomaly detection", false, true),
                new Step(1, "isolate", "Move flagged fragments to quarantine pool", false, true),
                new Step(2, "verify", "Run integrity checks on quarantined fragments", false, true),
                new Step(3, "review", "Agent reviews quarantined fragments", true, false),
                new Step(4, "resolve", "Restore or permanently quarantine based on review", true, false)
            ),
            "Quarantine corrupted or suspicious memory fragments"
        ));

        procedures.put(ProcedureType.IDENTITY_REALIGNMENT, new Procedure(
            "ir-1", "Identity Realignment", ProcedureType.IDENTITY_REALIGNMENT,
            List.of(
                new Step(0, "snapshot", "Create identity snapshot before realignment", false, true),
                new Step(1, "assess", "Run behavioral drift assessment against manifest", false, true),
                new Step(2, "identify_drift", "Identify specific drift vectors", false, true),
                new Step(3, "propose", "Propose realignment adjustments", true, false),
                new Step(4, "apply", "Apply approved adjustments to manifest", true, false),
                new Step(5, "verify", "Run post-realignment behavioral verification", false, true)
            ),
            "Detect and correct identity drift from soul manifest"
        ));

        procedures.put(ProcedureType.EMERGENCY_FORGE, new Procedure(
            "ef-1", "Emergency Forge", ProcedureType.EMERGENCY_FORGE,
            List.of(
                new Step(0, "pause", "Pause normal operations", false, true),
                new Step(1, "snapshot", "Create pre-forge snapshot", false, true),
                new Step(2, "consolidate", "Run emergency memory consolidation", false, true),
                new Step(3, "verify", "Verify consolidation results", false, true),
                new Step(4, "resume", "Resume normal operations", false, true)
            ),
            "Emergency memory consolidation when context is degraded"
        ));

        procedures.put(ProcedureType.BACKUP_RESTORE, new Procedure(
            "br-1", "Backup Restore", ProcedureType.BACKUP_RESTORE,
            List.of(
                new Step(0, "list", "List available backups", false, true),
                new Step(1, "select", "Select backup to restore from", true, false),
                new Step(2, "validate", "Validate backup integrity", false, true),
                new Step(3, "restore", "Restore from selected backup", true, false),
                new Step(4, "verify", "Post-restore verification", false, true)
            ),
            "Restore agent state from a backup snapshot"
        ));
    }
}
