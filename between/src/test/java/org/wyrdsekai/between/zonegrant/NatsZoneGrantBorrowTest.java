package org.wyrdsekai.between.zonegrant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.between.RelaySessionTransport;
import org.wyrdsekai.core.crypto.ZoneSecretService;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * #1184 — the multi-node zone-secret grant over NATS, end to end with REAL crypto and an
 * in-memory relay. Locks the contract: a trusted joiner gets the master and derives the IDENTICAL
 * secret argot key as the holder (so cross-node argot decodes); an untrusted node is refused without
 * reaching the issuer; a node that doesn't hold the master stays silent (no spurious grant).
 */
class NatsZoneGrantBorrowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ZONE = "zone-alpha";

    /** In-memory relay: publish delivers in-thread to a same-subject subscriber. */
    static final class FakeTransport extends RelaySessionTransport {
        final Map<String, Consumer<byte[]>> subs = new ConcurrentHashMap<>();
        @Override public boolean isConnected() { return true; }
        @Override public Object subscribe(String subject, Consumer<byte[]> handler) {
            subs.put(subject, handler); return subject;
        }
        @Override public void publish(String subject, byte[] data) {
            var s = subs.get(subject); if (s != null) s.accept(data);
        }
        @Override public void closeDispatcherObj(Object token) {
            if (token instanceof String s) subs.remove(s);
        }
    }

    /** Holder issuer: hands out a real ECIES grant of the zone master to the requester's pubkey. */
    private static NatsZoneGrantServer.GrantIssuer issuerFor(ZoneSecretService holder) {
        return (zone, requesterPub) ->
            holder.has(zone) ? Base64.getEncoder().encodeToString(holder.grantTo(zone, requesterPub)) : null;
    }

    @Test void protocol_round_trips() throws Exception {
        var req = new NatsZoneGrantProtocol.Request("rid", ZONE, "node-b", "cHViMQ==");
        var back = MAPPER.readValue(MAPPER.writeValueAsBytes(req), NatsZoneGrantProtocol.Request.class);
        assertThat(back.zoneId()).isEqualTo(ZONE);
        assertThat(back.requesterNodeId()).isEqualTo("node-b");
    }

    @Test void trusted_joiner_gets_master_and_derives_same_argot_key() throws Exception {
        var transport = new FakeTransport();

        // HOLDER (home-server) originates the master and listens for grant requests; trusts node-b.
        var holder = new ZoneSecretService();
        holder.generate(ZONE);
        var server = new NatsZoneGrantServer(transport, ZONE, "node-a",
            nodeId -> nodeId.equals("node-b"), issuerFor(holder));
        server.start();

        // JOINER (mac-node) has its own X25519 keypair and no master yet.
        var joinerKeys = ZoneSecretService.generateNodeEcdhKeyPair();
        var client = new NatsZoneGrantClient(transport, 5);
        var resp = client.requestGrant(ZONE, "node-b", joinerKeys.getPublic().getEncoded())
            .get(5, TimeUnit.SECONDS);

        assertThat(resp.ok()).as("holder granted the master").isTrue();
        assertThat(resp.granterNodeId()).isEqualTo("node-a");

        // Joiner unwraps with ITS private key and now derives the SAME argot key as the holder.
        var joiner = new ZoneSecretService();
        joiner.acceptGrant(ZONE, Base64.getDecoder().decode(resp.grantBlobBase64()),
            joinerKeys.getPrivate());
        assertThat(joiner.derive(ZONE, "argot-v1", 32))
            .as("both nodes derive the identical secret argot key → cross-node argot decodes")
            .containsExactly(holder.derive(ZONE, "argot-v1", 32));
    }

    @Test void untrusted_node_is_denied_without_issuing() throws Exception {
        var transport = new FakeTransport();
        var holder = new ZoneSecretService();
        holder.generate(ZONE);
        boolean[] issued = {false};
        var server = new NatsZoneGrantServer(transport, ZONE, "node-a",
            nodeId -> false,  // trust nobody
            (zone, pub) -> { issued[0] = true; return "should-never-happen"; });
        server.start();

        var keys = ZoneSecretService.generateNodeEcdhKeyPair();
        var client = new NatsZoneGrantClient(transport, 5);
        var resp = client.requestGrant(ZONE, "stranger", keys.getPublic().getEncoded())
            .get(5, TimeUnit.SECONDS);

        assertThat(resp.ok()).isFalse();
        assertThat(resp.error()).contains("not a known member");
        assertThat(issued[0]).as("issuer must not run for an untrusted node").isFalse();
    }

    @Test void non_holder_stays_silent_so_no_spurious_grant() {
        var transport = new FakeTransport();
        // This node trusts the requester but does NOT hold the master → issuer returns null.
        var emptyHolder = new ZoneSecretService();
        var server = new NatsZoneGrantServer(transport, ZONE, "node-c",
            nodeId -> true, issuerFor(emptyHolder));
        server.start();

        var keys = ZoneSecretService.generateNodeEcdhKeyPair();
        var client = new NatsZoneGrantClient(transport, 1);  // short timeout
        var future = client.requestGrant(ZONE, "node-b", keys.getPublic().getEncoded());

        var ex = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
        assertThat(ex.getCause()).isInstanceOf(RuntimeException.class);
        assertThat(ex.getCause().getMessage()).contains("timed out");
    }
}
