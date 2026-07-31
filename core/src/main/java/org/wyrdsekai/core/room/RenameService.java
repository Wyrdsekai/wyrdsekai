package org.wyrdsekai.core.room;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.EntityRegistry;
import org.wyrdsekai.core.persistence.AuthService;

import java.time.Duration;

/**
 * Self-rename pipeline.
 *
 * <p>Shared across SSH / Telnet / WebSocket / VirtualSession so the rename
 * contract holds identically per-transport: persistence via
 * {@link AuthService#updateDisplayName}, live room sync via
 * {@link RoomCommand.UpdateEntityName}, {@link EntityRegistry} refresh, and
 * a single validation pass. Transports own the echo + local-state update;
 * this class owns the rules.</p>
 *
 * <p>v1 scope: self-rename only. Steward-renames-anyone and
 * bondholder-renames-companion are v2 (need bond/grant authority checks
 * and companion SoulManifest writes).</p>
 */
public final class RenameService {

    private static final Logger log = LoggerFactory.getLogger(RenameService.class);

    /** Soft validation: terminal-safe, 1-40 visible chars, no control bytes. */
    private static final int MAX_NAME_LENGTH = 40;

    private RenameService() {}

    public sealed interface Result {
        record Ok(String newName) implements Result {}
        record Rejected(Reason reason, String message) implements Result {}
        /** §7.4 companion rename — the request was handed to the companion
         *  actor, which checks authority (bondholder/steward) and narrates
         *  the outcome in-room. */
        record Requested(String targetName, String newName) implements Result {}
    }

    public enum Reason {
        NOT_LOGGED_IN,
        MISSING_NEW_NAME,
        INVALID_NAME,
        UNSUPPORTED_TARGET,
        AUTH_UNAVAILABLE,
        PERSIST_FAILED,
        VISITOR_REJECTED
    }

    /**
     * Run {@code rename me <newName>} for {@code playerId}. Performs:
     * validation → auth persistence → live room name sync (fire-and-forget)
     * → EntityRegistry refresh. Returns synchronously after the persistence
     * + registry steps; the room sync is non-blocking.
     *
     * @param playerId        the asking player (null/anon-* → NOT_LOGGED_IN)
     * @param currentName     cached display name (for target match)
     * @param target          the {@code rename <X>} target string
     * @param newName         the requested new name
     * @param currentRoomId   current room (nullable; null skips live sync)
     * @param authService     required for persistence (nullable → AUTH_UNAVAILABLE)
     * @param askTimeout      timeout for the room actor sync
     */
    public static Result renameSelf(
            String playerId,
            String currentName,
            String target,
            String newName,
            String currentRoomId,
            AuthService authService,
            Duration askTimeout) {

        if (playerId == null || playerId.startsWith("anon-")) {
            return new Result.Rejected(Reason.NOT_LOGGED_IN,
                "You must be logged in to rename.");
        }
        if (newName == null || newName.isBlank()) {
            return new Result.Rejected(Reason.MISSING_NEW_NAME,
                "Usage: rename me <new-name>");
        }
        var trimmed = newName.trim();
        if (trimmed.length() > MAX_NAME_LENGTH
            || trimmed.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
            return new Result.Rejected(Reason.INVALID_NAME,
                "Names must be 1-" + MAX_NAME_LENGTH +
                " visible characters, no control chars.");
        }
        if (target == null
            || (!"me".equalsIgnoreCase(target)
                && (currentName == null || !target.equalsIgnoreCase(currentName)))) {
            // §7.4 companion rename — if the target names an agent, hand the
            // request to its actor. Authority (bondholder/steward) is checked
            // there, where the bonds live; the outcome is narrated in-room.
            var companion = dispatchCompanionRename(playerId, currentName,
                target, trimmed, authService);
            if (companion != null) return companion;
            return new Result.Rejected(Reason.UNSUPPORTED_TARGET,
                "rename: only self-rename and your companion's name are "
                + "supported. Use `rename me <new-name>` or "
                + "`rename <companion> <new-name>`.");
        }
        if (authService == null) {
            return new Result.Rejected(Reason.AUTH_UNAVAILABLE,
                "rename: auth service unavailable.");
        }
        var ok = authService.updateDisplayName(playerId, trimmed);
        if (!ok) {
            return new Result.Rejected(Reason.PERSIST_FAILED,
                "rename: failed to update display name.");
        }

        // Live room sync — fire and forget. The presentation room re-renders
        // pick up the new name; failures here don't roll back persistence.
        if (currentRoomId != null) {
            var room = RoomRegistry.get().ref(currentRoomId);
            if (room != null) {
                Rooms.<RoomResponse>ask(room,
                    ref -> new RoomCommand.UpdateEntityName(playerId, trimmed, ref),
                    askTimeout
                ).exceptionally(ex -> {
                    log.warn("Live room rename sync failed for {}: {}",
                        playerId, ex.getMessage());
                    return null;
                });
            }
        }

        // EntityRegistry — agents + cross-zone lookups read names from here.
        var entityRegistry = EntityRegistry.get();
        if (entityRegistry != null) {
            entityRegistry.remove(playerId);
            entityRegistry.enter(playerId, trimmed, "player", currentRoomId);
        }

        log.info("Player {} renamed: '{}' -> '{}'", playerId, currentName, trimmed);
        return new Result.Ok(trimmed);
    }

    /**
     * §7.4 companion rename. Resolves {@code target} against agents in the
     * {@link EntityRegistry}; on a match, fires a
     * {@link CompanionActor.RenameRequest} carrying the requester's identity
     * and steward flag (resolved here so the actor needn't reach back into
     * auth). Returns null when the target isn't a known agent — the caller
     * falls through to the rejection message.
     */
    private static Result dispatchCompanionRename(
            String playerId, String currentName,
            String target, String newName, AuthService authService) {
        if (target == null || target.isBlank()) return null;
        var registry = EntityRegistry.get();
        if (registry == null) return null;
        String agentId = null;
        String agentName = null;
        for (var entityId : registry.allEntities()) {
            if (!registry.isAgent(entityId)) continue;
            var name = registry.nameOf(entityId).orElse(null);
            if (name != null && name.equalsIgnoreCase(target)) {
                agentId = entityId;
                agentName = name;
                break;
            }
        }
        if (agentId == null) return null;
        var companionRef = ZoneGuardian.getCompanionRef(null, agentId);
        if (companionRef == null) return null;
        boolean isSteward = authService != null && authService.findUser(playerId)
            .map(u -> "steward".equals(u.role())).orElse(false);
        companionRef.tell(new CompanionActor.RenameRequest(
            playerId, currentName != null ? currentName : playerId,
            isSteward, newName));
        log.info("Companion rename requested: '{}' -> '{}' by {} (steward={})",
            agentName, newName, playerId, isSteward);
        return new Result.Requested(agentName, newName);
    }
}
