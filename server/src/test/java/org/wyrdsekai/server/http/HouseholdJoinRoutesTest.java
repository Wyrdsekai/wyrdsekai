package org.wyrdsekai.server.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.core.crypto.HouseholdBus;
import org.wyrdsekai.core.crypto.HouseholdTls;
import org.wyrdsekai.core.identity.HouseholdStore;
import org.wyrdsekai.core.persistence.PairingService;
import org.wyrdsekai.core.persistence.SqlDialect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * the "auto add to home zone" enrollment endpoint.
 * Verifies a valid pre-shared household key plus a signed one-time challenge enrolls
 * a peer into the hub's households table (so the GPU-borrow gate fires), and that an
 * invalid key, a missing or foreign proof, or a reused challenge enrols no one.
 * Fully offline (no network).
 */
class HouseholdJoinRoutesTest {

    private String jdbcUrl;
    private HouseholdStore householdStore;
    private PairingService pairingService;
    private NodeIdentity hubIdentity;
    private String validKey;
    private Path dataDir;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        var dbName = "hj-test-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcUrl = "jdbc:sqlite:file:" + dbName + "?mode=memory&cache=shared";
        // Keep the shared in-memory db alive for the whole test.
        @SuppressWarnings("resource")
        var keepAlive = DriverManager.getConnection(jdbcUrl);
        try (var stmt = keepAlive.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS households(
                  household_id    TEXT PRIMARY KEY,
                  public_key      BLOB NOT NULL,
                  fingerprint     TEXT NOT NULL,
                  did_key         TEXT,
                  x25519_public_key BLOB,
                  registered_at   INTEGER NOT NULL,
                  updated_at      INTEGER NOT NULL DEFAULT (unixepoch())
                )
                """);
        }
        householdStore = new HouseholdStore(jdbcUrl);

        pairingService = new PairingService(jdbcUrl, SqlDialect.fromJdbcUrl(jdbcUrl),
            "hub-household", "Hub Household", "", "nats://127.0.0.1:4222",
            "http://127.0.0.1:7070");
        pairingService.initSchema();
        validKey = pairingService.generateHouseholdKey();

        hubIdentity = NodeIdentity.loadOrGenerate(tmp.resolve("node-identity.json"));
        dataDir = tmp;
    }

    private HouseholdJoinRoutes routes;

    private HouseholdJoinRoutes routes() {
        if (routes == null) {
            routes = new HouseholdJoinRoutes(pairingService, householdStore, hubIdentity,
                () -> "198.51.100.50");
        }
        return routes;
    }

    private static HouseholdJoinRoutes.JoinNode nodeOf(NodeIdentity id) {
        return new HouseholdJoinRoutes.JoinNode(
            id.nodeId(), id.publicKeyBase64(),
            "aa:bb:cc:dd",                 // client-supplied values the hub must not trust
            "did:wyrd:z6MkPeer",
            id.x25519PublicKeyBase64());
    }

    @SuppressWarnings("unchecked")
    private String challengeFor(String nodeId) {
        var res = routes().issueChallenge(nodeId);
        assertThat(res.status()).isEqualTo(200);
        return (String) ((Map<String, Object>) res.body()).get("challenge");
    }

    private String proof(NodeIdentity signer, String challenge, NodeIdentity enrolled) {
        return Base64.getEncoder().encodeToString(signer.sign(HouseholdJoinRoutes.proofStatement(
            challenge, hubIdentity.nodeId(), enrolled.nodeId(), enrolled.publicKeyBase64(),
            enrolled.x25519PublicKeyBase64())));
    }

    private HouseholdJoinRoutes.JoinRequest provenReq(String key, NodeIdentity peer) {
        var challenge = challengeFor(peer.nodeId());
        return new HouseholdJoinRoutes.JoinRequest(key, nodeOf(peer), challenge, proof(peer, challenge, peer));
    }

    private NodeIdentity newPeer(Path tmp) throws Exception {
        return NodeIdentity.loadOrGenerate(tmp.resolve("peer-" + UUID.randomUUID() + ".json"));
    }

    @Test
    void validKeyEnrollsPeerAndReturnsHubPlusRoster(@TempDir Path tmp) throws Exception {
        var peer = newPeer(tmp);
        var result = routes().handleJoin(provenReq(validKey, peer));

        assertThat(result.status()).isEqualTo(200);
        // Peer is now present in the hub's households table — the borrow gate.
        var row = householdStore.get(peer.nodeId()).orElseThrow();
        assertThat(row.publicKey()).containsExactly(peer.publicKeyBytes());
        // Fingerprint and DID are computed from the key, not taken from the request.
        assertThat(row.fingerprint()).isNotEqualTo("aa:bb:cc:dd").contains(":");
        assertThat(row.didKey()).isNotEqualTo("did:wyrd:z6MkPeer").startsWith("did:");
        assertThat(row.x25519PublicKey()).containsExactly(peer.x25519PublicKeyBytes());

        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) result.body();
        assertThat(body).containsKey("zoneId");
        assertThat(body.get("natsUrl")).isEqualTo("nats://198.51.100.50:4222");

        @SuppressWarnings("unchecked")
        var hub = (Map<String, Object>) body.get("hub");
        assertThat(hub.get("nodeId")).isEqualTo(hubIdentity.nodeId());
        assertThat(hub.get("publicKeyB64")).isEqualTo(hubIdentity.publicKeyBase64());
        assertThat((String) hub.get("fingerprint")).contains(":");
        assertThat((String) hub.get("didKey")).startsWith("did:");

        @SuppressWarnings("unchecked")
        var members = (List<Map<String, Object>>) body.get("members");
        // Roster carries at least the freshly-enrolled peer.
        assertThat(members).anySatisfy(m -> assertThat(m.get("nodeId")).isEqualTo(peer.nodeId()));
    }

    @Test
    void invalidKeyIsRejectedAndPeerNotEnrolled(@TempDir Path tmp) throws Exception {
        var peer = newPeer(tmp);
        var result = routes().handleJoin(provenReq("wyrd_hk_not_a_real_key", peer));

        assertThat(result.status()).isEqualTo(403);
        assertThat(householdStore.get(peer.nodeId())).isEmpty();
    }

    @Test
    void joinWithoutProofIsRefused(@TempDir Path tmp) throws Exception {
        var peer = newPeer(tmp);
        // The household key alone no longer enrols a key: no challenge, no proof.
        var result = routes().handleJoin(new HouseholdJoinRoutes.JoinRequest(validKey, nodeOf(peer), null, null));
        assertThat(result.status()).isEqualTo(403);
        assertThat(householdStore.get(peer.nodeId())).isEmpty();

        // A challenge but no proof.
        var challenge = challengeFor(peer.nodeId());
        result = routes().handleJoin(new HouseholdJoinRoutes.JoinRequest(validKey, nodeOf(peer), challenge, null));
        assertThat(result.status()).isEqualTo(403);
        assertThat(householdStore.get(peer.nodeId())).isEmpty();
    }

    @Test
    void enrollingAKeyYouDoNotHoldIsRefused(@TempDir Path tmp) throws Exception {
        var victim = newPeer(tmp);     // the key being enrolled
        var attacker = newPeer(tmp);   // who actually signs
        var challenge = challengeFor(victim.nodeId());
        var result = routes().handleJoin(new HouseholdJoinRoutes.JoinRequest(
            validKey, nodeOf(victim), challenge, proof(attacker, challenge, victim)));
        assertThat(result.status()).isEqualTo(403);
        assertThat(householdStore.get(victim.nodeId())).isEmpty();
    }

    @Test
    void theJoiningMachineGetsItsBusLoginAndTheCaItPins(@TempDir Path tmp) throws Exception {
        var tls = HouseholdTls.ensure(dataDir);
        pairingService.useHouseholdBus(HouseholdBus.open(dataDir));
        var peer = newPeer(tmp);
        var peerId = peer.nodeId();

        var result = routes().handleJoin(provenReq(validKey + "." + tls.caFingerprint(), peer));

        assertThat(result.status()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) result.body();
        assertThat((String) body.get("nats_user")).isEqualTo("machine-" + peerId);
        assertThat((String) body.get("nats_pass")).hasSizeGreaterThanOrEqualTo(40);
        assertThat(body.get("home_ca_fp")).isEqualTo(tls.caFingerprint());
        assertThat(HouseholdTls.readCertificates((String) body.get("ca_pem")).getFirst()).isEqualTo(tls.ca());
        @SuppressWarnings("unchecked")
        var hub = (Map<String, Object>) body.get("hub");
        assertThat((String) hub.get("natsWsUrl")).startsWith("wss://");
        assertThat(Files.readString(HouseholdBus.open(dataDir).dataDir().resolve("nats").resolve(HouseholdBus.USERS)))
            .contains("machine-" + peerId);
    }

    @Test
    void aChallengeIsSingleUseAndBoundToItsNode(@TempDir Path tmp) throws Exception {
        var peer = newPeer(tmp);
        var other = newPeer(tmp);
        var challenge = challengeFor(peer.nodeId());
        var req = new HouseholdJoinRoutes.JoinRequest(validKey, nodeOf(peer), challenge, proof(peer, challenge, peer));
        assertThat(routes().handleJoin(req).status()).isEqualTo(200);
        // Replaying the same request fails: the challenge is spent.
        assertThat(routes().handleJoin(req).status()).isEqualTo(403);

        // A challenge issued for one node cannot enrol another.
        var forPeer = challengeFor(peer.nodeId());
        var result = routes().handleJoin(new HouseholdJoinRoutes.JoinRequest(
            validKey, nodeOf(other), forPeer, proof(other, forPeer, other)));
        assertThat(result.status()).isEqualTo(403);
        assertThat(householdStore.get(other.nodeId())).isEmpty();
    }

    @Test
    void anExpiredChallengeIsRefused(@TempDir Path tmp) throws Exception {
        var now = new AtomicReference<>(Instant.parse("2026-09-28T12:00:00Z"));
        var clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        routes = new HouseholdJoinRoutes(pairingService, householdStore, hubIdentity, () -> "198.51.100.50", clock);
        var peer = newPeer(tmp);
        var challenge = challengeFor(peer.nodeId());
        now.set(now.get().plus(HouseholdJoinRoutes.CHALLENGE_TTL).plusSeconds(1));
        var result = routes().handleJoin(new HouseholdJoinRoutes.JoinRequest(
            validKey, nodeOf(peer), challenge, proof(peer, challenge, peer)));
        assertThat(result.status()).isEqualTo(403);
        assertThat(householdStore.get(peer.nodeId())).isEmpty();
    }
}
