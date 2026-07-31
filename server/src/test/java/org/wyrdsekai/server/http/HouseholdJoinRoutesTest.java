package org.wyrdsekai.server.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.core.identity.HouseholdStore;
import org.wyrdsekai.core.persistence.PairingService;
import org.wyrdsekai.core.persistence.SqlDialect;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * the "auto add to home zone" enrollment endpoint.
 * Verifies a valid pre-shared household key enrolls a peer into the hub's
 * households table (so the GPU-borrow gate fires) and that an invalid key is
 * rejected without enrolling anyone. Fully offline (no network).
 */
class HouseholdJoinRoutesTest {

    private String jdbcUrl;
    private HouseholdStore householdStore;
    private PairingService pairingService;
    private NodeIdentity hubIdentity;
    private String validKey;

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
    }

    private HouseholdJoinRoutes routes() {
        return new HouseholdJoinRoutes(pairingService, householdStore, hubIdentity,
            () -> "198.51.100.50");
    }

    private HouseholdJoinRoutes.JoinRequest peerReq(String key, String nodeId) {
        var peer = new HouseholdJoinRoutes.JoinNode(
            nodeId,
            Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4, 5}),
            "aa:bb:cc:dd",
            "did:wyrd:z6MkPeer",
            Base64.getEncoder().encodeToString(new byte[]{9, 8, 7, 6}));
        return new HouseholdJoinRoutes.JoinRequest(key, peer);
    }

    @Test
    void validKeyEnrollsPeerAndReturnsHubPlusRoster() {
        var peerId = "peer-" + UUID.randomUUID();
        var result = routes().handleJoin(peerReq(validKey, peerId));

        assertThat(result.status()).isEqualTo(200);
        // Peer is now present in the hub's households table — the borrow gate.
        var row = householdStore.get(peerId).orElseThrow();
        assertThat(row.fingerprint()).isEqualTo("aa:bb:cc:dd");
        assertThat(row.didKey()).isEqualTo("did:wyrd:z6MkPeer");
        assertThat(row.x25519PublicKey()).containsExactly(new byte[]{9, 8, 7, 6});

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
        assertThat(members).anySatisfy(m -> assertThat(m.get("nodeId")).isEqualTo(peerId));
    }

    @Test
    void invalidKeyIsRejectedAndPeerNotEnrolled() {
        var peerId = "peer-" + UUID.randomUUID();
        var result = routes().handleJoin(peerReq("wyrd_hk_not_a_real_key", peerId));

        assertThat(result.status()).isEqualTo(403);
        assertThat(householdStore.get(peerId)).isEmpty();
    }
}
