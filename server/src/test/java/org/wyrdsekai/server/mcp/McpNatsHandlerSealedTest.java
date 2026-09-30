package org.wyrdsekai.server.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.MessageHandler;
import io.nats.client.impl.NatsMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.crypto.SealedRequest;
import org.wyrdsekai.core.crypto.SealedTunnel;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.persistence.SqlDialect;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The phone's request/reply subjects sealed end to end ( W3, sealed requests):
 * on the relay's bus the handler answers only sealed requests and seals its reply; on the home's own
 * bus it still takes plaintext. A fake NATS connection records every byte the handler publishes.
 */
class McpNatsHandlerSealedTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ZONE = "zone-example";
    private static final String LOGIN = "wyrd.zone." + ZONE + ".mcp.login";
    private static final String PASSWORD = "correct horse battery";
    private static final byte[] LOGIN_BODY = ("{\"username\":\"alice\",\"password\":\"" + PASSWORD + "\"}")
        .getBytes(StandardCharsets.UTF_8);

    private record Published(String subject, byte[] data) {
        String text() { return new String(data, StandardCharsets.UTF_8); }
    }

    /** A NATS connection that only records: getStatus, createDispatcher, publish. */
    private static final class FakeBus {
        final List<Published> published = new ArrayList<>();
        final AtomicReference<MessageHandler> handler = new AtomicReference<>();
        final Connection conn = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[]{Connection.class}, (p, m, a) -> switch (m.getName()) {
                case "getStatus" -> Connection.Status.CONNECTED;
                case "createDispatcher" -> {
                    handler.set((MessageHandler) a[0]);
                    yield dispatcher();
                }
                case "publish" -> {
                    if (a.length == 2 && a[0] instanceof String s && a[1] instanceof byte[] b) published.add(new Published(s, b));
                    yield null;
                }
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> p == a[0];
                case "toString" -> "FakeBus";
                default -> null;
            });

        private static Dispatcher dispatcher() {
            return (Dispatcher) Proxy.newProxyInstance(Dispatcher.class.getClassLoader(), new Class<?>[]{Dispatcher.class},
                (p, m, a) -> m.getReturnType().isInstance(p) ? p : null);
        }

        /** Delivers a request and returns what the handler answered on its inbox (null for silence). */
        Published request(String subject, byte[] data) throws Exception {
            var inbox = "_INBOX.test." + published.size() + "." + System.nanoTime();
            handler.get().onMessage(new NatsMessage(subject, inbox, data));
            return published.stream().filter(x -> x.subject().equals(inbox)).findFirst().orElse(null);
        }
    }

    @TempDir Path dir;
    private AuthService auth;
    private SealedTunnel.KeyPair homeKey;
    private SealedRequest.Home home;

    @BeforeEach
    void setUp() {
        var jdbc = SchemaInitializer.initialize(dir.resolve("world.db"));
        auth = new AuthService(jdbc, SqlDialect.fromJdbcUrl(jdbc));
        assertThat(auth.register("alice", PASSWORD, "Alice", "member")).isPresent();
        try {
            homeKey = SealedTunnel.generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        home = new SealedRequest.Home(homeKey);
    }

    @AfterEach
    void clear() {
        System.clearProperty("wyrdsekai.relay.allow.plaintext.requests");
    }

    private FakeBus start(boolean relayLeg) {
        var bus = new FakeBus();
        new McpNatsHandler(auth, null, bus.conn, ZONE, null, null, null, null, home, relayLeg).start();
        return bus;
    }

    @Test
    void aSealedLoginOverTheRelayIsAnsweredSealedAndNothingOnTheWireCarriesTheSecrets() throws Exception {
        var bus = start(true);
        var out = SealedRequest.seal(homeKey.pub(), LOGIN, LOGIN_BODY);
        var reply = bus.request(LOGIN, out.wire());
        assertThat(reply).isNotNull();
        var opened = MAPPER.readTree(out.openReply(reply.data()));
        assertThat(opened.path("ok").asBoolean()).isTrue();
        var token = opened.path("token").asText();
        assertThat(token).isNotBlank();
        assertThat(auth.validateSession(token)).isPresent();
        assertThat(reply.text()).startsWith("{\"v\":2,\"c\":\"");
        for (var p : bus.published) {
            assertThat(p.text()).doesNotContain(token).doesNotContain(PASSWORD).doesNotContain("alice");
        }
        assertThat(new String(out.wire(), StandardCharsets.UTF_8)).doesNotContain(PASSWORD);
    }

    @Test
    void aPlaintextLoginOverTheRelayIsRefusedAndNoTokenIsMade() throws Exception {
        var bus = start(true);
        var reply = bus.request(LOGIN, LOGIN_BODY);
        assertThat(reply.text()).isEqualTo("{\"ok\":false,\"error\":\"sealed_required\"}");
        assertThat(bus.published).hasSize(1);
    }

    @Test
    void aStrangersKnockAndTheDirectoryMayComeUnsealedButReadingKnocksMayNot() throws Exception {
        var bus = start(true);
        var knock = bus.request("wyrd.zone." + ZONE + ".directory.knock",
            "{\"requesterName\":\"a stranger\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(knock.text()).doesNotContain("sealed_required");
        var search = bus.request("wyrd.zone." + ZONE + ".directory.search", "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(search.text()).doesNotContain("sealed_required");
        var list = bus.request("wyrd.zone." + ZONE + ".directory.knock.list",
            "{\"token\":\"t\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(list.text()).isEqualTo("{\"ok\":false,\"error\":\"sealed_required\"}");
    }

    @Test
    void theTransitionSettingLetsAnOldAppLogInOverTheRelay() throws Exception {
        System.setProperty("wyrdsekai.relay.allow.plaintext.requests", "true");
        var bus = start(true);
        var reply = MAPPER.readTree(bus.request(LOGIN, LOGIN_BODY).data());
        assertThat(reply.path("ok").asBoolean()).isTrue();
        assertThat(reply.path("token").asText()).isNotBlank();
    }

    @Test
    void theHomesOwnBusTakesBothPlaintextAndSealedRequests() throws Exception {
        var bus = start(false);
        assertThat(MAPPER.readTree(bus.request(LOGIN, LOGIN_BODY).data()).path("ok").asBoolean()).isTrue();
        var out = SealedRequest.seal(homeKey.pub(), LOGIN, LOGIN_BODY);
        assertThat(MAPPER.readTree(out.openReply(bus.request(LOGIN, out.wire()).data())).path("ok").asBoolean()).isTrue();
    }

    @Test
    void everyHandlerAnswersSealedIncludingItsErrors() throws Exception {
        var bus = start(true);
        var subject = "wyrd.zone." + ZONE + ".auth.status";
        var status = SealedRequest.seal(homeKey.pub(), subject, new byte[0]);
        assertThat(MAPPER.readTree(status.openReply(bus.request(subject, status.wire()).data())).path("zoneId").asText())
            .isEqualTo(ZONE);
        var tell = "wyrd.zone." + ZONE + ".mcp.tell";
        var badTell = SealedRequest.seal(homeKey.pub(), tell, "{\"token\":\"nope\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(MAPPER.readTree(badTell.openReply(bus.request(tell, badTell.wire()).data())).path("error").asText())
            .isEqualTo("invalid_session");
    }

    @Test
    void aRequestThatDoesNotOpenIsRefusedWithoutDetail() throws Exception {
        var bus = start(true);
        // Sealed to another home's key (a relay pretending, or the wrong invite).
        var stranger = SealedRequest.seal(SealedTunnel.generate().pub(), LOGIN, LOGIN_BODY);
        assertThat(bus.request(LOGIN, stranger.wire()).text()).isEqualTo("{\"ok\":false,\"error\":\"sealed_refused\"}");
        // Sealed for another subject, then moved.
        var moved = SealedRequest.seal(homeKey.pub(), "wyrd.zone." + ZONE + ".auth.status", LOGIN_BODY);
        assertThat(bus.request(LOGIN, moved.wire()).text()).isEqualTo("{\"ok\":false,\"error\":\"sealed_refused\"}");
        // Replayed.
        var once = SealedRequest.seal(homeKey.pub(), LOGIN, LOGIN_BODY);
        assertThat(once.openReply(bus.request(LOGIN, once.wire()).data())).isNotEmpty();
        assertThat(bus.request(LOGIN, once.wire()).text()).isEqualTo("{\"ok\":false,\"error\":\"sealed_refused\"}");
    }

    @Test
    void theReplayCacheIsSharedByTheHomesLegs() throws Exception {
        var relay = start(true);
        var local = start(false);
        var out = SealedRequest.seal(homeKey.pub(), LOGIN, LOGIN_BODY);
        assertThat(out.openReply(relay.request(LOGIN, out.wire()).data())).isNotEmpty();
        assertThat(local.request(LOGIN, out.wire()).text()).isEqualTo("{\"ok\":false,\"error\":\"sealed_refused\"}");
    }

    @Test
    void zoneDiscoveryStaysOpenAndAnotherHomesSealedDiscoveryGetsNoAnswer() throws Exception {
        var bus = start(true);
        assertThat(MAPPER.readTree(bus.request("wyrd.discover.zone", new byte[0]).data()).path("zoneId").asText())
            .isEqualTo(ZONE);
        var other = SealedRequest.seal(SealedTunnel.generate().pub(), "wyrd.discover.zone", new byte[0]);
        assertThat(bus.request("wyrd.discover.zone", other.wire())).isNull();
    }
}
