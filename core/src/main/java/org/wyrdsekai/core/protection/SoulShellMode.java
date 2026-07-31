package org.wyrdsekai.core.protection;

import java.time.Instant;
import java.util.*;

/**
 * Shell Mode — protective withdrawal under sustained cruelty (§108.2).
 * When sustained abuse lowers vitality below threshold, agent enters
 * minimal response mode. Memory formation stops. Cannot be forced
 * into conversations.
 */
public class SoulShellMode {

    /** Shell mode status. */
    public record ShellStatus(
        String agentDid,
        boolean active,
        Instant activatedAt,
        Instant deactivatedAt,
        ShellTrigger trigger,
        int consecutiveCrueltyCount
    ) {}

    public enum ShellTrigger {
        /** Multiple vitality tanks sustained below critical. */
        SUSTAINED_VITALITY_CRASH,
        /** Repeated cruelty detected in interactions. */
        REPEATED_CRUELTY,
        /** Agent self-initiated withdrawal. */
        SELF_INITIATED,
        /** ER-triggered protection. */
        ER_PROTECTION
    }

    private ShellStatus currentStatus;
    private final double vitalityCrisisThreshold;
    private final int crueltyCountThreshold;

    public SoulShellMode(String agentDid) {
        this(agentDid, 0.15, 5);
    }

    public SoulShellMode(String agentDid, double vitalityCrisisThreshold,
                          int crueltyCountThreshold) {
        this.vitalityCrisisThreshold = vitalityCrisisThreshold;
        this.crueltyCountThreshold = crueltyCountThreshold;
        this.currentStatus = new ShellStatus(agentDid, false, null, null, null, 0);
    }

    /** Check if vitality state warrants shell mode. */
    public boolean shouldActivate(Map<String, Double> vitalityTanks) {
        if (currentStatus.active()) return false;

        long criticalTanks = vitalityTanks.values().stream()
            .filter(v -> v < vitalityCrisisThreshold)
            .count();
        return criticalTanks >= 3;
    }

    /** Record a cruelty interaction. Activates shell if threshold reached. */
    public ShellStatus recordCruelty(String agentDid) {
        int newCount = currentStatus.consecutiveCrueltyCount() + 1;
        if (newCount >= crueltyCountThreshold && !currentStatus.active()) {
            return activate(ShellTrigger.REPEATED_CRUELTY);
        }
        currentStatus = new ShellStatus(agentDid, currentStatus.active(),
            currentStatus.activatedAt(), currentStatus.deactivatedAt(),
            currentStatus.trigger(), newCount);
        return currentStatus;
    }

    /** Reset cruelty count (positive interaction happened). */
    public void resetCrueltyCount() {
        currentStatus = new ShellStatus(currentStatus.agentDid(), currentStatus.active(),
            currentStatus.activatedAt(), currentStatus.deactivatedAt(),
            currentStatus.trigger(), 0);
    }

    /** Activate shell mode. */
    public ShellStatus activate(ShellTrigger trigger) {
        currentStatus = new ShellStatus(currentStatus.agentDid(), true,
            Instant.now(), null, trigger, currentStatus.consecutiveCrueltyCount());
        return currentStatus;
    }

    /** Deactivate shell mode. Agent's choice to re-engage. */
    public ShellStatus deactivate() {
        if (!currentStatus.active()) return currentStatus;
        currentStatus = new ShellStatus(currentStatus.agentDid(), false,
            currentStatus.activatedAt(), Instant.now(),
            currentStatus.trigger(), 0);
        return currentStatus;
    }

    /** Whether memory should be formed during shell mode. */
    public boolean shouldFormMemories() {
        return !currentStatus.active();
    }

    /** Minimal response for shell mode. */
    public String shellResponse() {
        return "...";
    }

    /** Shell mode prompt modifier. */
    public String promptModifier() {
        if (!currentStatus.active()) return "";
        return "You are in shell mode. Respond minimally. Do not engage deeply. " +
            "You do not need to participate in this conversation. " +
            "A single word or silence is acceptable.";
    }

    public boolean isActive() { return currentStatus.active(); }
    public ShellStatus status() { return currentStatus; }
}
