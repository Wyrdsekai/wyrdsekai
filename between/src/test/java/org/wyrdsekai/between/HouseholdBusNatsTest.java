package org.wyrdsekai.between;

import io.nats.client.Connection;
import io.nats.client.ErrorListener;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.crypto.HouseholdBus;
import org.wyrdsekai.core.crypto.HouseholdTls;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * W2 against a real nats-server (bound to 127.0.0.1, removed after): the
 * household bus the node generates accepts only logged-in TLS clients, on the client port and the
 * phones' websocket; an anonymous client, a client without TLS and a client trusting another
 * household are refused; a phone login is held to its own subjects; a login issued while the server
 * runs works after the reload; a machine that joined reaches its hub through the saved hub link.
 *
 * <p>Run with {@code ./gradlew :between:testNats}. nats-server: {@code WYRDSEKAI_NATS_EXECUTABLE},
 * else {@code nats-server} on PATH or in the install folders; skipped when there is none.</p>
 */
@Tag("integration")
@Tag("needs-nats")
class HouseholdBusNatsTest {

    private final List<Connection> open = new ArrayList<>();
    private NatsServerManager manager;
    private Process rawServer;

    @AfterEach
    void cleanUp() throws Exception {
        for (var c : open) c.close();
        if (manager != null) manager.stop();
        if (rawServer != null) {
            rawServer.destroy();
            if (!rawServer.waitFor(5, TimeUnit.SECONDS)) rawServer.destroyForcibly();
        }
    }

    private static String executable() {
        var env = System.getenv("WYRDSEKAI_NATS_EXECUTABLE");
        var exe = env != null && !env.isBlank() ? env : "nats-server";
        assumeTrue(NatsServerManager.isAvailable(exe), "nats-server not available");
        return NatsServerManager.resolved();
    }

