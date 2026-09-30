package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.test.TestDb;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first account is the steward, and there is exactly one first account: the claim, the check
 * that no account exists and the insert commit together. Every way the household is founded (open
 * registration on a fresh zone, the bootstrap invite) brings the recovery key.
 */
@Tag("integration")
class OnlyOneFirstStewardTest {

    @Test
    @DisplayName("of eight first registrations at the same moment, exactly one becomes steward")
    void simultaneousFirstRegistrations(@TempDir Path dir) throws Exception {
        var auth = new AuthService(SchemaInitializer.initialize(dir.resolve("world.db")));
        int n = 8;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(n);
        var results = new ArrayList<Future<AuthService.Registration>>();
        try {
            for (int i = 0; i < n; i++) {
                var name = "founder" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    return auth.registerFirstSteward(name, "password123", null);
                }));
            }
            start.countDown();
            var created = new ArrayList<AuthService.Registration.Created>();
            int closed = 0;
            for (var f : results) {
                switch (f.get(60, TimeUnit.SECONDS)) {
                    case AuthService.Registration.Created c -> created.add(c);
                    case AuthService.Registration.Closed _ -> closed++;
                    case AuthService.Registration.UsernameTaken _ -> { }
                }
            }
            assertThat(created).hasSize(1);
            assertThat(closed).isEqualTo(n - 1);
        } finally {
            pool.shutdownNow();
        }
        var users = auth.listUsers();
        assertThat(users).hasSize(1);
        assertThat(users.get(0).role()).isEqualTo("steward");
        assertThat(auth.isOpenRegistrationAllowed()).isFalse();
    }

    @Test
    @DisplayName("the founder gets a recovery key that works, and open registration closes")
    void founderGetsRecoveryKey() {
        var auth = new AuthService(TestDb.createInMemory());
        var result = auth.registerFirstSteward("ann", "password123", "Ann");
        assertThat(result).isInstanceOf(AuthService.Registration.Created.class);
        var key = ((AuthService.Registration.Created) result).recoveryKey();
        assertThat(key).isNotBlank();
        assertThat(key.split("-")).hasSize(8);
        assertThat(auth.verifyRecoveryKey(key)).isTrue();
        assertThat(auth.getConfig(AuthService.CONFIG_OPEN_REGISTRATION)).isEqualTo("false");
        assertThat(auth.findUserByUsername("ann").orElseThrow().role()).isEqualTo("steward");
        assertThat(auth.recoverSteward(key, "new-password")).isTrue();
        assertThat(auth.login("ann", "new-password")).isPresent();
    }

    @Test
    @DisplayName("once any account exists, the first-steward path is closed and leaves nothing behind")
    void closedOnceAnAccountExists() {
        var auth = new AuthService(TestDb.createInMemory());
        auth.register("ann", "password123", "Ann");   // an install from before the claim existed
        assertThat(auth.registerFirstSteward("eve", "password123", null))
            .isInstanceOf(AuthService.Registration.Closed.class);
        assertThat(auth.findUserByUsername("eve")).isEmpty();
        assertThat(auth.getConfig(AuthService.CONFIG_FOUNDED_BY)).isNull();
        assertThat(auth.getConfig(AuthService.CONFIG_RECOVERY_KEY_HASH)).isNull();
    }

    @Test
    @DisplayName("a claim left behind by accounts deleted by hand does not lock the household out")
    void staleClaimIsCleared() throws Exception {
        var url = TestDb.createInMemory();
        var auth = new AuthService(url);
        assertThat(auth.registerFirstSteward("ann", "password123", null))
            .isInstanceOf(AuthService.Registration.Created.class);
        try (var conn = java.sql.DriverManager.getConnection(url); var st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM sessions");
            st.executeUpdate("DELETE FROM users");
        }
        assertThat(auth.registerFirstSteward("bob", "password123", null))
            .isInstanceOf(AuthService.Registration.Created.class);
        assertThat(auth.findUserByUsername("bob").orElseThrow().role()).isEqualTo("steward");
    }

    @Test
    @DisplayName("redeeming the bootstrap invite founds the household with a recovery key; other invites do not")
    void bootstrapInviteBringsRecoveryKey() {
        var url = TestDb.createInMemory();
        var auth = new AuthService(url);
        var invites = new InviteService(url);
        var bootstrap = invites.createBootstrapInvite("steward", 3600);
        assertThat(bootstrap.isBootstrap()).isTrue();
        var claimed = invites.claimInvite(bootstrap.code(), "claim:1").orElseThrow();

        var result = auth.registerByInvite(claimed, "steward", "password123", null);
        assertThat(result).isInstanceOf(AuthService.Registration.Created.class);
        var key = ((AuthService.Registration.Created) result).recoveryKey();
        assertThat(auth.verifyRecoveryKey(key)).isTrue();
        var steward = auth.findUserByUsername("steward").orElseThrow();
        assertThat(steward.role()).isEqualTo("steward");

        var member = invites.createInvite("kaz", "member", steward.id());
        assertThat(member.isBootstrap()).isFalse();
        var memberClaim = invites.claimInvite(member.code(), "claim:2").orElseThrow();
        var joined = auth.registerByInvite(memberClaim, "kaz", "password123", null);
        assertThat(joined).isInstanceOf(AuthService.Registration.Created.class);
        assertThat(((AuthService.Registration.Created) joined).recoveryKey()).isNull();
        assertThat(auth.verifyRecoveryKey(key)).as("the household's key is unchanged").isTrue();
    }

    @Test
    @DisplayName("a bootstrap invite redeemed after someone else founded the household creates nothing")
    void bootstrapInviteAfterFounding() {
        var url = TestDb.createInMemory();
        var auth = new AuthService(url);
        var invites = new InviteService(url);
        var bootstrap = invites.createBootstrapInvite("steward", 3600);
        assertThat(auth.registerFirstSteward("ann", "password123", null))
            .isInstanceOf(AuthService.Registration.Created.class);
        var claimed = invites.claimInvite(bootstrap.code(), "claim:1").orElseThrow();
        assertThat(auth.registerByInvite(claimed, "steward", "password123", null))
            .isInstanceOf(AuthService.Registration.Closed.class);
        assertThat(auth.findUserByUsername("steward")).isEmpty();
        assertThat(auth.listUsers()).hasSize(1);
    }
}
