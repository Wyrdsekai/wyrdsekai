package org.wyrdsekai.core.household;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §101 Multi-Human Households.
 */
class HouseholdWaveTest {

    @Nested
    class HouseholdMemberTests {

        @Test
        void steward_has_all_permissions() {
            var steward = HouseholdMember.steward("did:alice", "Alice");
            assertTrue(steward.isSteward());
            assertTrue(steward.hasPermission(HouseholdMember.PERM_AGENT_DELETE));
            assertTrue(steward.hasPermission("anything"));
        }

        @Test
        void member_has_only_granted_permissions() {
            var member = HouseholdMember.member("did:bob", "Bob",
                Set.of(HouseholdMember.PERM_ROOM_ENTER, HouseholdMember.PERM_BUDGET_VIEW));
            assertFalse(member.isSteward());
            assertTrue(member.hasPermission(HouseholdMember.PERM_ROOM_ENTER));
            assertFalse(member.hasPermission(HouseholdMember.PERM_AGENT_DELETE));
        }

        @Test
        void guest_has_minimal_permissions() {
            var guest = HouseholdMember.guest("did:carol", "Carol");
            assertTrue(guest.hasPermission(HouseholdMember.PERM_ROOM_ENTER));
            assertFalse(guest.hasPermission(HouseholdMember.PERM_AGENT_CREATE));
        }

        @Test
        void child_has_room_enter_only() {
            var child = HouseholdMember.child("did:david", "David");
            assertEquals(HouseholdMember.Role.CHILD, child.role());
            assertTrue(child.hasPermission(HouseholdMember.PERM_ROOM_ENTER));
            assertFalse(child.hasPermission(HouseholdMember.PERM_BUDGET_SET));
        }

        @Test
        void deactivated_member_loses_permissions() {
            var member = HouseholdMember.steward("did:alice", "Alice");
            var deactivated = member.deactivate();
            assertFalse(deactivated.active());
            assertFalse(deactivated.hasPermission(HouseholdMember.PERM_ALL));
        }

        @Test
        void promote_to_steward() {
            var member = HouseholdMember.member("did:bob", "Bob", Set.of());
            var promoted = member.promote();
            assertTrue(promoted.isSteward());
        }
    }

    @Nested
    class PermissionCheckerTests {

        @Test
        void steward_can_do_anything() {
            var checker = new PermissionChecker();
            checker.register(HouseholdMember.steward("did:alice", "Alice"));
            assertTrue(checker.check("did:alice", "anything").allowed());
        }

        @Test
        void unknown_member_denied() {
            var checker = new PermissionChecker();
            assertFalse(checker.check("did:unknown", "room:enter").allowed());
        }

        @Test
        void member_checked_against_permissions() {
            var checker = new PermissionChecker();
            checker.register(HouseholdMember.member("did:bob", "Bob",
                Set.of(HouseholdMember.PERM_ROOM_ENTER)));

            assertTrue(checker.check("did:bob", HouseholdMember.PERM_ROOM_ENTER).allowed());
            assertFalse(checker.check("did:bob", HouseholdMember.PERM_AGENT_DELETE).allowed());
        }

        @Test
        void cannot_remove_last_steward() {
            var checker = new PermissionChecker();
            checker.register(HouseholdMember.steward("did:alice", "Alice"));
            assertFalse(checker.unregister("did:alice"));
        }

        @Test
        void can_remove_steward_if_another_exists() {
            var checker = new PermissionChecker();
            checker.register(HouseholdMember.steward("did:alice", "Alice"));
            checker.register(HouseholdMember.steward("did:bob", "Bob"));
            assertTrue(checker.unregister("did:alice"));
            assertEquals(1, checker.stewardCount());
        }

        @Test
        void non_steward_cannot_modify_steward() {
            var checker = new PermissionChecker();
            checker.register(HouseholdMember.steward("did:alice", "Alice"));
            checker.register(HouseholdMember.member("did:bob", "Bob",
                Set.of(HouseholdMember.PERM_MEMBER_MANAGE)));

            var result = checker.checkMemberAction("did:bob", "did:alice",
                HouseholdMember.PERM_MEMBER_MANAGE);
            assertFalse(result.allowed());
        }

        @Test
        void steward_can_modify_steward() {
            var checker = new PermissionChecker();
            checker.register(HouseholdMember.steward("did:alice", "Alice"));
            checker.register(HouseholdMember.steward("did:bob", "Bob"));

            var result = checker.checkMemberAction("did:alice", "did:bob",
                HouseholdMember.PERM_MEMBER_MANAGE);
            assertTrue(result.allowed());
        }

        @Test
        void promote_member() {
            var checker = new PermissionChecker();
            checker.register(HouseholdMember.steward("did:alice", "Alice"));
            checker.register(HouseholdMember.member("did:bob", "Bob", Set.of()));

            var result = checker.promote("did:alice", "did:bob");
            assertTrue(result.allowed());
            assertTrue(checker.getMember("did:bob").get().isSteward());
        }

        @Test
        void cannot_deactivate_last_steward() {
            var checker = new PermissionChecker();
            checker.register(HouseholdMember.steward("did:alice", "Alice"));

            var result = checker.deactivate("did:alice", "did:alice");
            assertFalse(result.allowed());
        }

        @Test
        void list_stewards() {
            var checker = new PermissionChecker();
            checker.register(HouseholdMember.steward("did:alice", "Alice"));
            checker.register(HouseholdMember.member("did:bob", "Bob", Set.of()));
            assertEquals(1, checker.stewards().size());
            assertEquals(2, checker.memberCount());
        }
    }

    @Nested
    class StewardAuditLogTests {

        @Test
        void log_action() {
            var log = new StewardAuditLog();
            var entry = log.log("did:alice", "Alice",
                StewardAuditLog.ActionType.AGENT_CREATE,
                "did:home-server", "Created agent Lain", true);
            assertEquals("did:alice", entry.actorDid());
            assertTrue(entry.approved());
            assertEquals(1, log.entryCount());
        }

        @Test
        void filter_by_actor() {
            var log = new StewardAuditLog();
            log.log("did:alice", "Alice", StewardAuditLog.ActionType.AGENT_CREATE,
                "a1", "d1", true);
            log.log("did:bob", "Bob", StewardAuditLog.ActionType.BUDGET_CHANGE,
                "a1", "d2", true);
            log.log("did:alice", "Alice", StewardAuditLog.ActionType.TRUST_CHANGE,
                "t1", "d3", true);

            assertEquals(2, log.byActor("did:alice", 10).size());
        }

        @Test
        void filter_denied() {
            var log = new StewardAuditLog();
            log.log("did:alice", "Alice", StewardAuditLog.ActionType.AGENT_DELETE,
                "a1", "denied", false);
            log.log("did:alice", "Alice", StewardAuditLog.ActionType.AGENT_CREATE,
                "a2", "approved", true);

            assertEquals(1, log.denied(10).size());
        }

        @Test
        void prunes_old_entries() {
            var log = new StewardAuditLog();
            log.setMaxEntries(5);
            for (int i = 0; i < 10; i++) {
                log.log("did:a", "A", StewardAuditLog.ActionType.BUDGET_CHANGE,
                    "t", "d" + i, true);
            }
            assertEquals(5, log.entryCount());
        }
    }
}