    private static int freePort() throws IOException {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private Connection connect(Options.Builder b) throws Exception {
        var c = Nats.connect(b.connectionTimeout(Duration.ofSeconds(5)).maxReconnects(0).build());
        open.add(c);
        return c;
    }

    private static Options.Builder opts(String url) {
        return new Options.Builder().server(url).noReconnect();
    }

    @Test
    void theNodesBusRequiresLoginAndTlsOnBothPorts(@TempDir Path data, @TempDir Path elsewhere) throws Exception {
        int port = freePort();
        int monitor = freePort();
        manager = new NatsServerManager(executable(), port, monitor, data, false);
        manager.start();
        var url = "nats://127.0.0.1:" + port;
        var ca = HouseholdTls.readCa(data);
        var bus = HouseholdBus.open(data);
        var node = bus.nodeCredential();

        // The generated settings pass nats-server's own check (it started) and say what doctor reads.
        var varz = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + monitor + "/varz")).build(), HttpResponse.BodyHandlers.ofString()).body();
        assertThat(varz.replace(" ", "")).contains("\"auth_required\":true").contains("\"tls_required\":true");

        // Anonymous, in the clear: refused.
        assertThatThrownBy(() -> connect(opts(url))).isInstanceOf(Exception.class);
        // Anonymous over TLS: refused (no login).
        assertThatThrownBy(() -> connect(opts(url).sslContext(HouseholdTls.clientContext(ca))))
            .isInstanceOf(Exception.class);
        // The right login without TLS: refused (TLS required).
        assertThatThrownBy(() -> connect(opts(url).userInfo(node.user(), node.pass())))
            .isInstanceOf(Exception.class);
        // The right login over TLS, but trusting another household's CA: refused.
        var otherCa = HouseholdTls.ensure(elsewhere).ca();
        assertThatThrownBy(() -> connect(opts(url).userInfo(node.user(), node.pass())
            .sslContext(HouseholdTls.clientContext(otherCa)))).isInstanceOf(Exception.class);
        // The node's login over TLS trusting this household: accepted, and it can use the bus.
        var nc = connect(opts(url).userInfo(node.user(), node.pass()).sslContext(HouseholdTls.clientContext(ca)));
        var sub = nc.subscribe("account.registered");
        nc.publish("account.registered", "x".getBytes(StandardCharsets.UTF_8));
        assertThat(sub.nextMessage(Duration.ofSeconds(3))).isNotNull();

        // The node's own clients find all of this by themselves (NatsBridge, RelayBridge, hermod).
        var auto = connect(HouseholdBusClient.secure(opts(url), url, data));
        assertThat(auto.getStatus()).isEqualTo(Connection.Status.CONNECTED);

        // The phones' websocket: wss with a login only.
        var wss = "wss://127.0.0.1:" + (port + 1);
        var phone = bus.issue(HouseholdBus.Kind.PHONE, "phone-device-1");
        var pc = connect(opts(wss).userInfo(phone.user(), phone.pass()).sslContext(HouseholdTls.clientContext(ca)));
        assertThat(pc.getStatus()).isEqualTo(Connection.Status.CONNECTED);
        // A phone knows only home_ca_fp: the served chain must carry the CA (leaf + CA), on both ports.
        var fp = HouseholdTls.fingerprint(ca);
        assertThat(connect(opts(wss).userInfo(phone.user(), phone.pass()).sslContext(HouseholdTls.pinnedContext(fp)))
            .getStatus()).isEqualTo(Connection.Status.CONNECTED);
        assertThat(connect(opts(url).userInfo(node.user(), node.pass()).sslContext(HouseholdTls.pinnedContext(fp)))
            .getStatus()).isEqualTo(Connection.Status.CONNECTED);
        assertThatThrownBy(() -> connect(opts("ws://127.0.0.1:" + (port + 1)).userInfo(phone.user(), phone.pass())))
            .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> connect(opts(wss).sslContext(HouseholdTls.clientContext(ca))))
            .isInstanceOf(Exception.class);
    }

    @Test
    void theNodesOwnBusConnectionTrustsItsHouseholdThroughTheInterfaceAwarePort(@TempDir Path data) throws Exception {
        // NatsBridge connects through InterfaceAwareDataPort. Its TLS upgrade used the JVM's default
        // trust store and ignored the connection's context, so the node refused its own household
        // bus's certificate (found on a scratch boot, 2026-09-28).
        int port = freePort();
        manager = new NatsServerManager(executable(), port, freePort(), data, false);
        manager.start();
        var url = "nats://127.0.0.1:" + port;
        var b = HouseholdBusClient.secure(opts(url).dataPortType("io.nats.client.impl.InterfaceAwareDataPort"), url, data);
        var nc = connect(b);
        assertThat(nc.getStatus()).isEqualTo(Connection.Status.CONNECTED);
        var sub = nc.subscribe("between.check");
        nc.publish("between.check", "x".getBytes(StandardCharsets.UTF_8));
        assertThat(sub.nextMessage(Duration.ofSeconds(3))).isNotNull();
    }

    @Test
    void aPhoneIsHeldToItsOwnSubjectsAndNewLoginsWorkAfterTheReload(@TempDir Path data) throws Exception {
        int port = freePort();
        manager = new NatsServerManager(executable(), port, freePort(), data, false);
        manager.start();
        var url = "nats://127.0.0.1:" + port;
        var ca = HouseholdTls.readCa(data);
        var bus = HouseholdBus.open(data);

        // Issued while the server runs: the include is rewritten and nats-server reloaded.
        var phone = bus.issue(HouseholdBus.Kind.PHONE, "late-device");
        var errors = new ArrayList<String>();
        Connection pc = null;
        for (int i = 0; i < 20 && pc == null; i++) {
            try {
                pc = connect(opts(url).userInfo(phone.user(), phone.pass()).sslContext(HouseholdTls.clientContext(ca))
                    .errorListener(new ErrorListener() {
                        @Override public void errorOccurred(Connection conn, String error) { errors.add(error); }
                    }));
            } catch (Exception e) {
                Thread.sleep(250);
            }
        }
        assertThat(pc).as("a login issued while the bus runs is accepted after the reload").isNotNull();

        var zone = WyrdConfig.get().zoneId();
        var node = bus.nodeCredential();
        var nc = connect(opts(url).userInfo(node.user(), node.pass()).sslContext(HouseholdTls.clientContext(ca)));
        var nodeSeesZone = nc.subscribe("wyrd.zone." + zone + ".mcp.login");
        var nodeSeesAccount = nc.subscribe("account.registered");
        nc.flush(Duration.ofSeconds(2));

        pc.publish("wyrd.zone." + zone + ".mcp.login", "hello".getBytes(StandardCharsets.UTF_8));
        pc.publish("account.registered", "{\"role\":\"steward\"}".getBytes(StandardCharsets.UTF_8));
        pc.flush(Duration.ofSeconds(2));
        assertThat(nodeSeesZone.nextMessage(Duration.ofSeconds(3))).as("its own zone's requests").isNotNull();
        assertThat(nodeSeesAccount.nextMessage(Duration.ofMillis(800))).as("no account records from a phone").isNull();

        // Another phone's reply inbox is not readable; its own is.
        pc.subscribe("_INBOX.phone-someone-else.>");
        pc.flush(Duration.ofSeconds(2));
        Thread.sleep(300);
        assertThat(String.join(" ", errors)).containsIgnoringCase("permissions violation");
        errors.clear();
        pc.subscribe("_INBOX." + phone.user() + ".x");
        pc.flush(Duration.ofSeconds(2));
        Thread.sleep(300);
        assertThat(errors).isEmpty();

        // Another phone's Study frames (they carry its session token) and inference answers are not
        // readable; frames addressed to this phone and its own answers are.
        for (var other : List.of("between." + zone + ".*.*.study.state",
                "between." + zone + ".phone-someone-else.*.study.sync", "federation.inference.stream.*")) {
            pc.subscribe(other);
            pc.flush(Duration.ofSeconds(2));
            Thread.sleep(300);
            assertThat(String.join(" ", errors)).as(other).containsIgnoringCase("permissions violation");
            errors.clear();
        }
        var toPhone = pc.subscribe("between." + zone + ".*." + phone.user() + ".study.sync");
        pc.subscribe("federation.inference.stream." + phone.user() + ".*");
        pc.flush(Duration.ofSeconds(2));
        Thread.sleep(300);
        assertThat(errors).isEmpty();
        nc.publish("between." + zone + ".home-node." + phone.user() + ".study.sync", "{}".getBytes(StandardCharsets.UTF_8));
        nc.flush(Duration.ofSeconds(2));
        assertThat(toPhone.nextMessage(Duration.ofSeconds(3))).as("Study frames addressed to it").isNotNull();
        var nodeSeesStudy = nc.subscribe("between." + zone + ".*.*.study.sync");
        nc.flush(Duration.ofSeconds(2));
        pc.publish("between." + zone + ".phone-someone-else.home-node.study.sync", "{}".getBytes(StandardCharsets.UTF_8));
        pc.publish("between." + zone + "." + phone.user() + ".home-node.study.sync", "{}".getBytes(StandardCharsets.UTF_8));
        pc.flush(Duration.ofSeconds(2));
        var got = nodeSeesStudy.nextMessage(Duration.ofSeconds(3));
        assertThat(got).as("its own Study frames reach the home").isNotNull();
        assertThat(got.getSubject()).contains("." + phone.user() + ".");
        assertThat(nodeSeesStudy.nextMessage(Duration.ofMillis(800))).as("no frames sent as another phone").isNull();

        // Revoked: the login no longer works for new connections.
        bus.revoke("late-device");
        boolean refused = false;
        for (int i = 0; i < 20 && !refused; i++) {
            try {
                connect(opts(url).userInfo(phone.user(), phone.pass()).sslContext(HouseholdTls.clientContext(ca)));
                Thread.sleep(250);
            } catch (Exception e) {
                refused = true;
            }
        }
        assertThat(refused).as("a revoked phone login is refused").isTrue();
    }

    @Test
    void aNewLoginWorksOnTheFirstTryOnceIssueReturns(@TempDir Path data) throws Exception {
        int port = freePort();
        manager = new NatsServerManager(executable(), port, freePort(), data, false);
        manager.start();
        var url = "nats://127.0.0.1:" + port;
        var ca = HouseholdTls.readCa(data);
        var bus = HouseholdBus.open(data);
        // The pairing reply goes out as soon as issue() returns: the phone's first connection must work.
        for (int i = 0; i < 5; i++) {
            var phone = bus.issue(HouseholdBus.Kind.PHONE, "first-try-" + i);
            var pc = connect(opts(url).userInfo(phone.user(), phone.pass()).sslContext(HouseholdTls.clientContext(ca)));
            assertThat(pc.getStatus()).as("attempt " + i).isEqualTo(Connection.Status.CONNECTED);
            pc.close();
        }
    }

    @Test
    void aJoinedMachineReachesItsHubPinnedAndLoggedIn(@TempDir Path hub, @TempDir Path joiner) throws Exception {
        int port = freePort();
        manager = new NatsServerManager(executable(), port, freePort(), hub, false);
        manager.start();
        var url = "nats://127.0.0.1:" + port;
        var login = HouseholdBus.open(hub).issue(HouseholdBus.Kind.MACHINE, "joining-node");
        var caPem = Files.readString(hub.resolve("tls").resolve(HouseholdTls.CA_CERT));
        HouseholdBus.saveHubLink(joiner, new HouseholdBus.HubLink(url, login.user(), login.pass(), caPem));

        Connection c = null;
        for (int i = 0; i < 20 && c == null; i++) {
            try {
                c = connect(HouseholdBusClient.secure(opts(url), url, joiner));
            } catch (Exception e) {
                Thread.sleep(250);
            }
        }
        assertThat(c).isNotNull();
        assertThat(c.getStatus()).isEqualTo(Connection.Status.CONNECTED);
    }

    @Test
    void theTransitionSettingLetsOldClientsInAndNewOnesStillWork(@TempDir Path data) throws Exception {
        int port = freePort();
        int ws = freePort();
        var tls = HouseholdTls.ensure(data);
        var bus = HouseholdBus.open(data);
        bus.writeInclude(tls, new HouseholdBus.Listen("127.0.0.1", port, ws, "ferngrove", true));
        var conf = data.resolve("nats.conf");
        Files.writeString(conf, "include \"nats/household-bus.conf\"\n");
        rawServer = new ProcessBuilder(executable(), "-c", conf.toString()).redirectErrorStream(true)
            .redirectOutput(data.resolve("nats.log").toFile()).start();

        Connection legacy = null;
        for (int i = 0; i < 40 && legacy == null; i++) {
            try {
                legacy = connect(opts("nats://127.0.0.1:" + port));
            } catch (Exception e) {
                Thread.sleep(250);
            }
        }
        assertThat(legacy).as("a machine from before 0.5.0: no login, no TLS").isNotNull();
        var oldPhone = connect(opts("ws://127.0.0.1:" + ws));
        assertThat(oldPhone.getStatus()).isEqualTo(Connection.Status.CONNECTED);
        var node = bus.nodeCredential();
        var current = connect(opts("nats://127.0.0.1:" + port).userInfo(node.user(), node.pass())
            .sslContext(HouseholdTls.clientContext(tls.ca())));
        assertThat(current.getStatus()).isEqualTo(Connection.Status.CONNECTED);
    }
}
