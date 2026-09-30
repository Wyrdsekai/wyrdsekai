package org.wyrdsekai.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.Nats;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.common.protocol.C2SMessage;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.crypto.SealedRequest;
import org.wyrdsekai.core.crypto.SealedTunnel;
import org.wyrdsekai.core.crypto.TunnelKey;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The terminal reaching a home through a relay ( W3): the login as a sealed
 * request and the session as a sealed tunnel, against a real NATS server and a stand-in home built from
 * the same core primitives the home uses. A connection subscribed to {@code >} never sees the password,
 * the token or what was said. Gate: TUNNEL_LIVE_NATS_URL (a NATS server without auth).
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "TUNNEL_LIVE_NATS_URL", matches = ".+")
class RelayTunnelConnectionLiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PASSWORD = "correct horse battery";
    private static final String TOKEN = "tok-9f3a-secret-session";

    @Test
    void theTerminalLogsInAndTalksSealedAndTheRelaySeesNoneOfIt() throws Exception {
        var url = System.getenv("TUNNEL_LIVE_NATS_URL");
        var zone = "zone-" + UUID.randomUUID().toString().substring(0, 8);
        var homeKey = SealedTunnel.generate();
        var home = new SealedRequest.Home(homeKey);
        var ups = new LinkedBlockingQueue<String>();
        var channels = new ConcurrentHashMap<String, SealedTunnel.Accepted>();
        var firstFrames = ConcurrentHashMap.<String>newKeySet();

        try (var homeConn = Nats.connect(url); var relay = Nats.connect(url)) {
            var seen = new CopyOnWriteArrayList<String>();
            relay.createDispatcher(m -> seen.add(m.getSubject() + " " + new String(m.getData(), StandardCharsets.ISO_8859_1)))
                .subscribe(">");
            relay.flush(Duration.ofSeconds(2));
            var login = "wyrd.zone." + zone + ".mcp.login";
            var tunnel = "wyrd.tunnel." + zone + ".";
            homeConn.createDispatcher(m -> {
                try {
                    var opened = home.open(login, m.getData());
                    var body = MAPPER.readTree(opened.body());
                    var ok = "alice".equals(body.path("username").asText()) && PASSWORD.equals(body.path("password").asText());
                    homeConn.publish(m.getReplyTo(), SealedRequest.sealReply(opened.replyKey(), login,
                        (ok ? "{\"ok\":true,\"token\":\"" + TOKEN + "\"}" : "{\"ok\":false,\"error\":\"invalid_credentials\"}")
                            .getBytes(StandardCharsets.UTF_8)));
                } catch (Exception ex) {
                    ups.add("home error " + ex);
                }
            }).subscribe(login);
            var tunnelSub = homeConn.createDispatcher(m -> {
                try {
                    var rest = m.getSubject().substring(tunnel.length());
                    var session = rest.substring(0, rest.lastIndexOf('.'));
                    var down = tunnel + session + ".down";
                    if (rest.endsWith(".open")) {
                        var e = TunnelKey.decodeKey(MAPPER.readTree(m.getData()).path("e").asText());
                        var acc = SealedTunnel.accept(homeKey, e, session);
                        channels.put(session, acc);
                        homeConn.publish(down, ("{\"v\":2,\"e\":\"" + Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(acc.zoneEphemeralPub()) + "\"}").getBytes(StandardCharsets.UTF_8));
                    } else if (rest.endsWith(".up")) {
                        var acc = channels.get(session);
                        var frame = new String(acc.up().open(m.getData(), m.getSubject().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
                        if (firstFrames.add(session)) {
                            ups.add("first " + frame);
                            homeConn.publish(down, acc.down().seal(
                                "{\"type\":\"error\",\"seq\":1,\"code\":\"hello\",\"message\":\"welcome home\"}".getBytes(StandardCharsets.UTF_8),
                                down.getBytes(StandardCharsets.UTF_8)));
                        } else {
                            ups.add(frame);
                        }
                    }
                } catch (Exception ex) {
                    ups.add("home error " + ex);
                }
            });
            tunnelSub.subscribe(tunnel + "*.open");
            tunnelSub.subscribe(tunnel + "*.up");
            homeConn.flush(Duration.ofSeconds(2));

            var received = new LinkedBlockingQueue<S2CMessage>();
            var cli = new RelayTunnelConnection(url, null, null, null, zone, homeKey.pub(), received::add, null);
            assertThat(cli.loginOverRelay("alice", PASSWORD)).isTrue();
            cli.connect();
            assertThat(cli.awaitConnected(10_000)).isTrue();
            assertThat(ups.poll(10, TimeUnit.SECONDS)).isEqualTo("first {\"token\":\"" + TOKEN + "\"}");
            var hello = received.poll(10, TimeUnit.SECONDS);
            assertThat(hello).isInstanceOf(S2CMessage.Error.class);
            assertThat(((S2CMessage.Error) hello).message()).isEqualTo("welcome home");
            cli.send(new C2SMessage.Say("1", "study", "a private sentence"));
            assertThat(ups.poll(10, TimeUnit.SECONDS)).contains("a private sentence");
            cli.disconnect();

            relay.flush(Duration.ofSeconds(2));
            var wire = seen.toString();
            assertThat(seen.size()).isGreaterThanOrEqualTo(6);
            assertThat(wire).doesNotContain(PASSWORD).doesNotContain(TOKEN).doesNotContain("alice")
                .doesNotContain("private sentence").doesNotContain("welcome home");
        }
    }

    @Test
    void withoutTheHomesKeyTheTerminalSendsNoPassword() throws Exception {
        var url = System.getenv("TUNNEL_LIVE_NATS_URL");
        var zone = "zone-" + UUID.randomUUID().toString().substring(0, 8);
        try (var relay = Nats.connect(url)) {
            var seen = new CopyOnWriteArrayList<String>();
            relay.createDispatcher(m -> seen.add(new String(m.getData(), StandardCharsets.ISO_8859_1))).subscribe(">");
            relay.flush(Duration.ofSeconds(2));
            var cli = new RelayTunnelConnection(url, null, null, null, zone, null, m -> { }, null);
            assertThat(cli.loginOverRelay("alice", PASSWORD)).isFalse();
            cli.connect();
            assertThat(cli.awaitConnected(1_000)).isFalse();
            cli.disconnect();
            relay.flush(Duration.ofSeconds(2));
            assertThat(seen.toString()).doesNotContain(PASSWORD);
        }
    }
}
