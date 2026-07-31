package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.test.TestDb;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-account SSH key binding — the fix for the pubkey impersonation hole
 * (a global key-only accept-list let any key log in as any account) and the
 * restart-required-to-add-a-key bug (keys loaded once at sshd start).
 */
@Tag("integration")
class AuthServiceSshKeyTest {

    private AuthService service;
    private String stewardId;
    private String memberId;

    // Distinct, well-formed OpenSSH key lines (type + base64, no comment).
    private static final String STEWARD_KEY =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIEXAMPLEKEY0AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String MEMBER_KEY =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIEXAMPLEKEY1AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @BeforeEach void setUp() {
        service = new AuthService(TestDb.createInMemory());
        stewardId = service.register("steward", "password123", "Steward").orElseThrow().userId();
        memberId  = service.register("member", "password123", "Member", "member").orElseThrow().userId();
    }

    @Test void key_resolves_to_its_owning_account() {
        assertThat(service.addSshKey(stewardId, STEWARD_KEY, "steward@bootstrap")).isTrue();
        var owner = service.findUserBySshKey(STEWARD_KEY);
        assertThat(owner).isPresent();
        assertThat(owner.get().id()).isEqualTo(stewardId);
        assertThat(owner.get().username()).isEqualTo("steward");
    }

    @Test void member_key_does_NOT_resolve_to_steward() {
        // THE impersonation fix: a member's key is bound only to the member.
        service.addSshKey(memberId, MEMBER_KEY, "member@invite");
        var owner = service.findUserBySshKey(MEMBER_KEY);
        assertThat(owner).isPresent();
        assertThat(owner.get().id()).isEqualTo(memberId);
        assertThat(owner.get().role()).isEqualTo("member");
        // The member's key resolves to NOBODY as steward — there is no path by
        // which MEMBER_KEY yields the steward account.
        assertThat(owner.get().id()).isNotEqualTo(stewardId);
    }

    @Test void unbound_key_resolves_to_no_one() {
        assertThat(service.findUserBySshKey("ssh-ed25519 AAAAunknownKEYnotBound")).isEmpty();
        assertThat(service.findUserBySshKey(null)).isEmpty();
        assertThat(service.findUserBySshKey("")).isEmpty();
    }

    @Test void newly_added_key_is_resolvable_immediately_no_restart() {
        // The resolver is a live query — a key added now is found now, without
        // any reload/restart (the old model captured keys once at sshd start).
        assertThat(service.findUserBySshKey(MEMBER_KEY)).isEmpty();
        service.addSshKey(memberId, MEMBER_KEY, "added-live");
        assertThat(service.findUserBySshKey(MEMBER_KEY)).isPresent();
    }

    @Test void adding_same_key_is_idempotent() {
        assertThat(service.addSshKey(stewardId, STEWARD_KEY, "first")).isTrue();
        assertThat(service.addSshKey(stewardId, STEWARD_KEY, "again")).isFalse();
    }

    @Test void whitespace_around_key_is_normalized() {
        service.addSshKey(stewardId, STEWARD_KEY, "c");
        assertThat(service.findUserBySshKey("  " + STEWARD_KEY + "  ")).isPresent();
    }

    // ─── #17 (2026-07-19 OSS hardening) — key-squat DoS ─────────────────────

    @Test void squatting_a_key_does_not_block_the_real_owner() {
        // Attacker adds the victim's (public) key to THEIR OWN account first.
        assertThat(service.addSshKey(memberId, STEWARD_KEY, "squatter")).isTrue();
        // The real owner can STILL bind their own key (composite PK). Previously
        // the global key_line PK made this insert-ignore silently no-op.
        assertThat(service.addSshKey(stewardId, STEWARD_KEY, "real-owner")).isTrue();
        // Both accounts now carry the row; the connecting username disambiguates.
        var owners = service.findUsersBySshKey(STEWARD_KEY);
        assertThat(owners).extracting(AuthService.User::id)
            .containsExactlyInAnyOrder(stewardId, memberId);
    }

    @Test void findUsersBySshKey_returns_all_owners() {
        service.addSshKey(stewardId, STEWARD_KEY, "s");
        assertThat(service.findUsersBySshKey(STEWARD_KEY)).hasSize(1);
        service.addSshKey(memberId, STEWARD_KEY, "m");
        assertThat(service.findUsersBySshKey(STEWARD_KEY)).hasSize(2);
    }
}
