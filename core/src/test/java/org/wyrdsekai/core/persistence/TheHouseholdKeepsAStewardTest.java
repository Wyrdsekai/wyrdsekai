package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.test.TestDb;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The household keeps at least one steward. The guard is in {@link AuthService}, so it holds for
 * every surface that changes a role or removes an account: the roster ledger in the Study (web,
 * SSH, telnet), the HTTP routes, and account replication.
 */
@Tag("integration")
class TheHouseholdKeepsAStewardTest {

    private AuthService auth;
    private String ann;
    private String max;

    @BeforeEach
    void setUp() {
        auth = new AuthService(TestDb.createInMemory());
        ann = auth.register("ann", "password123", "Ann").orElseThrow().userId();   // first: steward
        max = auth.register("max", "password123", "Max").orElseThrow().userId();   // member
    }

    @Test
    @DisplayName("the only steward cannot demote themself")
    void lastStewardCannotBeDemoted() {
        assertThat(auth.changeRole(ann, ann, "member")).isEqualTo(AuthService.RoleChange.LAST_STEWARD);
        assertThat(auth.setRole(ann, ann, "guest")).isFalse();
        assertThat(auth.findUser(ann).orElseThrow().role()).isEqualTo("steward");
    }

    @Test
    @DisplayName("with two stewards one may step down; the one left cannot")
    void secondStewardMayStepDown() {
        assertThat(auth.changeRole(ann, max, "steward")).isEqualTo(AuthService.RoleChange.CHANGED);
        assertThat(auth.changeRole(max, ann, "member")).isEqualTo(AuthService.RoleChange.CHANGED);
        assertThat(auth.changeRole(max, max, "member")).isEqualTo(AuthService.RoleChange.LAST_STEWARD);
        assertThat(auth.listUsers().stream().filter(u -> "steward".equals(u.role())).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("members cannot change roles, and unknown roles are refused")
    void onlyStewardsAndKnownRoles() {
        assertThat(auth.changeRole(max, ann, "member")).isEqualTo(AuthService.RoleChange.NOT_STEWARD);
        assertThat(auth.changeRole(ann, max, "overlord")).isEqualTo(AuthService.RoleChange.UNKNOWN_ROLE);
        assertThat(auth.changeRole(ann, "nobody", "member")).isEqualTo(AuthService.RoleChange.NOT_FOUND);
    }

    @Test
    @DisplayName("account replication never removes the last steward")
    void replicationCannotRemoveLastSteward() {
        assertThat(auth.removeUserDirect(ann)).isFalse();
        assertThat(auth.findUser(ann)).isPresent();
        assertThat(auth.removeUserDirect(max)).isTrue();
        assertThat(auth.findUser(max)).isEmpty();
    }

    @Test
    @DisplayName("a steward cannot remove themself, and a member cannot remove anyone")
    void removal() {
        assertThat(auth.removeUser(ann, ann)).isFalse();
        assertThat(auth.removeUser(max, ann)).isFalse();
        assertThat(auth.removeUser(ann, max)).isTrue();
        assertThat(auth.findUser(ann)).isPresent();
    }

    @Test
    @DisplayName("two stewards demoting each other at the same moment leave one steward")
    void simultaneousDemotions(@TempDir Path dir) throws Exception {
        for (int round = 0; round < 10; round++) {
            var svc = new AuthService(SchemaInitializer.initialize(dir.resolve("demote-" + round + ".db")));
            var a = svc.register("a", "password123", null, "steward").orElseThrow().userId();
            var b = svc.register("b", "password123", null, "steward").orElseThrow().userId();
            race(() -> svc.changeRole(a, b, "member"), () -> svc.changeRole(b, a, "member"));
            assertThat(svc.listUsers().stream().filter(u -> "steward".equals(u.role())).count())
                .as("round %d", round).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("two stewards removing each other at the same moment leave one steward")
    void simultaneousRemovals(@TempDir Path dir) throws Exception {
        for (int round = 0; round < 10; round++) {
            var svc = new AuthService(SchemaInitializer.initialize(dir.resolve("remove-" + round + ".db")));
            var a = svc.register("a", "password123", null, "steward").orElseThrow().userId();
            var b = svc.register("b", "password123", null, "steward").orElseThrow().userId();
            race(() -> svc.removeUser(a, b), () -> svc.removeUser(b, a));
            var users = svc.listUsers();
            assertThat(users).as("round %d", round).hasSize(1);
            assertThat(users.get(0).role()).isEqualTo("steward");
        }
    }

    private static void race(Callable<?> one, Callable<?> two) throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var f1 = pool.submit(() -> { start.await(); return one.call(); });
            var f2 = pool.submit(() -> { start.await(); return two.call(); });
            start.countDown();
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
