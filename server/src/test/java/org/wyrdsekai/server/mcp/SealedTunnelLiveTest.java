package org.wyrdsekai.server.mcp;

import io.javalin.Javalin;
import io.nats.client.Nats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.crypto.SealedTunnel;
import org.wyrdsekai.core.crypto.TunnelKey;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The phone tunnel through a relay, sealed end to end ( W3), against a real
 * NATS server and a stand-in for the home's /ws. A second connection listens to every tunnel subject
 * the way a relay could, and never sees the login token or a word of the conversation.
 * Gate: TUNNEL_LIVE_NATS_URL (a NATS server without auth, e.g. a scratch nats:2.11.4).
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "TUNNEL_LIVE_NATS_URL", matches = ".+")
class SealedTunnelLiveTest {

    private Javalin home;

    @AfterEach
    void stop() {
        if (home != null) home.stop();
        System.clearProperty("wyrdsekai.tunnel.allow.plaintext");
    }

    private int startHome() {
        home = Javalin.create(cfg -> cfg.routes.ws("/ws", ws -> {
            ws.onConnect(ctx -> ctx.send("{\"type\":\"welcome\",\"token\":\"" + ctx.queryParam("token") + "\"}"));
            ws.onMessage(ctx -> ctx.send("{\"type\":\"echo\",\"of\":" + ctx.message() + "}"));
        })).start(0);
        return home.port();
    }

    @Test
    void aSealedSessionCarriesTheConversationAndTheRelaySeesOnlyCiphertext() throws Exception {
        var url = System.getenv("TUNNEL_LIVE_NATS_URL");
        var zone = "zone-" + UUID.randomUUID().toString().substring(0, 8);
        var session = UUID.randomUUID().toString().replace("-", "");
        var homeKey = SealedTunnel.generate();
        try (var homeConn = Nats.connect(url); var phone = Nats.connect(url); var relay = Nats.connect(url)) {
            var seenByRelay = new CopyOnWriteArrayList<byte[]>();
            relay.createDispatcher(m -> seenByRelay.add(m.getData())).subscribe("wyrd.tunnel." + zone + ".>");
            var handler = new TunnelSessionHandler(homeConn, zone, startHome(), homeKey);
            handler.start();
            var down = new LinkedBlockingQueue<byte[]>();
            var prefix = "wyrd.tunnel." + zone + "." + session;
            phone.createDispatcher(m -> down.add(m.getData())).subscribe(prefix + ".down");
            relay.flush(Duration.ofSeconds(2)); phone.flush(Duration.ofSeconds(2));

            var eph = SealedTunnel.generate();
            phone.publish(prefix + ".open", ("{\"v\":2,\"e\":\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(eph.pub()) + "\"}")
                .getBytes(StandardCharsets.UTF_8));
            var reply = new String(down.poll(10, TimeUnit.SECONDS), StandardCharsets.UTF_8);
            assertThat(reply).startsWith("{\"v\":2,\"e\":\"");
            var zoneEph = TunnelKey.decodeKey(reply.replaceAll(".*\"e\":\"([^\"]+)\".*", "$1"));
            var ch = SealedTunnel.complete(eph, homeKey.pub(), zoneEph, session);

            phone.publish(prefix + ".up", ch.up().seal("{\"token\":\"secret-login-token\"}".getBytes(StandardCharsets.UTF_8),
                (prefix + ".up").getBytes(StandardCharsets.UTF_8)));
            var welcome = new String(ch.down().open(down.poll(10, TimeUnit.SECONDS), (prefix + ".down").getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
            assertThat(welcome).contains("\"welcome\"").contains("secret-login-token");

            phone.publish(prefix + ".up", ch.up().seal("{\"type\":\"say\",\"text\":\"a private sentence\"}".getBytes(StandardCharsets.UTF_8),
                (prefix + ".up").getBytes(StandardCharsets.UTF_8)));
            var echo = new String(ch.down().open(down.poll(10, TimeUnit.SECONDS), (prefix + ".down").getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
            assertThat(echo).contains("a private sentence");

            relay.flush(Duration.ofSeconds(2));
            var wire = new StringBuilder();
            for (var b : seenByRelay) wire.append(new String(b, StandardCharsets.ISO_8859_1));
            assertThat(seenByRelay).hasSizeGreaterThanOrEqualTo(6);
            assertThat(wire.toString()).doesNotContain("secret-login-token").doesNotContain("private sentence").doesNotContain("welcome");
            handler.stop();
        }
    }

    @Test
    void anUnsealedTunnelIsRefusedUnlessAllowed() throws Exception {
        var url = System.getenv("TUNNEL_LIVE_NATS_URL");
        var zone = "zone-" + UUID.randomUUID().toString().substring(0, 8);
        var session = UUID.randomUUID().toString().replace("-", "");
        try (var homeConn = Nats.connect(url); var phone = Nats.connect(url)) {
            var handler = new TunnelSessionHandler(homeConn, zone, startHome(), SealedTunnel.generate());
            handler.start();
            var down = new LinkedBlockingQueue<byte[]>();
            var prefix = "wyrd.tunnel." + zone + "." + session;
            phone.createDispatcher(m -> down.add(m.getData())).subscribe(prefix + ".down");
            phone.flush(Duration.ofSeconds(2));
            phone.publish(prefix + ".open", "{\"token\":\"old-app-token\"}".getBytes(StandardCharsets.UTF_8));
            var reply = new String(down.poll(10, TimeUnit.SECONDS), StandardCharsets.UTF_8);
            assertThat(reply).contains("tunnel_plaintext_refused");
            assertThat(down.poll(2, TimeUnit.SECONDS)).as("no session was opened").isNull();

            System.setProperty("wyrdsekai.tunnel.allow.plaintext", "true");
            var session2 = UUID.randomUUID().toString().replace("-", "");
            var prefix2 = "wyrd.tunnel." + zone + "." + session2;
            phone.createDispatcher(m -> down.add(m.getData())).subscribe(prefix2 + ".down");
            phone.flush(Duration.ofSeconds(2));
            phone.publish(prefix2 + ".open", "{\"token\":\"old-app-token\"}".getBytes(StandardCharsets.UTF_8));
            assertThat(new String(down.poll(10, TimeUnit.SECONDS), StandardCharsets.UTF_8)).contains("welcome");
            handler.stop();
        }
    }
}
