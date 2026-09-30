package org.wyrdsekai.between;

import io.nats.client.Connection;
import io.nats.client.Consumer;
import io.nats.client.ErrorListener;
import io.nats.client.Options;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A node's NKey link to a relay that binds registrations to zones (security review 2026-09-28): it
 * connects encrypted and pinned from the join, takes its own reply inbox, reaches its own zone's
 * subjects, and is refused another zone's and another user's inbox. Gates: RELAY_ZONE_LIVE_URL (the zone
 * port), RELAY_ZONE_LIVE_IDENTITY (node-identity.json that registered), RELAY_ZONE_LIVE_ZONE, and
 * WYRDSEKAI_DATA_DIR (where the join wrote relay-tls-pins).
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "RELAY_ZONE_LIVE_URL", matches = ".+")
class RelayZoneLiveTest {

    @Test
    void aBoundNodeReachesOnlyItsOwnZone() throws Exception {
        var url = System.getenv("RELAY_ZONE_LIVE_URL");
        var zone = System.getenv("RELAY_ZONE_LIVE_ZONE");
        var identity = NodeIdentity.loadOrGenerate(Path.of(System.getenv("RELAY_ZONE_LIVE_IDENTITY")));
        List<String> errors = new CopyOnWriteArrayList<>();
        var opts = new Options.Builder().server(url).maxReconnects(0)
            .authHandler(identity.nkeyAuthHandler())
            .errorListener(new ErrorListener() {
                @Override public void errorOccurred(Connection conn, String error) { errors.add(error); }
                @Override public void exceptionOccurred(Connection conn, Exception exp) { }
                @Override public void slowConsumerDetected(Connection conn, Consumer consumer) { }
            });
        assertThat(RelayTls.storedPins(RelayTls.hostPort(url))).as("pins written by the join").isNotEmpty();
        try (var conn = RelayTls.connect(opts, url)) {
            assertThat(conn.getOptions().getInboxPrefix()).isEqualTo("_INBOX." + identity.nkeyPublicKey() + ".");
            var d = conn.createDispatcher(m -> { });
            for (var own : List.of("between." + zone + ".>", "federation." + zone + ".gate.>",
                    "federation.*." + zone + ".gate.>", "federation.inference.stream." + zone + ".x",
                    "_INBOX." + identity.nkeyPublicKey() + ".>")) {
                d.subscribe(own);
            }
            conn.flush(Duration.ofSeconds(5));
            Thread.sleep(500);
            assertThat(errors).as("own zone allowed").isEmpty();
            for (var other : List.of("between.otherzone.>", "federation.*.gate.>", "_INBOX.>",
                    "federation.inference.stream.*")) {
                d.subscribe(other);
            }
            conn.flush(Duration.ofSeconds(5));
            Thread.sleep(800);
            assertThat(String.join("\n", errors).toLowerCase())
                .contains("permissions violation for subscription to \"between.otherzone.>\"")
                .contains("permissions violation for subscription to \"federation.*.gate.>\"")
                .contains("permissions violation for subscription to \"_inbox.>\"")
                .contains("permissions violation for subscription to \"federation.inference.stream.*\"");
        }
    }
}
