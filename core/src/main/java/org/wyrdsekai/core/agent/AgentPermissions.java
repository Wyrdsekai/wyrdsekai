package org.wyrdsekai.core.agent;

import org.wyrdsekai.scripting.sandbox.SandboxLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Zone-level permission set for an agent. Controls what zone services
 * an agent can access (codezaiku.create, iot.lights, etc.).
 *
 * <p>Evaluation order:
 * <ol>
 *   <li>Check DENY rules first — if any matching DENY exists, action is denied</li>
 *   <li>Check ALLOW rules — if any matching ALLOW exists, action is allowed</li>
 *   <li>Default: deny (must be explicitly granted)</li>
 * </ol>
 *
 * <p>This complements the Ward system (room-level access) with service-level
 * granularity. Stored as soul items (category "permission") and managed by
 * the human steward.
 */
public final class AgentPermissions {

    private final List<ZonePermission> permissions;

    public AgentPermissions(List<ZonePermission> permissions) {
        this.permissions = permissions != null ? List.copyOf(permissions) : List.of();
    }

    /**
     * Check if the agent is allowed to perform namespace.action.
     * Deny overrides allow. Default is deny.
     */
    public boolean isAllowed(String namespace, String action) {
        if (namespace == null || action == null) return false;

        // Check deny first — any matching deny blocks the action
        boolean denied = permissions.stream()
            .filter(p -> p.level() == ZonePermission.PermissionLevel.DENY)
            .anyMatch(p -> p.matches(namespace, action));
        if (denied) return false;

        // Check allow — any matching allow grants the action
        return permissions.stream()
            .filter(p -> p.level() == ZonePermission.PermissionLevel.ALLOW)
            .anyMatch(p -> p.matches(namespace, action));
    }

    /** The underlying permission entries (immutable). */
    public List<ZonePermission> entries() {
        return permissions;
    }

    // --- Static factories for role-based defaults ---

    /**
     * Companion permissions: broad read, selective write.
     * Can read status from all namespaces, but write actions need explicit grant.
     */
    public static AgentPermissions companion() {
        return new AgentPermissions(List.of(
            new ZonePermission("*", "status", ZonePermission.PermissionLevel.ALLOW),
            new ZonePermission("*", "list", ZonePermission.PermissionLevel.ALLOW),
            new ZonePermission("*", "info", ZonePermission.PermissionLevel.ALLOW)
        ));
    }

    /**
     * Engineer permissions: engine room admin, monitoring, infrastructure access.
     */
    public static AgentPermissions engineer() {
        return new AgentPermissions(List.of(
            new ZonePermission("*", "status", ZonePermission.PermissionLevel.ALLOW),
            new ZonePermission("*", "list", ZonePermission.PermissionLevel.ALLOW),
            new ZonePermission("*", "info", ZonePermission.PermissionLevel.ALLOW),
            new ZonePermission("engine", "*", ZonePermission.PermissionLevel.ALLOW),
            new ZonePermission("monitoring", "*", ZonePermission.PermissionLevel.ALLOW),
            new ZonePermission("codezaiku", "status", ZonePermission.PermissionLevel.ALLOW),
            new ZonePermission("codezaiku", "list", ZonePermission.PermissionLevel.ALLOW)
        ));
    }

    /**
     * Warden permissions: security tools, flagging, but not approval authority.
     */
    public static AgentPermissions warden() {
        return new AgentPermissions(List.of(
            new ZonePermission("*", "status", ZonePermission.PermissionLevel.ALLOW),
            new ZonePermission("*", "list", ZonePermission.PermissionLevel.ALLOW),
            new ZonePermission("security", "*", ZonePermission.PermissionLevel.ALLOW),
            new ZonePermission("ward", "*", ZonePermission.PermissionLevel.ALLOW)
        ));
    }

    /**
     * New/untrusted agent permissions: read-only until steward grants more.
     */
    public static AgentPermissions newAgent() {
        return new AgentPermissions(List.of(
            new ZonePermission("*", "status", ZonePermission.PermissionLevel.ALLOW),
            new ZonePermission("*", "list", ZonePermission.PermissionLevel.ALLOW)
        ));
    }

    /**
     * Unrestricted permissions: full access to everything.
     * For the human steward's primary companion.
     */
    public static AgentPermissions unrestricted() {
        return new AgentPermissions(List.of(
            new ZonePermission("*", "*", ZonePermission.PermissionLevel.ALLOW)
        ));
    }

    /**
     * Build a new permissions set by adding additional entries.
     */
    public AgentPermissions withAdditional(List<ZonePermission> additional) {
        var combined = new ArrayList<>(permissions);
        combined.addAll(additional);
        return new AgentPermissions(combined);
    }

    /**
     * Check if this agent has granted context access for a source.
     * Delegates to the global {@link ContextAccessManager}.
     *
     * @param source Context source (e.g. "active_window", "calendar", "voice")
     * @return true if access is granted
     */
    public boolean hasContextAccess(String source) {
        var mgr = ContextAccessManager.get();
        if (mgr == null) return false;
        // agentId is not stored here — caller must use ContextAccessManager directly
        // This method exists for interface compatibility with the spec.
        return false; // Use ContextAccessManager.isGranted(agentId, source) instead
    }

    /**
     * Get the granted scope for a context source.
     * Delegates to the global {@link ContextAccessManager}.
     *
     * @param source Context source
     * @return Scope string (e.g. "vscode,terminal"), or null if not granted
     */
    public String contextScope(String source) {
        var mgr = ContextAccessManager.get();
        if (mgr == null) return null;
        // Same note as hasContextAccess — use ContextAccessManager directly
        return null;
    }

    /**
     * Determine the maximum sandbox level this agent is allowed.
     * Based on permission grants in the "sandbox" namespace:
     * <ul>
     *   <li>New agent (no sandbox grants): SKILL_BASIC</li>
     *   <li>Companion (sandbox.data): SKILL_DATA</li>
     *   <li>Trusted (sandbox.server): SKILL_SERVER</li>
     *   <li>Steward-approved (sandbox.full): SKILL_FULL</li>
     *   <li>Unrestricted (*.* allow): SKILL_FULL</li>
     * </ul>
     *
     * @return The maximum sandbox level for this agent
     */
    public SandboxLevel maxSandboxLevel() {
        // Unrestricted agents get full access
        if (isAllowed("*", "*")) {
            return SandboxLevel.SKILL_FULL;
        }
        // Check specific sandbox namespace grants (highest first)
        if (isAllowed("sandbox", "full")) {
            return SandboxLevel.SKILL_FULL;
        }
        if (isAllowed("sandbox", "server")) {
            return SandboxLevel.SKILL_SERVER;
        }
        if (isAllowed("sandbox", "data")) {
            return SandboxLevel.SKILL_DATA;
        }
        // Default: SKILL_BASIC (every agent can at least run basic skills)
        return SandboxLevel.SKILL_BASIC;
    }
}
