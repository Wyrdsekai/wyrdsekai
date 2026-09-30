package org.wyrdsekai.server.ssh;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.sshd.client.SshClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.InviteService;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The installer's bootstrap invite is redeemed over SSH ({@code ssh steward@host}, the code as the
 * password). That founds the household, so it shows the recovery key once, as registering the
 * first account over HTTP does. Before 2026-09-28 this path made a steward with no recovery key.
 */
@Tag("integration")
class SshBootstrapRecoveryKeyTest {

    private static final Pattern RECOVERY_KEY = Pattern.compile("recovery key[^\\n]*\\n\\s*(\\S+)");

    private SshAdapter adapter;
    private ActorTestKit testKit;
    private String previousDataDir;

    @AfterEach
    void tearDown() {
        if (adapter != null) adapter.stop();
        if (testKit != null) testKit.shutdownTestKit();
        if (previousDataDir == null) System.clearProperty("wyrdsekai.dataDir");
        else System.setProperty("wyrdsekai.dataDir", previousDataDir);
    }

    @Test
    @DisplayName("redeeming the bootstrap invite over SSH shows a recovery key that works")
    void bootstrapOverSsh(@TempDir Path dir) throws Exception {
        previousDataDir = System.getProperty("wyrdsekai.dataDir");
        System.setProperty("wyrdsekai.dataDir", dir.toString());
        var url = SchemaInitializer.initialize(dir.resolve("world.db"));
        var auth = new AuthService(url);
        var invites = new InviteService(url);
        var bootstrap = invites.createBootstrapInvite("steward", 3600);

        int port;
        try (var probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = probe.getLocalPort(); }
        testKit = ActorTestKit.create();
        adapter = new SshAdapter();
        adapter.start(port, testKit.system(), auth, invites, null, null);

        var client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((s, addr, key) -> true);
        client.start();
        try (var session = client.connect("steward", "127.0.0.1", port).verify(Duration.ofSeconds(10)).getSession()) {
            session.addPasswordIdentity(bootstrap.code());
            session.auth().verify(Duration.ofSeconds(10));
            try (var channel = session.createShellChannel()) {
                var received = new ByteArrayOutputStream();
                channel.setOut(received);
                channel.setErr(new ByteArrayOutputStream());
                channel.open().verify(Duration.ofSeconds(10));
                var in = channel.getInvertedIn();
                in.write("password123\npassword123\n".getBytes(StandardCharsets.UTF_8));
                in.flush();

                String text = "";
                var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (System.nanoTime() < deadline) {
                    text = received.toString(StandardCharsets.UTF_8);
                    if (RECOVERY_KEY.matcher(text).find()) break;
                    Thread.sleep(100);
                }
                var m = RECOVERY_KEY.matcher(text);
                assertThat(m.find()).as(text).isTrue();
                assertThat(auth.verifyRecoveryKey(m.group(1))).isTrue();
            }
        } finally {
            client.stop();
        }
        assertThat(auth.findUserByUsername("steward").orElseThrow().role()).isEqualTo("steward");
    }
}
