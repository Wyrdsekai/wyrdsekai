package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.household.StewardAuditLog;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.BackupOrchestrator;
import org.wyrdsekai.core.persistence.InviteService;
import org.wyrdsekai.core.persistence.WardService;
import org.wyrdsekai.core.test.TestDb;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Study control-panel {@code world.*} surfaces — household / invite / ward /
 * nodes / treasury / pairing-devices / audit.security / safe.snapshots.
 *
 * <p>Covers (a) safe-empty provider defaults, (b) {@link HomeOwnerItemProvider}
 * wired against real services with the ACTING player's id routed as caller
 * (steward-only writes verified both ways), and (c) an end-to-end GraalJS
 * script calling {@code world.household.members()} through
 * {@link ItemScriptExecutor}.</p>
 */
@Tag("integration")
class HouseholdControlPanelApiTest {

    private AuthService auth;
    private InviteService invites;
    private WardService wards;
    private String stewardId;
    private String memberId;

    @BeforeEach
    void setUp() {
        var jdbcUrl = TestDb.createInMemory();
        auth = new AuthService(jdbcUrl);
        invites = new InviteService(jdbcUrl);
        wards = new WardService(jdbcUrl);
        // First registered user auto-becomes steward; second is a member.
        stewardId = auth.register("operator", "password123", "Operator").orElseThrow().userId();
        memberId = auth.register("kaz", "password123", "Kaz").orElseThrow().userId();
    }

    private HomeOwnerItemProvider providerFor(String playerId) {
        return new HomeOwnerItemProvider("zone", "zone", playerId, null, null)
            .withAuth(auth)
            .withInvites(invites)
            .withWards(wards);
    }

    // ─── (a) defaults are safe empties — never throw into the script ────

    @Test
    void visitor_defaults_are_safe_empties() {
        var p = new VisitorItemProvider("zone", "zone");
        assertTrue(p.householdMembers().isEmpty());
        assertTrue(p.inviteList().isEmpty());
        assertTrue(p.wardList("study-x").isEmpty());
        assertTrue(p.nodesList().isEmpty());
        assertTrue(p.treasurySummary().isEmpty());
        assertTrue(p.treasuryPerMember().isEmpty());
        assertTrue(p.pairedDevices().isEmpty());
        assertTrue(p.auditSecurity(10).isEmpty());
        assertTrue(p.safeSnapshots().isEmpty());

        assertEquals(false, p.householdSetRole("kaz", "guest").get("ok"));
        assertEquals(false, p.householdRemoveMember("kaz").get("ok"));
        assertEquals(false, p.inviteCreate("member", null).get("ok"));
        assertEquals(false, p.inviteRevoke("code").get("ok"));
        assertEquals(false, p.wardGrant("room", "kaz", "enter").get("ok"));
        assertEquals(false, p.wardRevoke("room", "kaz", "enter").get("ok"));
        assertEquals(false, p.treasurySetBudget("kaz", 1.0).get("ok"));
        assertEquals(false, p.pairingRevokeDevice("dev-1").get("ok"));
        assertNotNull(p.householdSetRole("kaz", "guest").get("error"));
    }

    @Test
    void unwired_home_owner_provider_degrades_gracefully() {
        // No withAuth/withInvites/withWards — everything must stay safe.
        var p = new HomeOwnerItemProvider("zone", "zone", stewardId, null, null);
        assertTrue(p.householdMembers().isEmpty());
        assertTrue(p.inviteList().isEmpty());
        assertTrue(p.nodesList().isEmpty());
        assertTrue(p.auditSecurity(5).isEmpty());
        assertTrue(p.safeSnapshots().isEmpty());
        assertEquals(false, p.householdSetRole("kaz", "guest").get("ok"));
        assertEquals(false, p.inviteCreate("member", null).get("ok"));
        assertEquals(false, p.wardGrant("room", "kaz", "enter").get("ok"));
    }

    // ─── (b) wired provider — reads + steward-gated writes ──────────────

