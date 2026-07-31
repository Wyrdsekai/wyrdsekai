package org.wyrdsekai.between;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * integration test: spin up a real {@code nats-server}
 * configured for NKey auth, accept the local NodeIdentity's pubkey in the users
 * list, and verify {@link RelayBridge} (and a plain {@link Nats#connect})
 * authenticate end-to-end with the NKey AuthHandler.
 *
 * <p>This is the full round-trip the relay performs in production. If
 * {@link NodeIdentity#nkeyAuthHandler()} ever broke (e.g. wrong sign algorithm,
 * wrong getID format), this test would fail in exactly the same way as a live
 * relay would reject the node — caught at PR-time instead of by Authorization
 * Violation in prod logs.</p>
 *
 * <p>Tagged "integration" because it spawns a real binary; skipped when
 * {@code nats-server} isn't on PATH.</p>
 */
@Tag("integration")
@EnabledIf("isNatsServerAvailable")
@Tag("needs-nats")
final class RelayBridgeNkeyAuthIT {

    static boolean isNatsServerAvailable() {
        return NatsServerManager.isAvailable("nats-server");
    }

    private Process natsProcess;
    private int natsPort;
    private Path identityFile;
    private NodeIdentity identity;

    @BeforeEach
    void start_nats_with_nkey_auth(@TempDir Path tmp) throws Exception {
        identityFile = tmp.resolve("node-identity.json");
        identity = NodeIdentity.loadOrGenerate(identityFile);

        natsPort = freePort();
        var pubkey = identity.nkeyPublicKey();

        // Minimal NATS config: NKey-only auth for one user (this NodeIdentity).
        var conf = """
            port: %d
            http_port: 0
            authorization {
                users = [
                    { nkey: "%s",
                      permissions: {
                        publish:   { allow: ["between.>", "_INBOX.>"] }
                        subscribe: { allow: ["between.>", "_INBOX.>"] }
                      }
                    }
                ]
            }
            """.formatted(natsPort, pubkey);
        var confFile = tmp.resolve("nats.conf");
        Files.writeString(confFile, conf, StandardCharsets.UTF_8);

        natsProcess = new ProcessBuilder("nats-server", "-c", confFile.toString())
            .redirectErrorStream(true)
            .start();
        // Drain stdout so the buffer doesn't fill and block the subprocess.
        var drain = new Thread(() -> {
            try (var in = natsProcess.getInputStream()) {
                in.transferTo(System.out);
            } catch (IOException ignored) {}
        }, "nats-stdout-drain");
        drain.setDaemon(true);
        drain.start();

        waitForNatsHealthy(natsPort, Duration.ofSeconds(5));
    }

    @AfterEach
    void stop_nats() throws Exception {
        if (natsProcess != null) {
            natsProcess.destroy();
            if (!natsProcess.waitFor(5, TimeUnit.SECONDS)) {
                natsProcess.destroyForcibly();
            }
        }
    }

    @Test
    void node_identity_nkey_auth_handler_connects_and_round_trips_a_message() throws Exception {
        var url = "nats://127.0.0.1:" + natsPort;
        var opts = new Options.Builder()
            .server(url)
            .authHandler(identity.nkeyAuthHandler())
            .connectionTimeout(Duration.ofSeconds(3))
            .build();

        try (Connection conn = Nats.connect(opts)) {
            assertThat(conn.getStatus()).isEqualTo(Connection.Status.CONNECTED);

            var queue = new LinkedBlockingQueue<String>();
            var dispatcher = conn.createDispatcher(msg ->
                queue.offer(new String(msg.getData(), StandardCharsets.UTF_8)));
            dispatcher.subscribe("between.test.echo");
            // Give the subscription time to register on the server.
            Thread.sleep(150);

            conn.publish("between.test.echo", "hello-nkey".getBytes(StandardCharsets.UTF_8));
            var received = queue.poll(3, TimeUnit.SECONDS);
            assertThat(received).isEqualTo("hello-nkey");
        }
    }

    @Test
    void relay_bridge_uses_nkey_when_node_identity_provided() throws Exception {
        // Two RelayBridge endpoints on the same NATS to validate the dispatcher
        // wires up — one as the "local" listener, the other as the "relay".
        // NB: in production the local NATS and relay NATS are distinct servers;
        // the bridge's same-server-collapse safety detects this and skips
        // forwarding (exercised by the urlsResolveSame check). We assert the
        // bridge starts cleanly and the underlying relay connection is live.
        var url = "nats://127.0.0.1:" + natsPort;
        try (var bridge = new RelayBridge(
                url, url, "test-zone", identity.nodeId(),
                /*authUser=*/null, /*authPassword=*/null,
                /*nodeIdentity=*/identity)) {
            bridge.start();
            assertThat(bridge.isConnected())
                .as("RelayBridge with NodeIdentity should auth via NKey and connect")
                .isTrue();
        }
    }

    @Test
    void relay_session_transport_uses_nkey_when_node_identity_provided() throws Exception {
        // Mirror of relay_bridge_uses_nkey_when_node_identity_provided() for the
        // session transport — ensures the NKey path stays wired across BOTH
        // relay-side connections (RelayBridge for federation/between-forwarding,
        // RelaySessionTransport for inference + WS proxy). Without this, a
        // node could federate but NOT proxy sessions on an NKey-only relay.
        var url = "nats://127.0.0.1:" + natsPort;
        var transport = RelaySessionTransport.connect(url, identity, "wyrd-session-test");
        try {
            assertThat(transport)
                .as("connect(url, NodeIdentity, name) must return a non-null transport")
                .isNotNull();
            assertThat(transport.isConnected())
                .as("session transport with NKey identity should connect")
                .isTrue();

            // Round-trip: publish + subscribe through the transport interface
            // (not the bare connection) to confirm both sides work over NKey.
            var queue = new LinkedBlockingQueue<String>();
            var dispatcher = transport.subscribe("between.session.echo",
                bytes -> queue.offer(new String(bytes, StandardCharsets.UTF_8)));
            assertThat(dispatcher).as("subscribe() returned").isNotNull();
            Thread.sleep(150);
            transport.publish("between.session.echo",
                "session-nkey-hello".getBytes(StandardCharsets.UTF_8));

            var received = queue.poll(3, TimeUnit.SECONDS);
            assertThat(received).isEqualTo("session-nkey-hello");
        } finally {
            if (transport != null) transport.close();
        }
    }

    @Test
    void relay_session_transport_legacy_password_overload_still_compiles_and_runs() throws Exception {
        // Migration-window guard: callers on the OLD signature
        // (relayUrl, user, password, name) must keep compiling and running.
        // We can't easily authenticate against an NKey-only NATS with a
        // password (it'll fail), but we DO assert the call returns null or
        // disconnected — i.e. doesn't crash on missing identity argument.
        var url = "nats://127.0.0.1:" + natsPort;
        var transport = RelaySessionTransport.connect(
            url, "legacy-user", "legacy-password", "wyrd-session-legacy");
        // NKey-only NATS rejects password — connect() returns null, doesn't throw.
        if (transport != null) {
            assertThat(transport.isConnected()).isFalse();
            transport.close();
        }
    }

    @Test
    void relay_bridge_falls_back_to_password_auth_when_no_node_identity() throws Exception {
        // The NATS we started only accepts NKey, so a password attempt MUST fail.
        // We use this to confirm RelayBridge actually picks the legacy path when
        // nodeIdentity is null (i.e. password mode is still wired and selected
        // — separately we can verify it's rejected by NATS, which is what the
        // user would see during migration on a relay that only accepts NKeys).
        var url = "nats://127.0.0.1:" + natsPort;
        var bridge = new RelayBridge(
            url, url, "test-zone", identity.nodeId(),
            /*authUser=*/"hh-bogus", /*authPassword=*/"badtoken",
            /*nodeIdentity=*/null);
        try {
            bridge.start();
        } catch (Exception expected) {
            // Expected — NKey-only NATS rejects password auth. Confirm we tried.
            assertThat(expected.getMessage().toLowerCase())
                .satisfiesAnyOf(
                    msg -> assertThat(msg).contains("auth"),
                    msg -> assertThat(msg).contains("violation"),
                    msg -> assertThat(msg).contains("rejected"));
            return;
        } finally {
            bridge.close();
        }
        // Some NATS versions disconnect silently rather than throwing — assert
        // that the connection ended up not-connected.
        assertThat(bridge.isConnected()).isFalse();
    }

    private static int freePort() throws IOException {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void waitForNatsHealthy(int port, Duration timeout) throws InterruptedException {
        var deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try (var s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException ignored) {
                Thread.sleep(100);
            }
        }
        throw new RuntimeException("nats-server didn't accept connections on " + port
            + " within " + timeout);
    }
}
