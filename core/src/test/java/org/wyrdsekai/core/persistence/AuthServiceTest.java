package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.test.TestDb;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class AuthServiceTest {

    private AuthService service;

    @BeforeEach void setUp() {
        var jdbcUrl = TestDb.createInMemory();
        service = new AuthService(jdbcUrl);
    }

    @Test void register_and_login() {
        var reg = service.register("alice", "password123", "Alice");
        assertThat(reg).isPresent();
        assertThat(reg.get().token()).isNotBlank();

        var login = service.login("alice", "password123");
        assertThat(login).isPresent();
        assertThat(login.get().userId()).isEqualTo(reg.get().userId());
    }

    @Test void login_wrong_password() {
        service.register("alice", "password123", "Alice");
        var login = service.login("alice", "wrong");
        assertThat(login).isEmpty();
    }

    @Test void login_unknown_user() {
        var login = service.login("nonexistent", "password");
        assertThat(login).isEmpty();
    }

    @Test void validate_session() {
        var reg = service.register("alice", "password123", "Alice");
        assertThat(reg).isPresent();

        var user = service.validateSession(reg.get().token());
        assertThat(user).isPresent();
        assertThat(user.get().username()).isEqualTo("alice");
        assertThat(user.get().displayName()).isEqualTo("Alice");
    }

    @Test void validate_bogus_token() {
        assertThat(service.validateSession("bogus-token-123")).isEmpty();
    }

    @Test void register_duplicate_username_returns_empty() {
        var first = service.register("alice", "pass1", "Alice");
        assertThat(first).isPresent();

        var second = service.register("alice", "pass2", "Alice2");
        assertThat(second).isEmpty();
    }

    @Test void logout_invalidates_session() {
        var reg = service.register("alice", "password123", "Alice");
        assertThat(reg).isPresent();

        service.logout(reg.get().token());
        assertThat(service.validateSession(reg.get().token())).isEmpty();
    }

    @Test void countUsers() {
        service.register("alice", "pass1", null);
        service.register("bob", "pass2", null);
        assertThat(service.countUsers()).isEqualTo(2);
    }

    // ── Role-based tests ─────────────────────────────────────────────

    @Test void firstUserBecomesSteward() {
        var reg = service.register("alice", "pass1", "Alice");
        assertThat(reg).isPresent();

        var user = service.validateSession(reg.get().token());
        assertThat(user).isPresent();
        assertThat(user.get().role()).isEqualTo("steward");
    }

    @Test void secondUserIsMember() {
        var first = service.register("alice", "pass1", "Alice");
        assertThat(first).isPresent();

        var second = service.register("bob", "pass2", "Bob");
        assertThat(second).isPresent();

        var user = service.validateSession(second.get().token());
        assertThat(user).isPresent();
        assertThat(user.get().role()).isEqualTo("member");
    }

    @Test void stewardCanCreateUser() {
        var steward = service.register("alice", "pass1", "Alice");
        assertThat(steward).isPresent();
        var stewardId = steward.get().userId();

        var created = service.registerByAdmin(stewardId, "bob", "pass2", "Bob", "member");
        assertThat(created).isPresent();

        var bob = service.validateSession(created.get().token());
        assertThat(bob).isPresent();
        assertThat(bob.get().username()).isEqualTo("bob");
        assertThat(bob.get().role()).isEqualTo("member");
    }

    @Test void memberCannotCreateUser() {
        var steward = service.register("alice", "pass1", "Alice");
        assertThat(steward).isPresent();

        var member = service.register("bob", "pass2", "Bob");
        assertThat(member).isPresent();
        var memberId = member.get().userId();

        var result = service.registerByAdmin(memberId, "charlie", "pass3", "Charlie", "member");
        assertThat(result).isEmpty();
    }

    @Test void setRoleBySteward() {
        var steward = service.register("alice", "pass1", "Alice");
        assertThat(steward).isPresent();
        var stewardId = steward.get().userId();

        var member = service.register("bob", "pass2", "Bob");
        assertThat(member).isPresent();
        var memberId = member.get().userId();

        // Verify bob is member
        var bob = service.findUser(memberId);
        assertThat(bob).isPresent();
        assertThat(bob.get().role()).isEqualTo("member");

        // Steward promotes bob
        var result = service.setRole(stewardId, memberId, "steward");
        assertThat(result).isTrue();

        // Verify bob is now steward
        bob = service.findUser(memberId);
        assertThat(bob).isPresent();
        assertThat(bob.get().role()).isEqualTo("steward");
    }

    @Test void listUsersReturnsAll() {
        service.register("alice", "pass1", "Alice");
        service.register("bob", "pass2", "Bob");
        service.register("charlie", "pass3", "Charlie");

        var users = service.listUsers();
        assertThat(users).hasSize(3);
        assertThat(users).extracting(AuthService.User::username)
            .containsExactlyInAnyOrder("alice", "bob", "charlie");
        // First user should be steward
        var alice = users.stream().filter(u -> "alice".equals(u.username())).findFirst();
        assertThat(alice).isPresent();
        assertThat(alice.get().role()).isEqualTo("steward");
    }

    @Test void findUserByUsername() {
        service.register("alice", "pass1", "Alice");
        var found = service.findUserByUsername("alice");
        assertThat(found).isPresent();
        assertThat(found.get().displayName()).isEqualTo("Alice");
        assertThat(found.get().role()).isEqualTo("steward");

        assertThat(service.findUserByUsername("nonexistent")).isEmpty();
    }

    @Test void isFirstUser() {
        assertThat(service.isFirstUser()).isTrue();
        service.register("alice", "pass1", "Alice");
        assertThat(service.isFirstUser()).isFalse();
    }

    @Test void memberCannotSetRole() {
        var steward = service.register("alice", "pass1", "Alice");
        assertThat(steward).isPresent();

        var member = service.register("bob", "pass2", "Bob");
        assertThat(member).isPresent();
        var memberId = member.get().userId();

        // Member tries to set own role to steward — should fail
        var result = service.setRole(memberId, memberId, "steward");
        assertThat(result).isFalse();

        // Verify bob is still member
        var bob = service.findUser(memberId);
        assertThat(bob).isPresent();
        assertThat(bob.get().role()).isEqualTo("member");
    }

    @Test void registerByAdminWithStewardRole() {
        var steward = service.register("alice", "pass1", "Alice");
        assertThat(steward).isPresent();
        var stewardId = steward.get().userId();

        var created = service.registerByAdmin(stewardId, "bob", "pass2", "Bob", "steward");
        assertThat(created).isPresent();

        var bob = service.validateSession(created.get().token());
        assertThat(bob).isPresent();
        assertThat(bob.get().role()).isEqualTo("steward");
    }

    @Test void displayNameDefaultsToUsername() {
        var reg = service.register("alice", "pass1", null);
        assertThat(reg).isPresent();

        var user = service.validateSession(reg.get().token());
        assertThat(user).isPresent();
        assertThat(user.get().displayName()).isEqualTo("alice");
    }

    @Test void setRoleForNonexistentUserReturnsFalse() {
        var steward = service.register("alice", "pass1", "Alice");
        assertThat(steward).isPresent();

        var result = service.setRole(steward.get().userId(), "nonexistent-id", "steward");
        assertThat(result).isFalse();
    }

    // ── Wave 1: Household Security tests ─────────────────────────────

    @Test void openRegistration_allowedForFirstUser() {
        assertThat(service.isOpenRegistrationAllowed()).isTrue();
    }

    @Test void openRegistration_closesAfterFirstUser() {
        // open registration is now strictly tied
        // to "no users yet". Once any user exists, all subsequent accounts
        // require an invite. The previous "steward toggles open_registration"
        // path is removed.
        var reg = service.register("alice", "pass1", "Alice");
        assertThat(reg).isPresent();
        assertThat(service.isOpenRegistrationAllowed()).isFalse();
    }

    @Test void openRegistration_cannotBeReEnabledByConfig() {
        // F4: setting the config key has no effect. Door stays closed.
        var reg = service.register("alice", "pass1", "Alice");
        assertThat(reg).isPresent();
        var uid = reg.get().userId();
        service.setConfig(AuthService.CONFIG_OPEN_REGISTRATION, "true", uid);
        assertThat(service.isOpenRegistrationAllowed()).isFalse();
    }

    @Test void householdConfig_setAndGet() {
        var reg = service.register("alice", "pass1", "Alice");
        var uid = reg.orElseThrow().userId();
        service.setConfig("test.key", "test-value", uid);
        assertThat(service.getConfig("test.key")).isEqualTo("test-value");
    }

    @Test void householdConfig_missingKeyReturnsNull() {
        assertThat(service.getConfig("nonexistent")).isNull();
    }

    @Test void householdConfig_upsertOverwrites() {
        var reg = service.register("alice", "pass1", "Alice");
        var uid = reg.orElseThrow().userId();
        service.setConfig("key", "v1", uid);
        service.setConfig("key", "v2", uid);
        assertThat(service.getConfig("key")).isEqualTo("v2");
    }

    @Test void findSteward_returnsFirstSteward() {
        service.register("alice", "pass1", "Alice");
        service.register("bob", "pass2", "Bob");
        var steward = service.findSteward();
        assertThat(steward).isPresent();
        assertThat(steward.get().username()).isEqualTo("alice");
    }

    @Test void findSteward_emptyWhenNoUsers() {
        assertThat(service.findSteward()).isEmpty();
    }

    @Test void migrateToHouseholdSecurity_stewardExistsNoOp() {
        // Normal state: steward exists, migration is a no-op.
        // F4: we no longer write CONFIG_OPEN_REGISTRATION; the door is
        // derived from "users exist" automatically.
        service.register("alice", "pass1", "Alice");
        var migrated = service.migrateToHouseholdSecurity();
        assertThat(migrated).isFalse();
        // Door is closed because alice exists, not because of a config key.
        assertThat(service.isOpenRegistrationAllowed()).isFalse();
    }

    @Test void migrateToHouseholdSecurity_noUsersReturnsFalse() {
        assertThat(service.migrateToHouseholdSecurity()).isFalse();
    }

    @Test void removeUser_stewardCanRemoveMember() {
        var steward = service.register("alice", "pass1", "Alice");
        var member = service.register("bob", "pass2", "Bob");
        assertThat(steward).isPresent();
        assertThat(member).isPresent();

        var removed = service.removeUser(steward.get().userId(), member.get().userId());
        assertThat(removed).isTrue();
        assertThat(service.findUser(member.get().userId())).isEmpty();
    }

    @Test void removeUser_stewardCannotRemoveSelf() {
        var steward = service.register("alice", "pass1", "Alice");
        assertThat(steward).isPresent();
        assertThat(service.removeUser(steward.get().userId(), steward.get().userId())).isFalse();
    }

    @Test void removeUser_memberCannotRemoveAnyone() {
        var steward = service.register("alice", "pass1", "Alice");
        var member = service.register("bob", "pass2", "Bob");
        assertThat(steward).isPresent();
        assertThat(member).isPresent();
        assertThat(service.removeUser(member.get().userId(), steward.get().userId())).isFalse();
    }
}