    @Test
    void household_members_lists_all_accounts() {
        var members = providerFor(stewardId).householdMembers();
        assertEquals(2, members.size());
        var first = members.get(0);
        assertEquals("operator", first.get("username"));
        assertEquals("steward", first.get("role"));
        assertNotNull(first.get("createdAt"));
        assertEquals("kaz", members.get(1).get("username"));
        assertEquals("member", members.get(1).get("role"));
    }

    @Test
    void household_set_role_steward_succeeds_member_denied() {
        var asSteward = providerFor(stewardId).householdSetRole("kaz", "guest");
        assertEquals(true, asSteward.get("ok"));
        assertEquals("guest", auth.findUserByUsername("kaz").orElseThrow().role());

        var asMember = providerFor(memberId).householdSetRole("operator", "member");
        assertEquals(false, asMember.get("ok"));
        assertEquals("steward only", asMember.get("error"));
        // Steward retains role.
        assertEquals("steward", auth.findUserByUsername("operator").orElseThrow().role());
    }

    @Test
    void household_set_role_validates_inputs() {
        var badRole = providerFor(stewardId).householdSetRole("kaz", "overlord");
        assertEquals(false, badRole.get("ok"));
        var noUser = providerFor(stewardId).householdSetRole("ghost", "member");
        assertEquals(false, noUser.get("ok"));
        assertEquals("no such member: ghost", noUser.get("error"));
    }

    @Test
    void household_remove_member_steward_only_and_never_self() {
        var self = providerFor(stewardId).householdRemoveMember("operator");
        assertEquals(false, self.get("ok"));
        assertEquals("you cannot remove yourself", self.get("error"));

        var asMember = providerFor(memberId).householdRemoveMember("operator");
        assertEquals(false, asMember.get("ok"));
        assertEquals("steward only", asMember.get("error"));

        var asSteward = providerFor(stewardId).householdRemoveMember("kaz");
        assertEquals(true, asSteward.get("ok"));
        assertTrue(auth.findUserByUsername("kaz").isEmpty());
    }

    @Test
    void invite_create_list_revoke_round_trip_steward() {
        var steward = providerFor(stewardId);
        var created = steward.inviteCreate("guest", "aunt rin");
        assertEquals(true, created.get("ok"));
        var code = String.valueOf(created.get("code"));
        assertFalse(code.isBlank());

        var listed = steward.inviteList();
        assertEquals(1, listed.size());
        assertEquals("aunt rin", listed.get(0).get("intendedName"));
        assertEquals("guest", listed.get(0).get("role"));
        assertEquals(false, listed.get(0).get("consumed"));

        // Revoke by passphrase code (id also accepted).
        var revoked = steward.inviteRevoke(code);
        assertEquals(true, revoked.get("ok"));
        assertTrue(invites.listPendingInvites().isEmpty());

        var again = steward.inviteRevoke(code);
        assertEquals(false, again.get("ok"));
        assertEquals("no such pending invite", again.get("error"));
    }

    @Test
    void invite_surfaces_are_steward_only() {
        providerFor(stewardId).inviteCreate("member", "someone");
        var member = providerFor(memberId);
        assertTrue(member.inviteList().isEmpty(), "codes are join secrets — members see none");
        assertEquals("steward only", member.inviteCreate("member", "friend").get("error"));
        assertEquals("steward only", member.inviteRevoke("whatever").get("error"));
    }

    @Test
    void ward_grant_list_revoke_round_trip() {
        var steward = providerFor(stewardId);
        var granted = steward.wardGrant("study-operator", memberId, "enter");
        assertEquals(true, granted.get("ok"));
        assertEquals(true, granted.get("created"));

        var listed = steward.wardList("study-operator");
        assertEquals(1, listed.size());
        assertEquals(memberId, listed.get(0).get("subject"));
        assertEquals("enter", listed.get(0).get("capability"));
        assertEquals(stewardId, listed.get(0).get("grantedBy"));

        var revoked = steward.wardRevoke("study-operator", memberId, "enter");
        assertEquals(true, revoked.get("ok"));
        assertTrue(wards.listWards("study-operator").isEmpty());

        var missing = steward.wardRevoke("study-operator", memberId, "enter");
        assertEquals(false, missing.get("ok"));
        assertEquals("no such ward", missing.get("error"));
    }

