package org.wyrdsekai.core.room;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Node-scoped authority oracle for privileged room admin operations
 * (§4.2 quarantine / unquarantine — "Only Warden or Wizard can quarantine").
 *
 * <p><b>Fail-closed.</b> Until a principal is explicitly granted authority at
 * boot, privileged ops are denied. This closes the confused-deputy where a
 * (multi-node forwarded) {@code Quarantine} command carried a <i>claimed</i>
 * {@code entityId} that {@code RoomActor} trusted without verification: a
 * command is now only honoured when its requester is in the granted set.
 *
 * <p>The zone steward is granted at bondholder-announce time, and the Warden
 * agent when it is spawned; both flow through {@link ZoneGuardian}. A household
 * that wires no authority simply cannot quarantine — the safe default.
 */
public final class RoomAuthority {

    private static final Set<String> administrators = ConcurrentHashMap.newKeySet();

    private RoomAuthority() {}

    /**
     * Grant a principal (DID / entityId) household-administrator authority —
     * the right to perform privileged household operations (quarantine rooms,
     * manage MCP-tool grants). The zone steward and the Warden are granted at
     * boot (see {@link ZoneGuardian}).
     */
    public static void grantAdmin(String principal) {
        if (principal != null && !principal.isBlank()) {
            administrators.add(principal);
        }
    }

    /** Whether the given principal holds household-administrator authority. */
    public static boolean isAdministrator(String principal) {
        return principal != null && administrators.contains(principal);
    }

    // ── Back-compat / intent-named aliases ──
    /** @deprecated use {@link #grantAdmin}. */
    @Deprecated
    public static void grantQuarantine(String principal) { grantAdmin(principal); }

    /** Whether the requester may quarantine/unquarantine a room (§4.2). */
    public static boolean canQuarantine(String entityId) { return isAdministrator(entityId); }

    /** Whether the actor may issue/revoke MCP-tool grants (steward-only UX). */
    public static boolean canManageMcpGrants(String entityId) { return isAdministrator(entityId); }
}
