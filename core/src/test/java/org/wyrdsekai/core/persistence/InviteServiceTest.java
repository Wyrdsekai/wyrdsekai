package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.test.TestDb;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class InviteServiceTest {

    private InviteService inviteService;
    private AuthService authService;
    private String stewardId;

    @BeforeEach void setUp() {
        var jdbcUrl = TestDb.createInMemory();
        inviteService = new InviteService(jdbcUrl);
        authService = new AuthService(jdbcUrl);
        // Create steward
        var reg = authService.register("steward", "pass123", "Steward");
        stewardId = reg.orElseThrow().userId();
    }

    @Test void createInvite_generatesPassphrase() {
        var invite = inviteService.createInvite("Alice", "member", stewardId);
        assertThat(invite.id()).isNotBlank();
        assertThat(invite.code()).isNotBlank();
        assertThat(invite.code().split("\\s+")).hasSize(6); // 6-word passphrase
        assertThat(invite.intendedName()).isEqualTo("Alice");
        assertThat(invite.role()).isEqualTo("member");
        assertThat(invite.isValid()).isTrue();
        assertThat(invite.isConsumed()).isFalse();
        assertThat(invite.isExpired()).isFalse();
    }

    @Test void redeemInvite_consumesCode() {
        var invite = inviteService.createInvite("Alice", "member", stewardId);
        var code = invite.code();

        // Create a user account to redeem with
        var aliceReg = authService.register("alice", "pass123", "Alice", "member");
        var aliceId = aliceReg.orElseThrow().userId();

        var redeemed = inviteService.redeemInvite(code, aliceId);
        assertThat(redeemed).isPresent();
        assertThat(redeemed.get().isConsumed()).isTrue();
        assertThat(redeemed.get().consumedBy()).isEqualTo(aliceId);
    }

    @Test void redeemInvite_codeIsOneTimeUse() {
        var invite = inviteService.createInvite("Alice", "member", stewardId);
        var code = invite.code();

        // First redeem succeeds
        var first = inviteService.redeemInvite(code, "user-1");
        assertThat(first).isPresent();

        // Second redeem fails (code consumed)
        var second = inviteService.redeemInvite(code, "user-2");
        assertThat(second).isEmpty();
    }

    @Test void redeemInvite_invalidCodeFails() {
        var result = inviteService.redeemInvite("bogus code that does not exist", "user-1");
        assertThat(result).isEmpty();
    }

    @Test void redeemInvite_expiredCodeFails() {
        // Create with 0-second expiry (already expired)
        var invite = inviteService.createInvite("Alice", "member", stewardId, 0);
        var result = inviteService.redeemInvite(invite.code(), "user-1");
        assertThat(result).isEmpty();
    }

    @Test void listInvites_returnsAll() {
        inviteService.createInvite("Alice", "member", stewardId);
        inviteService.createInvite("Bob", "guest", stewardId);

        var invites = inviteService.listInvites();
        assertThat(invites).hasSize(2);
    }

    @Test void listPendingInvites_excludesConsumedAndExpired() {
        var valid = inviteService.createInvite("Alice", "member", stewardId);
        inviteService.createInvite("Bob", "member", stewardId, 0); // expired
        var consumed = inviteService.createInvite("Charlie", "member", stewardId);
        inviteService.redeemInvite(consumed.code(), "user-1");

        var pending = inviteService.listPendingInvites();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).intendedName()).isEqualTo("Alice");
    }

    @Test void revokeInvite_deletesPending() {
        var invite = inviteService.createInvite("Alice", "member", stewardId);
        assertThat(inviteService.revokeInvite(invite.id())).isTrue();
        assertThat(inviteService.listPendingInvites()).isEmpty();
    }

    @Test void revokeInvite_cannotRevokeConsumed() {
        var invite = inviteService.createInvite("Alice", "member", stewardId);
        inviteService.redeemInvite(invite.code(), "user-1");
        assertThat(inviteService.revokeInvite(invite.id())).isFalse();
    }

    @Test void purgeExpired_cleansUp() {
        inviteService.createInvite("Alice", "member", stewardId, 0); // expired
        inviteService.createInvite("Bob", "member", stewardId); // valid
        var purged = inviteService.purgeExpired();
        assertThat(purged).isEqualTo(1);
        assertThat(inviteService.listInvites()).hasSize(1);
    }

    @Test void generatePassphrase_uniqueAndSixWords() {
        var p1 = InviteService.generatePassphrase();
        var p2 = InviteService.generatePassphrase();
        assertThat(p1.split("\\s+")).hasSize(6);
        assertThat(p2.split("\\s+")).hasSize(6);
        assertThat(p1).isNotEqualTo(p2); // vanishingly unlikely to collide
    }

    @Test void createInvite_guestRole() {
        var invite = inviteService.createInvite("Guest", "guest", stewardId);
        assertThat(invite.role()).isEqualTo("guest");
    }

    @Test void createInvite_defaultsToMember() {
        var invite = inviteService.createInvite("Someone", null, stewardId);
        assertThat(invite.role()).isEqualTo("member");
    }

    // ─── #4 (2026-07-19 OSS hardening) — atomic claim-before-create ─────────

    @Test void claimInvite_consumesAndReturnsRole() {
        var invite = inviteService.createInvite("Alice", "guest", stewardId);
        var claimed = inviteService.claimInvite(invite.code(), "claim:tok-1");
        assertThat(claimed).isPresent();
        assertThat(claimed.get().role()).isEqualTo("guest");
        // Now pending list is empty (consumed by the claim).
        assertThat(inviteService.listPendingInvites()).isEmpty();
        // A second claim finds nothing.
        assertThat(inviteService.claimInvite(invite.code(), "claim:tok-2")).isEmpty();
    }

    @Test void releaseClaim_restoresInviteToPending() {
        var invite = inviteService.createInvite("Alice", "member", stewardId);
        assertThat(inviteService.claimInvite(invite.code(), "claim:tok-1")).isPresent();
        inviteService.releaseClaim("claim:tok-1");
        // Back to pending — usable again (register-failed path).
        assertThat(inviteService.listPendingInvites()).hasSize(1);
        assertThat(inviteService.claimInvite(invite.code(), "claim:tok-3")).isPresent();
    }

    @Test void rebindClaim_setsConsumedByToUserId() {
        var invite = inviteService.createInvite("Alice", "member", stewardId);
        inviteService.claimInvite(invite.code(), "claim:tok-1");
        inviteService.rebindClaim("claim:tok-1", "user-42");
        var listed = inviteService.listInvites().stream()
            .filter(i -> i.id().equals(invite.id())).findFirst().orElseThrow();
        assertThat(listed.consumedBy()).isEqualTo("user-42");
    }

    @Test void claimInvite_isAtomicUnderConcurrency() throws Exception {
        var invite = inviteService.createInvite("Alice", "member", stewardId);
        var code = invite.code();
        int threads = 16;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var winners = new AtomicInteger();
        var futures = new ArrayList<Future<?>>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    // Retry only transient DB-lock errors (shared-cache SQLite may
                    // throw SQLITE_BUSY); an "already consumed" is a clean empty,
                    // not an exception, so retries never mint a second winner.
                    for (int attempt = 0; attempt < 50; attempt++) {
                        try {
                            if (inviteService.claimInvite(code, "claim:race-" + n).isPresent()) {
                                winners.incrementAndGet();
                            }
                            break;
                        } catch (RuntimeException retryable) {
                            Thread.sleep(5);
                        }
                    }
                } catch (Exception ignored) {}
            }));
        }
        start.countDown();
        for (var f : futures) f.get();
        pool.shutdown();
        // Exactly one caller may win the atomic claim.
        assertThat(winners.get()).isEqualTo(1);
    }

    @Test void redeemInvite_caseInsensitiveCode() {
        var invite = inviteService.createInvite("Alice", "member", stewardId);
        var code = invite.code().toUpperCase(); // codes are stored lowercase
        var result = inviteService.redeemInvite(code, "user-1");
        assertThat(result).isPresent();
    }
}