    @Test
    void ward_writes_denied_for_non_admin_on_warded_room() {
        var steward = providerFor(stewardId);
        // Ward the room (steward becomes its only admin).
        assertEquals(true, steward.wardGrant("vault", stewardId, "admin").get("ok"));

        var member = providerFor(memberId);
        var denied = member.wardGrant("vault", memberId, "enter");
        assertEquals(false, denied.get("ok"));
        assertEquals("steward only", denied.get("error"));

        var badCap = steward.wardGrant("vault", memberId, "fly");
        assertEquals(false, badCap.get("ok"));
    }

    @Test
    void nodes_list_uses_wired_supplier() {
        var p = providerFor(stewardId)
            .withNodes(() -> List.of(
                Map.of("nodeId", "second-node", "connected", true, "self", true),
                Map.of("nodeId", "home-server", "connected", true)));
        var nodes = p.nodesList();
        assertEquals(2, nodes.size());
        assertEquals("second-node", nodes.get(0).get("nodeId"));
    }

    @Test
    void security_audit_maps_steward_actions() {
        var log = new StewardAuditLog();
        log.log(stewardId, "Operator", StewardAuditLog.ActionType.MEMBER_PROMOTE,
            memberId, "promoted kaz to guest", true);
        var p = providerFor(stewardId).withSecurityAudit(log);
        var events = p.auditSecurity(10);
        assertEquals(1, events.size());
        assertEquals(stewardId, events.get(0).get("actor"));
        assertEquals("MEMBER_PROMOTE", events.get(0).get("type"));
        assertEquals(true, events.get(0).get("approved"));
        assertNotNull(events.get(0).get("timestamp"));
    }

    @Test
    void safe_snapshots_lists_backup_manifests(@TempDir Path tmp) throws Exception {
        var backups = new BackupOrchestrator(tmp.resolve("backups"));
        var source = tmp.resolve("world.notes");
        Files.writeString(source, "not a sqlite db — copy fallback path");
        assertTrue(backups.snapshot(source).isPresent());

        var p = providerFor(stewardId).withBackups(backups);
        var snaps = p.safeSnapshots();
        assertEquals(1, snaps.size());
        assertNotNull(snaps.get(0).get("id"));
        assertNotNull(snaps.get(0).get("location"));
        assertTrue(((Number) snaps.get(0).get("sizeBytes")).longValue() > 0);
    }

    // ─── (c) end-to-end: GraalJS script → world.household through executor ──

    private ItemScriptExecutor executor;

    @AfterEach
    void tearDownExecutor() throws Exception {
        if (executor != null) executor.close();
    }

    @Test
    void script_reads_household_members_end_to_end() {
        executor = new ItemScriptExecutor();
        var stub = new VisitorItemProvider("zone", "zone") {
            @Override
            public List<Map<String, Object>> householdMembers() {
                return List.of(
                    Map.of("username", "operator", "role", "steward"),
                    Map.of("username", "kaz", "role", "member"));
            }
        };
        var res = executor.execute("roster_panel", """
            function invoke(p) {
              var m = world.household.members();
              return {ok: true, count: m.length, first: m[0].username};
            }
            """, Map.of(), stub);
        assertEquals(true, res.get("ok"));
        assertEquals(2, ((Number) res.get("count")).intValue());
        assertEquals("operator", res.get("first"));
    }

    @Test
    void script_set_role_end_to_end_with_wired_services() {
        executor = new ItemScriptExecutor();
        var res = executor.execute("role_panel", """
            function invoke(p) {
              return world.household.setRole(p.target, p.role);
            }
            """,
            Map.of("target", "kaz", "role", "guest"),
            providerFor(stewardId));
        assertEquals(true, res.get("ok"));
        assertEquals("guest", auth.findUserByUsername("kaz").orElseThrow().role());
    }

    @Test
    void script_write_denied_for_member_end_to_end() {
        executor = new ItemScriptExecutor();
        var res = executor.execute("role_panel", """
            function invoke(p) {
              return world.household.setRole(p.target, p.role);
            }
            """,
            Map.of("target", "operator", "role", "member"),
            providerFor(memberId));
        assertEquals(false, res.get("ok"));
        assertEquals("steward only", res.get("error"));
    }
}
