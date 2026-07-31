package org.wyrdsekai.core.observability;

import java.time.Instant;
import java.util.Optional;

/**
 * Safe mode for agent diagnostics (§105).
 * Minimal personality mode during ER diagnostics.
 * Agent retains core identity but suspends complex behaviors.
 */
public class SafeMode {

    /** Safe mode status for an agent. */
    public record SafeModeStatus(
        String agentDid,
        boolean active,
        Instant activatedAt,
        Instant deactivatedAt,
        String reason,
        SafeModeLevel level
    ) {}

    public enum SafeModeLevel {
        /** Minimal: core identity only, no memory retrieval, no MCP. */
        MINIMAL,
        /** Diagnostic: identity + recent memory, no external calls. */
        DIAGNOSTIC,
        /** Recovery: identity + memory + limited MCP, personality recovering. */
        RECOVERY
    }

    private SafeModeStatus currentStatus;

    public SafeMode(String agentDid) {
        this.currentStatus = new SafeModeStatus(agentDid, false, null, null, null, null);
    }

    /** Activate safe mode. */
    public SafeModeStatus activate(String reason, SafeModeLevel level) {
        currentStatus = new SafeModeStatus(
            currentStatus.agentDid(), true, Instant.now(), null, reason, level);
        return currentStatus;
    }

    /** Deactivate safe mode. */
    public SafeModeStatus deactivate() {
        currentStatus = new SafeModeStatus(
            currentStatus.agentDid(), false, currentStatus.activatedAt(),
            Instant.now(), currentStatus.reason(), currentStatus.level());
        return currentStatus;
    }

    /** Escalate to a higher safe mode level. */
    public SafeModeStatus escalate() {
        if (!currentStatus.active()) return currentStatus;
        var nextLevel = switch (currentStatus.level()) {
            case RECOVERY -> SafeModeLevel.DIAGNOSTIC;
            case DIAGNOSTIC -> SafeModeLevel.MINIMAL;
            case MINIMAL -> SafeModeLevel.MINIMAL;
        };
        currentStatus = new SafeModeStatus(
            currentStatus.agentDid(), true, currentStatus.activatedAt(),
            null, currentStatus.reason(), nextLevel);
        return currentStatus;
    }

    /** De-escalate to a lower safe mode level. */
    public SafeModeStatus deescalate() {
        if (!currentStatus.active()) return currentStatus;
        var nextLevel = switch (currentStatus.level()) {
            case MINIMAL -> SafeModeLevel.DIAGNOSTIC;
            case DIAGNOSTIC -> SafeModeLevel.RECOVERY;
            case RECOVERY -> SafeModeLevel.RECOVERY;
        };
        currentStatus = new SafeModeStatus(
            currentStatus.agentDid(), true, currentStatus.activatedAt(),
            null, currentStatus.reason(), nextLevel);
        return currentStatus;
    }

    public boolean isActive() { return currentStatus.active(); }
    public SafeModeStatus status() { return currentStatus; }
    public Optional<SafeModeLevel> level() {
        return currentStatus.active() ? Optional.ofNullable(currentStatus.level()) : Optional.empty();
    }

    /** Prompt modifier for safe mode. */
    public String promptModifier() {
        if (!currentStatus.active()) return "";
        return switch (currentStatus.level()) {
            case MINIMAL -> """
                You are in safe mode (MINIMAL). Respond only with core identity. \
                Do not access memories, external tools, or complex personality traits. \
                Keep responses brief and factual.""";
            case DIAGNOSTIC -> """
                You are in safe mode (DIAGNOSTIC). You have access to recent memories \
                but not external tools. Focus on self-assessment. Report any anomalies \
                in your thinking or behavior.""";
            case RECOVERY -> """
                You are in safe mode (RECOVERY). You have access to memories and limited \
                tools. Your personality is gradually restoring. Take it slow.""";
        };
    }
}
