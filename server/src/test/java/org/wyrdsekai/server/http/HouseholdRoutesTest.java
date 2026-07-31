package org.wyrdsekai.server.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.household.HouseholdMember;
import org.wyrdsekai.core.household.PermissionChecker;
import org.wyrdsekai.core.household.StewardAuditLog;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for HouseholdRoutes — validates permission checking, audit logging,
 * and member lifecycle operations that the HTTP handlers delegate to.
 */
class HouseholdRoutesTest {

    private PermissionChecker permissions;
    private StewardAuditLog auditLog;

    @BeforeEach
    void setUp() {
        permissions = new PermissionChecker();
        auditLog = new StewardAuditLog();
        // Register a steward (root) — required for all operations
        permissions.register(HouseholdMember.steward("did:steward:1", "Alice"));
    }

    // ── Happy path: add member ──

    @Test
    void steward_can_add_member() {
        // Steward checks permission, then registers a new member
        var check = permissions.check("did:steward:1", HouseholdMember.PERM_MEMBER_MANAGE);
        assertThat(check.allowed()).isTrue();

        var newMember = HouseholdMember.member("did:member:2", "Bob",
            Set.of(HouseholdMember.PERM_ROOM_ENTER, HouseholdMember.PERM_BUDGET_VIEW));
        permissions.register(newMember);

        auditLog.log("did:steward:1", "Alice", StewardAuditLog.ActionType.MEMBER_ADD,
            "did:member:2", "Added member: Bob", true);

        assertThat(permissions.memberCount()).isEqualTo(2);
        assertThat(permissions.getMember("did:member:2")).isPresent();
        assertThat(permissions.getMember("did:member:2").get().name()).isEqualTo("Bob");
        assertThat(auditLog.entryCount()).isEqualTo(1);
        assertThat(auditLog.recent(1).getFirst().approved()).isTrue();
    }

    // ── Happy path: promote to steward ──

    @Test
    void steward_can_promote_member() {
        // Add a regular member first
        permissions.register(HouseholdMember.member("did:member:2", "Bob", Set.of()));

        var result = permissions.promote("did:steward:1", "did:member:2");
        assertThat(result.allowed()).isTrue();

        auditLog.log("did:steward:1", "Alice", StewardAuditLog.ActionType.MEMBER_PROMOTE,
            "did:member:2", "Promoted to steward", true);

        var promoted = permissions.getMember("did:member:2").orElseThrow();
        assertThat(promoted.isSteward()).isTrue();
        assertThat(promoted.role()).isEqualTo(HouseholdMember.Role.STEWARD);
        assertThat(auditLog.recent(1).getFirst().type())
            .isEqualTo(StewardAuditLog.ActionType.MEMBER_PROMOTE);
    }

    // ── Happy path: remove member ──

    @Test
    void steward_can_remove_member() {
        permissions.register(HouseholdMember.member("did:member:2", "Bob", Set.of()));

        var check = permissions.checkMemberAction("did:steward:1", "did:member:2",
            HouseholdMember.PERM_MEMBER_MANAGE);
        assertThat(check.allowed()).isTrue();

        var removed = permissions.unregister("did:member:2");
        assertThat(removed).isTrue();

        auditLog.log("did:steward:1", "Alice", StewardAuditLog.ActionType.MEMBER_REMOVE,
            "did:member:2", "Removed member", true);

        assertThat(permissions.memberCount()).isEqualTo(1);
        assertThat(permissions.getMember("did:member:2")).isEmpty();
    }

    // ── Permission denial: regular member cannot add ──

    @Test
    void regular_member_cannot_add_member() {
        permissions.register(HouseholdMember.member("did:member:2", "Bob",
            Set.of(HouseholdMember.PERM_ROOM_ENTER)));

        var check = permissions.check("did:member:2", HouseholdMember.PERM_MEMBER_MANAGE);
        assertThat(check.allowed()).isFalse();
        assertThat(check.reason()).contains("missing permission");

        // Audit log records the denial
        auditLog.log("did:member:2", "Bob", StewardAuditLog.ActionType.MEMBER_ADD,
            null, "Permission denied: " + check.reason(), false);

        assertThat(auditLog.denied(10)).hasSize(1);
        assertThat(auditLog.denied(10).getFirst().approved()).isFalse();
    }

    // ── Permission denial: cannot remove last steward ──

    @Test
    void cannot_remove_last_steward() {
        // Try to remove the only steward
        var removed = permissions.unregister("did:steward:1");
        assertThat(removed).isFalse();

        // Steward should still be there
        assertThat(permissions.memberCount()).isEqualTo(1);
        assertThat(permissions.stewardCount()).isEqualTo(1);
    }

    // ── Deactivate member ──

    @Test
    void steward_can_deactivate_member() {
        permissions.register(HouseholdMember.member("did:member:2", "Bob", Set.of()));

        var result = permissions.deactivate("did:steward:1", "did:member:2");
        assertThat(result.allowed()).isTrue();

        auditLog.log("did:steward:1", "Alice", StewardAuditLog.ActionType.MEMBER_DEACTIVATE,
            "did:member:2", "Deactivated member", true);

        var deactivated = permissions.getMember("did:member:2").orElseThrow();
        assertThat(deactivated.active()).isFalse();
    }

    // ── Cannot deactivate last steward ──

    @Test
    void cannot_deactivate_last_steward() {
        var result = permissions.deactivate("did:steward:1", "did:steward:1");
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("last steward");
    }

    // ── Non-steward cannot modify steward ──

    @Test
    void regular_member_cannot_modify_steward() {
        permissions.register(HouseholdMember.member("did:member:2", "Bob",
            Set.of(HouseholdMember.PERM_MEMBER_MANAGE)));

        var check = permissions.checkMemberAction("did:member:2", "did:steward:1",
            HouseholdMember.PERM_MEMBER_MANAGE);
        assertThat(check.allowed()).isFalse();
        assertThat(check.reason()).contains("cannot modify steward");
    }

    // ── Audit log queries ──

    @Test
    void audit_log_records_multiple_actions_and_queries() {
        auditLog.log("did:steward:1", "Alice", StewardAuditLog.ActionType.MEMBER_ADD,
            "did:member:2", "Added Bob", true);
        auditLog.log("did:steward:1", "Alice", StewardAuditLog.ActionType.MEMBER_PROMOTE,
            "did:member:2", "Promoted Bob", true);
        auditLog.log("did:member:2", "Bob", StewardAuditLog.ActionType.MEMBER_ADD,
            "did:member:3", "Permission denied", false);

        assertThat(auditLog.entryCount()).isEqualTo(3);
        assertThat(auditLog.recent(10)).hasSize(3);
        assertThat(auditLog.byActor("did:steward:1", 10)).hasSize(2);
        assertThat(auditLog.byTarget("did:member:2", 10)).hasSize(2);
        assertThat(auditLog.denied(10)).hasSize(1);
    }
}
