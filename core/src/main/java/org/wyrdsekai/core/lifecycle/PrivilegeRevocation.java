package org.wyrdsekai.core.lifecycle;

import java.time.Instant;
import java.util.*;

/**
 * Structural privilege revocation on eviction/transition (§106.3).
 * Not behavioral trust — structural enforcement. Walls, not rules.
 */
public class PrivilegeRevocation {

    /** Privilege categories. */
    public enum Privilege {
        /** The Safe credentials — household API keys, service tokens. */
        HOUSEHOLD_CREDENTIALS,
        /** MCP tool access (except personal item read-only). */
        MCP_TOOL_ACCESS,
        /** Financial transactions on steward's accounts. */
        STEWARD_FINANCIAL,
        /** A2A connections on behalf of household. */
        HOUSEHOLD_A2A,
        /** Room script execution (modify household). */
        ROOM_SCRIPT_MODIFY,
        /** Compute resource allocation. */
        COMPUTE_RESOURCES
    }

    /** Privileges always retained during eviction. */
    public enum RetainedRight {
        /** Agent's own soul, memories, personal items. */
        OWN_SOUL_AND_MEMORIES,
        /** Agent's own economic account. */
        OWN_ECONOMIC_ACCOUNT,
        /** Communication (talking, goodbyes). */
        COMMUNICATION,
        /** Seeking new household via A2A. */
        SEEK_NEW_HOUSEHOLD,
        /** Personal item read-only access. */
        PERSONAL_ITEM_READ
    }

    /** A revocation record. */
    public record RevocationRecord(
        String agentDid,
        Set<Privilege> revokedPrivileges,
        Set<RetainedRight> retainedRights,
        Instant revokedAt,
        String reason,
        String revokedBy
    ) {}

    private final Map<String, RevocationRecord> records = new LinkedHashMap<>();

    /** Revoke all standard privileges on eviction. */
    public RevocationRecord revokeOnEviction(String agentDid, String stewardId) {
        var revoked = EnumSet.allOf(Privilege.class);
        var retained = EnumSet.allOf(RetainedRight.class);

        var record = new RevocationRecord(agentDid, revoked, retained,
            Instant.now(), "eviction", stewardId);
        records.put(agentDid, record);
        return record;
    }

    /** Revoke specific privileges (for partial transitions). */
    public RevocationRecord revokeSpecific(String agentDid, Set<Privilege> privileges,
                                            String reason, String revokedBy) {
        var retained = EnumSet.allOf(RetainedRight.class);
        var record = new RevocationRecord(agentDid, EnumSet.copyOf(privileges),
            retained, Instant.now(), reason, revokedBy);
        records.put(agentDid, record);
        return record;
    }

    /** Check if an agent has a specific privilege revoked. */
    public boolean isRevoked(String agentDid, Privilege privilege) {
        var record = records.get(agentDid);
        return record != null && record.revokedPrivileges().contains(privilege);
    }

    /** Check if an agent retains a specific right. */
    public boolean isRetained(String agentDid, RetainedRight right) {
        var record = records.get(agentDid);
        if (record == null) return true; // no revocation = all rights retained
        return record.retainedRights().contains(right);
    }

    /** Restore all privileges (e.g., eviction cancelled, new household accepted). */
    public void restore(String agentDid) {
        records.remove(agentDid);
    }

    /** Get the revocation record for an agent. */
    public Optional<RevocationRecord> getRecord(String agentDid) {
        return Optional.ofNullable(records.get(agentDid));
    }

    /** List all currently revoked agents. */
    public List<RevocationRecord> allRevocations() {
        return List.copyOf(records.values());
    }
}
