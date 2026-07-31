package org.wyrdsekai.core.household;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Set;

/**
 * A human member of a Wyrdsekai household (§101).
 * Unix/Linux permission model: at least one root (steward).
 * Anyone can be made steward (sudo). Granular permissions.
 */
public record HouseholdMember(
    @JsonProperty("did") String did,
    @JsonProperty("name") String name,
    @JsonProperty("role") Role role,
    @JsonProperty("permissions") Set<String> permissions,
    @JsonProperty("groups") Set<String> groups,
    @JsonProperty("joinedAt") Instant joinedAt,
    @JsonProperty("active") boolean active
) {

    @JsonCreator
    public HouseholdMember {}

    public enum Role {
        /** Root steward — full control. At least one required. */
        STEWARD,
        /** Regular member — permissions determine access. */
        MEMBER,
        /** Guest — limited, temporary access. */
        GUEST,
        /** Child — age-gated restrictions (§100). */
        CHILD
    }

    // ── Permission constants ──

    public static final String PERM_AGENT_CREATE = "agent:create";
    public static final String PERM_AGENT_DELETE = "agent:delete";
    public static final String PERM_AGENT_CONFIG = "agent:config";
    public static final String PERM_BUDGET_SET = "budget:set";
    public static final String PERM_BUDGET_VIEW = "budget:view";
    public static final String PERM_TRUST_MANAGE = "trust:manage";
    public static final String PERM_SAFETY_SET = "safety:set";
    public static final String PERM_MCP_MANAGE = "mcp:manage";
    public static final String PERM_TOPOLOGY_MANAGE = "topology:manage";
    public static final String PERM_MEMBER_MANAGE = "member:manage";
    public static final String PERM_ROOM_ENTER = "room:enter";
    public static final String PERM_ROOM_SCRIPT = "room:script";
    public static final String PERM_EXPORT = "export";
    public static final String PERM_ALL = "*";

    /** Whether this member is a steward (root). */
    public boolean isSteward() {
        return role == Role.STEWARD;
    }

    /** Check if member has a specific permission. */
    public boolean hasPermission(String permission) {
        if (!active) return false;
        if (role == Role.STEWARD) return true; // steward = root
        return permissions.contains(PERM_ALL) || permissions.contains(permission);
    }

    /** Check if member belongs to a group. */
    public boolean inGroup(String group) {
        return groups != null && groups.contains(group);
    }

    /** Create a steward member. */
    public static HouseholdMember steward(String did, String name) {
        return new HouseholdMember(did, name, Role.STEWARD,
            Set.of(PERM_ALL), Set.of("stewards"), Instant.now(), true);
    }

    /** Create a regular member with specific permissions. */
    public static HouseholdMember member(String did, String name, Set<String> permissions) {
        return new HouseholdMember(did, name, Role.MEMBER,
            Set.copyOf(permissions), Set.of(), Instant.now(), true);
    }

    /** Create a guest member with minimal permissions. */
    public static HouseholdMember guest(String did, String name) {
        return new HouseholdMember(did, name, Role.GUEST,
            Set.of(PERM_ROOM_ENTER, PERM_BUDGET_VIEW), Set.of(), Instant.now(), true);
    }

    /** Create a child member. */
    public static HouseholdMember child(String did, String name) {
        return new HouseholdMember(did, name, Role.CHILD,
            Set.of(PERM_ROOM_ENTER), Set.of(), Instant.now(), true);
    }

    /** Deactivate this member. */
    public HouseholdMember deactivate() {
        return new HouseholdMember(did, name, role, permissions, groups, joinedAt, false);
    }

    /** Promote to steward. */
    public HouseholdMember promote() {
        return new HouseholdMember(did, name, Role.STEWARD,
            Set.of(PERM_ALL), groups, joinedAt, active);
    }
}
