package org.wyrdsekai.server.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.Nats;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.crypto.SealedRequest;
import org.wyrdsekai.core.crypto.SealedTunnel;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.persistence.SqlDialect;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The phone's login through a relay, sealed end to end ( W3, sealed requests)
 * against a real NATS server. A third connection subscribed to {@code >} records every message the way
 * a relay operator or a relay user reading {@code _INBOX.>} could, and never sees the password or the
 * session token. Gate: TUNNEL_LIVE_NATS_URL (a NATS server without auth, e.g. a scratch nats:2.11.4),
 * the same server SealedTunnelLiveTest uses.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "TUNNEL_LIVE_NATS_URL", matches = ".+")
class SealedRequestLiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PASSWORD = "correct horse battery";

    @TempDir Path dir;

    @Test
    void aSealedLoginThroughTheRelayShowsNeitherPasswordNorTokenAndAPlaintextOneIsRefused() throws Exception {
        var url = System.getenv("TUNNEL_LIVE_NATS_URL");
        var zone = "zone-" + UUID.randomUUID().toString().substring(0, 8);
        var subject = "wyrd.zone." + zone + ".mcp.login";
        var jdbc = SchemaInitializer.initialize(dir.resolve("world.db"));
        var auth = new AuthService(jdbc, SqlDialect.fromJdbcUrl(jdbc));
        assertThat(auth.register("alice", PASSWORD, "Alice", "member")).isPresent();
        var homeKey = SealedTunnel.generate();
        var body = ("{\"username\":\"alice\",\"password\":\"" + PASSWORD + "\"}").getBytes(StandardCharsets.UTF_8);

        try (var homeConn = Nats.connect(url); var phone = Nats.connect(url); var relay = Nats.connect(url)) {
            var seen = new CopyOnWriteArrayList<String>();
            relay.createDispatcher(m -> seen.add(m.getSubject() + " " + new String(m.getData(), StandardCharsets.ISO_8859_1)))
                .subscribe(">");
            relay.flush(Duration.ofSeconds(2));
            var handler = new McpNatsHandler(auth, null, homeConn, zone, null, null, null, null,
                new SealedRequest.Home(homeKey), true);
            handler.start();
            homeConn.flush(Duration.ofSeconds(2));

            var out = SealedRequest.seal(homeKey.pub(), subject, body);
            var reply = phone.request(subject, out.wire(), Duration.ofSeconds(10));
            assertThat(reply).isNotNull();
            var login = MAPPER.readTree(out.openReply(reply.getData()));
            assertThat(login.path("ok").asBoolean()).isTrue();
            var token = login.path("token").asText();
            assertThat(auth.validateSession(token)).isPresent();
            relay.flush(Duration.ofSeconds(2));
            var sealedTraffic = List.copyOf(seen);
            assertThat(sealedTraffic).hasSize(2);   // the request on its subject, the reply on the phone's inbox
            assertThat(sealedTraffic.get(0)).startsWith(subject + " {\"v\":2,");
            assertThat(sealedTraffic.get(1)).startsWith("_INBOX.").contains("{\"v\":2,\"c\":");
            assertThat(sealedTraffic.toString()).doesNotContain(PASSWORD).doesNotContain(token).doesNotContain("alice");

            var refused = phone.request(subject, body, Duration.ofSeconds(10));
            assertThat(new String(refused.getData(), StandardCharsets.UTF_8))
                .isEqualTo("{\"ok\":false,\"error\":\"sealed_required\"}");
            relay.flush(Duration.ofSeconds(2));
            // An old app's plaintext request is readable (it was sent that way); it is refused, so no token is.
            assertThat(seen).hasSize(4);
            assertThat(seen.toString()).doesNotContain(token).doesNotContain("\"token\"");
            handler.stop();
        }
    }
}
