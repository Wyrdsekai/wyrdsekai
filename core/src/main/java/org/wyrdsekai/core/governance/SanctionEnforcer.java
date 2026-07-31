package org.wyrdsekai.core.governance;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces moderation sanctions on entities (§32).
 * Bridges ModerationService decisions to RoomActor enforcement.
 * Tracks enforcement history for audit.
 */
public class SanctionEnforcer {

    /** An enforcement action applied or lifted. */
    public record EnforcementAction(
        String entityId,
        ModerationService.SanctionLevel level,
        String action,  // "applied", "escalated", "lifted"
        String reason,
        Instant timestamp
    ) {}

    private final ModerationService moderationService;
    private final List<EnforcementAction> history = new ArrayList<>();
    private final Map<String, List<String>> restrictedRooms = new ConcurrentHashMap<>();

    public SanctionEnforcer(ModerationService moderationService) {
        this.moderationService = moderationService;
    }

    /**
     * Apply a sanction to an entity.
     * @param entityId target entity
     * @param level sanction level
     * @param reason why
     * @param duration optional duration (null = permanent)
     * @return the enforcement action
     */
    public EnforcementAction applySanction(String entityId,
                                             ModerationService.SanctionLevel level,
                                             String reason, Duration duration) {
        Instant expiresAt = duration != null ? Instant.now().plus(duration) : null;
        moderationService.applySanction(entityId, level, reason, expiresAt);

        var action = new EnforcementAction(entityId, level, "applied", reason, Instant.now());
        history.add(action);
        return action;
    }

    /**
     * Escalate an entity's sanction to the next level.
     * @return the enforcement action
     */
    public EnforcementAction escalate(String entityId, String reason) {
        var sanction = moderationService.escalate(entityId, reason);
        var action = new EnforcementAction(entityId, sanction.level(), "escalated",
            reason, Instant.now());
        history.add(action);
        return action;
    }

    /**
     * Lift a sanction (set to NONE).
     * @return the enforcement action
     */
    public EnforcementAction liftSanction(String entityId, String reason) {
        moderationService.liftSanction(entityId);
        var action = new EnforcementAction(entityId,
            ModerationService.SanctionLevel.NONE, "lifted", reason, Instant.now());
        history.add(action);
        return action;
    }

    /**
     * Check if an entity is allowed to enter a room.
     * Banned entities cannot enter any room.
     * Suspended entities can only enter their current room.
     */
    public boolean canEnterRoom(String entityId, String roomId) {
        if (moderationService.isBanned(entityId)) return false;
        if (moderationService.isRestricted(entityId)) {
            var allowed = restrictedRooms.get(entityId);
            return allowed != null && allowed.contains(roomId);
        }
        return true;
    }

    /**
     * Check if an entity is allowed to speak.
     * Probation+ cannot speak. Warning can speak.
     */
    public boolean canSpeak(String entityId) {
        var sanction = moderationService.getSanction(entityId);
        return sanction.level().severity() < ModerationService.SanctionLevel.PROBATION.severity();
    }

    /**
     * Set rooms an entity is restricted to during suspension.
     */
    public void setRestrictedRooms(String entityId, List<String> roomIds) {
        restrictedRooms.put(entityId, List.copyOf(roomIds));
    }

    /** Get enforcement history for an entity. */
    public List<EnforcementAction> historyFor(String entityId) {
        return history.stream()
            .filter(a -> a.entityId().equals(entityId))
            .toList();
    }

    /** Get all enforcement history. */
    public List<EnforcementAction> allHistory() {
        return List.copyOf(history);
    }

    /** Current sanction level for an entity. */
    public ModerationService.SanctionLevel currentLevel(String entityId) {
        return moderationService.getSanction(entityId).level();
    }

    /** Human-readable summary. */
    public String describe() {
        if (history.isEmpty()) return "No enforcement actions recorded.";
        var sb = new StringBuilder("=== Enforcement History ===\n\n");
        sb.append("Total actions: ").append(history.size()).append("\n\n");
        history.stream().limit(10).forEach(a ->
            sb.append("  ").append(a.action()).append(" ")
                .append(a.level()).append(" on ").append(a.entityId())
                .append(" — ").append(a.reason()).append("\n"));
        return sb.toString().stripTrailing();
    }
}
