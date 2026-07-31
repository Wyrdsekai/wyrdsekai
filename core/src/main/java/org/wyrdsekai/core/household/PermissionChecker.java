package org.wyrdsekai.core.household;

import org.wyrdsekai.core.economy.AttestationService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Permission middleware for household commands (§101).
 * Unix-style: system provides mechanism, household decides policy.
 * <p>
 * At least one steward (root) must exist at all times.
 */
public class PermissionChecker {

    /** Result of a permission check. */
    public record CheckResult(
        boolean allowed,
        String reason
    ) {
        public static CheckResult allow() {
            return new CheckResult(true, "permitted");
        }

        public static CheckResult deny(String reason) {
            return new CheckResult(false, reason);
        }
    }

    private final Map<String, HouseholdMember> members = new ConcurrentHashMap<>();

    /** Register a household member. */
    public void register(HouseholdMember member) {
        members.put(member.did(), member);
    }

    /** Unregister a member (if not the last steward). */
    public boolean unregister(String did) {
        var member = members.get(did);
        if (member == null) return false;

        // Prevent removing the last steward
        if (member.isSteward() && stewardCount() <= 1) return false;

        members.remove(did);
        return true;
    }

    /**
     * Check if a member has permission for an action.
     *
     * @param memberDid  the acting member's DID
     * @param permission the required permission
     * @return check result
     */
    public CheckResult check(String memberDid, String permission) {
        var member = members.get(memberDid);
        if (member == null) {
            return CheckResult.deny("not a household member");
        }
        if (!member.active()) {
            return CheckResult.deny("member account is deactivated");
        }
        if (member.hasPermission(permission)) {
            return CheckResult.allow();
        }
        return CheckResult.deny("missing permission: " + permission);
    }

    /**
     * Check if a member can perform an action on a target member.
     * Prevents non-stewards from modifying steward accounts.
     */
    public CheckResult checkMemberAction(String actorDid, String targetDid, String permission) {
        var basic = check(actorDid, permission);
        if (!basic.allowed()) return basic;

        var target = members.get(targetDid);
        if (target == null) return CheckResult.deny("target not found");

        var actor = members.get(actorDid);
        // Non-stewards cannot modify stewards
        if (target.isSteward() && !actor.isSteward()) {
            return CheckResult.deny("cannot modify steward account");
        }
        return CheckResult.allow();
    }

    /** Promote a member to steward (requires steward permission). */
    public CheckResult promote(String actorDid, String targetDid) {
        var check = check(actorDid, HouseholdMember.PERM_MEMBER_MANAGE);
        if (!check.allowed()) return check;

        var target = members.get(targetDid);
        if (target == null) return CheckResult.deny("member not found");
        if (target.isSteward()) return CheckResult.deny("already a steward");

        members.put(targetDid, target.promote());
        return CheckResult.allow();
    }

    /** Deactivate a member (requires steward + member:manage). */
    public CheckResult deactivate(String actorDid, String targetDid) {
        var check = checkMemberAction(actorDid, targetDid, HouseholdMember.PERM_MEMBER_MANAGE);
        if (!check.allowed()) return check;

        var target = members.get(targetDid);
        if (target.isSteward() && stewardCount() <= 1) {
            return CheckResult.deny("cannot deactivate the last steward");
        }

        members.put(targetDid, target.deactivate());
        return CheckResult.allow();
    }

    /**
     * Check permission with reputation gating.
     * Sensitive operations (MCP calls, cloud inference, room creation) require
     * a minimum reputation score. Low-reputation agents get restricted.
     *
     * @param memberDid     the acting member's DID
     * @param permission    the required permission
     * @param minReputation minimum reputation score (0.0-1.0) for this operation
     * @return check result
     */
    public CheckResult checkWithReputation(String memberDid, String permission,
                                            double minReputation) {
        var basic = check(memberDid, permission);
        if (!basic.allowed()) return basic;

        var attestation = AttestationService.get();
        if (attestation != null && minReputation > 0) {
            if (!attestation.meetsThreshold(memberDid, minReputation)) {
                var score = attestation.score(memberDid);
                return CheckResult.deny(
                    "reputation too low: " + String.format("%.2f", score.overall())
                    + " < " + String.format("%.2f", minReputation));
            }
        }
        return CheckResult.allow();
    }

    // ── Queries ──

    public Optional<HouseholdMember> getMember(String did) {
        return Optional.ofNullable(members.get(did));
    }

    public List<HouseholdMember> allMembers() {
        return List.copyOf(members.values());
    }

    public List<HouseholdMember> stewards() {
        return members.values().stream()
            .filter(HouseholdMember::isSteward)
            .filter(HouseholdMember::active)
            .toList();
    }

    public int memberCount() { return members.size(); }

    public int stewardCount() {
        return (int) members.values().stream()
            .filter(HouseholdMember::isSteward)
            .filter(HouseholdMember::active)
            .count();
    }
}
