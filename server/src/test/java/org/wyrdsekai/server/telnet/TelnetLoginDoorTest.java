package org.wyrdsekai.server.telnet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.InviteService;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.security.LoginRateLimiter;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The telnet login prompt, driven over a real loopback socket: failed logins count per source
 * address and per account across connections, one invite code makes one account however many
 * sessions redeem it at once, and founding the household shows the recovery key.
 */
@Tag("integration")
class TelnetLoginDoorTest {

    private static final Pattern RECOVERY_KEY = Pattern.compile("recovery key[^\\n]*\\n\\s*(\\S+)");

    private AuthService auth;
    private InviteService invites;

    private record Run(boolean loggedIn, String output) {}

    @BeforeEach
    void setUp(@TempDir Path dir) {
        LoginRateLimiter.shared().clear();
        var url = SchemaInitializer.initialize(dir.resolve("world.db"));
        auth = new AuthService(url);
        invites = new InviteService(url);
    }

    @AfterEach
    void tearDown() {
        LoginRateLimiter.shared().clear();
    }

    @Test
    @DisplayName("five failures lock the account and the address, and reconnecting does not reset them")
    void lockoutSurvivesReconnect() throws Exception {
        auth.registerFirstSteward("ann", "password123", null);
        var steward = auth.findUserByUsername("ann").orElseThrow();
        auth.registerByAdmin(steward.id(), "bob", "password456", null, "member");

        var first = telnet("connect ann wrong1", "connect ann wrong2", "connect ann wrong3",
            "connect ann wrong4", "connect ann wrong5");
        assertThat(first.loggedIn()).isFalse();
        assertThat(first.output()).contains("Too many attempts");

        var second = telnet("connect ann password123");
        assertThat(second.loggedIn()).as("a new connection is still locked out").isFalse();
        assertThat(second.output()).contains("Too many failed logins");

        var other = telnet("connect bob password456");
        assertThat(other.loggedIn()).as("the address is locked for every account").isFalse();
    }

    @Test
    @DisplayName("a wrong password and then the right one logs in")
    void goodLoginWorks() throws Exception {
        auth.registerFirstSteward("ann", "password123", null);
        var run = telnet("connect ann wrong", "connect ann password123");
        assertThat(run.loggedIn()).isTrue();
        assertThat(run.output()).contains("Welcome back");
    }

    @Test
    @DisplayName("eight sessions redeeming one invite at the same moment make one account")
    void inviteIsClaimedOnce() throws Exception {
        auth.registerFirstSteward("ann", "password123", null);
        var steward = auth.findUserByUsername("ann").orElseThrow();
        var invite = invites.createInvite("guest", "member", steward.id());
        int n = 8;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(n);
        var runs = new ArrayList<Future<Run>>();
        try {
            for (int i = 0; i < n; i++) {
                var line = "redeem " + invite.code() + " joiner" + i + " password123";
                runs.add(pool.submit(() -> {
                    start.await();
                    return telnet(line);
                }));
            }
            start.countDown();
            int in = 0;
            for (var f : runs) if (f.get(60, TimeUnit.SECONDS).loggedIn()) in++;
            assertThat(in).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(auth.listUsers()).hasSize(2);
        assertThat(invites.listPendingInvites()).isEmpty();
    }

    @Test
    @DisplayName("creating the first account over telnet shows a recovery key that works")
    void createShowsRecoveryKey() throws Exception {
        var run = telnet("create ann password123");
        assertThat(run.loggedIn()).isTrue();
        assertThat(run.output()).contains("recovery key");
        var m = RECOVERY_KEY.matcher(run.output());
        assertThat(m.find()).as(run.output()).isTrue();
        assertThat(auth.verifyRecoveryKey(m.group(1))).isTrue();

        var again = telnet("create eve password123");
        assertThat(again.loggedIn()).isFalse();
        assertThat(auth.findUserByUsername("eve")).isEmpty();
    }

    @Test
    @DisplayName("redeeming the bootstrap invite over telnet shows the recovery key")
    void bootstrapRedeemShowsRecoveryKey() throws Exception {
        var bootstrap = invites.createBootstrapInvite("steward", 3600);
        var run = telnet("redeem " + bootstrap.code() + " steward password123");
        assertThat(run.loggedIn()).isTrue();
        var m = RECOVERY_KEY.matcher(run.output());
        assertThat(m.find()).as(run.output()).isTrue();
        assertThat(auth.verifyRecoveryKey(m.group(1))).isTrue();
        assertThat(auth.findUserByUsername("steward").orElseThrow().role()).isEqualTo("steward");
    }

    /** One telnet connection from loopback: send the lines, then end input; returns whether it logged in. */
    private Run telnet(String... lines) throws Exception {
        var loopback = InetAddress.getLoopbackAddress();
        try (var listener = new ServerSocket(0, 8, loopback);
             var client = new Socket(loopback, listener.getLocalPort());
             var server = listener.accept();
             var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            var session = new TelnetSession(server, null, auth, invites, null, null);
            var result = exec.submit(() ->
                session.authenticate(server.getInputStream(), server.getOutputStream(), new boolean[1]));
            var out = client.getOutputStream();
            for (var line : lines) out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            client.shutdownOutput();
            boolean loggedIn = result.get(60, TimeUnit.SECONDS);
            server.shutdownOutput();
            var text = new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Run(loggedIn, text);
        }
    }
}
