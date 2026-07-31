package org.wyrdsekai.server.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.javalin.router.JavalinDefaultRoutingApi;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.identity.HouseholdStore;
import org.wyrdsekai.core.naming.HouseholdIdentity;
import org.wyrdsekai.core.persistence.PairingService;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * the "auto add to home zone" enrollment endpoint.
 *
 * <p>Closes the gap where the household GPU-borrow only fires for peers already
 * present in the local {@code households} table, yet nothing enrolled a PEER.
 * The hub here accepts a pre-shared {@link PairingService household key} (the
 * SAME trust primitive {@code POST /api/pair/key} validates) and, on a valid
 * key, mirrors the joining node's public identity into THIS hub's
 * {@link HouseholdStore}. It then echoes the hub's own identity + the current
 * roster so the joiner can mirror the household back on its side.</p>
 *
 * <p>Scoped strictly to the household trust boundary — no federation/public
 * surface is touched. A node enrolled here is exempted from inference quota
 * exactly like the local node (see {@code NatsInferenceServer.checkQuota}).</p>
 */
public final class HouseholdJoinRoutes {

    private static final Logger log = LoggerFactory.getLogger(HouseholdJoinRoutes.class);

    private final PairingService pairingService;
    private final HouseholdStore householdStore;
    private final NodeIdentity localIdentity;
    private final Supplier<String> lanIpSupplier;

    /**
     * @param pairingService validates the presented household key (reuses the
     *                       {@code /api/pair/key} predicate)
     * @param householdStore THIS hub's households table — the joiner is upserted here
     * @param localIdentity  the server's loaded node identity (the hub identity
     *                       echoed back so the joiner can mirror it)
     * @param lanIpSupplier  resolves the hub's LAN IP for the advertised natsUrl
     */
    public HouseholdJoinRoutes(PairingService pairingService, HouseholdStore householdStore,
                               NodeIdentity localIdentity, Supplier<String> lanIpSupplier) {
        this.pairingService = pairingService;
        this.householdStore = householdStore;
        this.localIdentity = localIdentity;
        this.lanIpSupplier = lanIpSupplier;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.post("/api/household/join", this::handleJoin);
    }

    // --- Request records ---

    record JoinNode(
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("publicKeyB64") String publicKeyB64,
        @JsonProperty("fingerprint") String fingerprint,
        @JsonProperty("didKey") String didKey,
        @JsonProperty("x25519PublicKeyB64") String x25519PublicKeyB64
    ) {}

    record JoinRequest(
        @JsonProperty("householdKey") String householdKey,
        @JsonProperty("node") JoinNode node
    ) {}

    /** Result of the core enrollment logic — decoupled from Javalin for testing. */
    public record JoinResult(int status, Object body) {}

    // --- HTTP handler ---

    private void handleJoin(Context ctx) {
        JoinRequest req;
        try {
            req = Json.mapper().readValue(ctx.body(), JoinRequest.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid request body"));
            return;
        }
        var result = handleJoin(req);
        ctx.status(result.status()).json(result.body());
    }

    /**
     * Core enrollment logic, exposed for direct unit testing. Validates the
     * household key, upserts the joining peer into this hub's households table,
     * and builds the hub-identity + roster response.
     */
    public JoinResult handleJoin(JoinRequest req) {
        if (req == null || req.node() == null) {
            return new JoinResult(400, Map.of("error", "Missing node identity"));
        }
        var node = req.node();
        if (node.nodeId() == null || node.nodeId().isBlank()
            || node.publicKeyB64() == null || node.publicKeyB64().isBlank()) {
            return new JoinResult(400, Map.of("error", "Missing nodeId or publicKeyB64"));
        }
        // Validate the pre-shared household key — the SAME predicate /api/pair/key uses.
        if (!pairingService.validateHouseholdKey(req.householdKey())) {
            log.warn("Household join rejected — invalid or revoked household key (node {})",
                node.nodeId());
            return new JoinResult(403, Map.of("error", "Invalid or revoked household key"));
        }

        // Enroll the joiner into THIS hub's households table.
        byte[] peerPub = Base64.getDecoder().decode(node.publicKeyB64());
        byte[] peerX25519 = (node.x25519PublicKeyB64() == null || node.x25519PublicKeyB64().isBlank())
            ? null : Base64.getDecoder().decode(node.x25519PublicKeyB64());
        householdStore.upsert(node.nodeId(), peerPub, node.fingerprint(), node.didKey(), peerX25519);
        log.info("Household join accepted — enrolled peer {} ({}) into households",
            node.nodeId(), node.didKey());

        // Echo the hub identity + current roster so the joiner can mirror the household.
        var lanIp = lanIpSupplier != null ? lanIpSupplier.get() : null;
        if (lanIp == null || lanIp.isBlank()) lanIp = "127.0.0.1";
        var natsUrl = "nats://" + lanIp + ":4222";
        // Mobile joiners need the WebSocket listener (G2, 2026-07-11).
        var natsWsUrl = "ws://" + lanIp + ":4223";

        var hub = new LinkedHashMap<String, Object>();
        hub.put("nodeId", localIdentity.nodeId());
        hub.put("publicKeyB64", localIdentity.publicKeyBase64());
        hub.put("fingerprint", fingerprintOf(localIdentity.publicKeyBytes()));
        hub.put("didKey", HouseholdIdentity.fromSpkiBytes(localIdentity.publicKeyBytes()).did());
        hub.put("x25519PublicKeyB64", localIdentity.x25519PublicKeyBase64());
        hub.put("natsWsUrl", natsWsUrl);

        List<Map<String, Object>> members = new ArrayList<>();
        for (var row : householdStore.all()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("nodeId", row.householdId());
            m.put("publicKeyB64", row.publicKey() == null ? null
                : Base64.getEncoder().encodeToString(row.publicKey()));
            m.put("fingerprint", row.fingerprint());
            m.put("didKey", row.didKey());
            m.put("x25519PublicKeyB64", row.x25519PublicKey() == null ? null
                : Base64.getEncoder().encodeToString(row.x25519PublicKey()));
            members.add(m);
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("zoneId", WyrdConfig.get().zoneId());
        body.put("natsUrl", natsUrl);
        body.put("hub", hub);
        body.put("members", members);
        return new JoinResult(200, body);
    }

    /** SHA-256 of the SPKI bytes as colon-separated lowercase hex — matches Main's mirror. */
    static String fingerprintOf(byte[] spkiBytes) {
        try {
            var sha256 = MessageDigest.getInstance("SHA-256").digest(spkiBytes);
            var hex = new StringBuilder();
            for (int i = 0; i < sha256.length; i++) {
                if (i > 0) hex.append(':');
                hex.append(String.format("%02x", sha256[i] & 0xff));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}
