package org.wyrdsekai.server.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.javalin.router.JavalinDefaultRoutingApi;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.crypto.HouseholdBus;
import org.wyrdsekai.core.crypto.HouseholdTls;
import org.wyrdsekai.core.identity.HouseholdStore;
import org.wyrdsekai.core.naming.HouseholdIdentity;
import org.wyrdsekai.core.persistence.PairingService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
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
 *
 * <p>Proof of possession (2026-09-28). The joiner first asks {@code POST /api/household/join/challenge}
 * for a one-time challenge, then signs {@link #proofStatement} with the node key it is enrolling. A join
 * without a valid proof is refused: before, anyone holding the household key could enrol any public
 * key, including one they did not hold. The fingerprint and DID stored for the joiner are computed here
 * from its key, not taken from the request.</p>
 */
public final class HouseholdJoinRoutes {

    private static final Logger log = LoggerFactory.getLogger(HouseholdJoinRoutes.class);

    private final PairingService pairingService;
    private final HouseholdStore householdStore;
    private final NodeIdentity localIdentity;
    private final Supplier<String> lanIpSupplier;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    /** Outstanding challenges: challenge → (node id it was issued for, expiry millis). Single use. */
    private final Map<String, Pending> challenges = new LinkedHashMap<>();

    static final Duration CHALLENGE_TTL = Duration.ofMinutes(2);
    private static final int MAX_OUTSTANDING = 256;

    private record Pending(String nodeId, long expiresAtMs) {}

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
        this(pairingService, householdStore, localIdentity, lanIpSupplier, Clock.systemUTC());
    }

    HouseholdJoinRoutes(PairingService pairingService, HouseholdStore householdStore,
                        NodeIdentity localIdentity, Supplier<String> lanIpSupplier, Clock clock) {
        this.pairingService = pairingService;
        this.householdStore = householdStore;
        this.localIdentity = localIdentity;
        this.lanIpSupplier = lanIpSupplier;
        this.clock = clock;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.post("/api/household/join/challenge", this::handleChallenge);
        app.post("/api/household/join", this::handleJoin);
    }

    /**
     * The bytes a joiner signs with its node key: the challenge, this hub, and the identity being enrolled.
     * Shared with the joining side ({@code wyrd join}).
     */
    public static byte[] proofStatement(String challenge, String hubNodeId, String nodeId,
                                        String publicKeyB64, String x25519PublicKeyB64) {
        return ("wyrd-household-join:v1:" + challenge + ":" + hubNodeId + ":" + nodeId + ":"
            + publicKeyB64 + ":" + (x25519PublicKeyB64 == null ? "" : x25519PublicKeyB64))
            .getBytes(StandardCharsets.UTF_8);
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
        @JsonProperty("node") JoinNode node,
        @JsonProperty("challenge") String challenge,
        @JsonProperty("proof") String proof
    ) {}

    record ChallengeRequest(@JsonProperty("nodeId") String nodeId) {}

    /** Result of the core enrollment logic — decoupled from Javalin for testing. */
    public record JoinResult(int status, Object body) {}

    // --- HTTP handlers ---

    private void handleChallenge(Context ctx) {
        ChallengeRequest req;
        try {
            req = Json.mapper().readValue(ctx.body(), ChallengeRequest.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "Invalid request body"));
            return;
        }
        var result = issueChallenge(req == null ? null : req.nodeId());
        ctx.status(result.status()).json(result.body());
    }

    /** A one-time challenge for {@code nodeId}, valid for {@link #CHALLENGE_TTL}. */
    public JoinResult issueChallenge(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return new JoinResult(400, Map.of("error", "Missing nodeId"));
        }
        var bytes = new byte[32];
        random.nextBytes(bytes);
        var challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long now = clock.millis();
        synchronized (challenges) {
            challenges.values().removeIf(p -> p.expiresAtMs() < now);
            if (challenges.size() >= MAX_OUTSTANDING) {
                return new JoinResult(429, Map.of("error", "Too many pending joins; try again shortly"));
            }
            challenges.put(challenge, new Pending(nodeId, now + CHALLENGE_TTL.toMillis()));
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("challenge", challenge);
        body.put("hubNodeId", localIdentity.nodeId());
        body.put("expiresInSeconds", CHALLENGE_TTL.toSeconds());
        return new JoinResult(200, body);
    }

    /** True when {@code challenge} was issued for {@code nodeId}, is unexpired, and was not used before. */
    private boolean consumeChallenge(String challenge, String nodeId) {
        if (challenge == null) return false;
        Pending p;
        synchronized (challenges) {
            p = challenges.remove(challenge);
        }
        return p != null && p.nodeId().equals(nodeId) && p.expiresAtMs() >= clock.millis();
    }

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

        // Proof of possession: the joiner signed this hub's one-time challenge with the key it enrols.
        if (!consumeChallenge(req.challenge(), node.nodeId())) {
            log.warn("Household join rejected — missing, expired or reused challenge (node {})", node.nodeId());
            return new JoinResult(403, Map.of("error",
                "Ask for a join challenge first (POST /api/household/join/challenge) and sign it"));
        }
        byte[] peerPub;
        byte[] peerX25519;
        try {
            peerPub = Base64.getDecoder().decode(node.publicKeyB64());
            peerX25519 = (node.x25519PublicKeyB64() == null || node.x25519PublicKeyB64().isBlank())
                ? null : Base64.getDecoder().decode(node.x25519PublicKeyB64());
        } catch (IllegalArgumentException e) {
            return new JoinResult(400, Map.of("error", "Unreadable key"));
        }
        boolean proven;
        try {
            proven = req.proof() != null && NodeIdentity.verify(
                proofStatement(req.challenge(), localIdentity.nodeId(), node.nodeId(),
                    node.publicKeyB64(), node.x25519PublicKeyB64()),
                Base64.getDecoder().decode(req.proof()), peerPub);
        } catch (IllegalArgumentException e) {
            proven = false;
        }
        if (!proven) {
            log.warn("Household join rejected — the proof is not signed by the key being enrolled (node {})",
                node.nodeId());
            return new JoinResult(403, Map.of("error", "The proof does not verify against the node's key"));
        }

        // Enroll the joiner into THIS hub's households table, with the fingerprint and DID of its key.
        var didKey = HouseholdIdentity.fromSpkiBytes(peerPub).did();
        householdStore.upsert(node.nodeId(), peerPub, fingerprintOf(peerPub), didKey, peerX25519);
        log.info("Household join accepted — enrolled peer {} ({}) into households",
            node.nodeId(), didKey);

        // Echo the hub identity + current roster so the joiner can mirror the household.
        var lanIp = lanIpSupplier != null ? lanIpSupplier.get() : null;
        if (lanIp == null || lanIp.isBlank()) lanIp = "127.0.0.1";
        var natsUrl = "nats://" + lanIp + ":4222";
        // Mobile joiners need the WebSocket listener (G2, 2026-07-11); TLS since W2.
        var natsWsUrl = (WyrdConfig.get().natsLanPlaintext() ? "ws://" : "wss://") + lanIp + ":4223";

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
        addBusLogin(body, node.nodeId());
        return new JoinResult(200, body);
    }

    /**
     * W2: the joining machine's own login for this home's bus ({@code nats_user}
     * {@code nats_pass}) and the CA the bus's TLS certificate chains to ({@code ca_pem},
     * {@code home_ca_fp}), which the machine pins. Without them it cannot use the bus.
     */
    private void addBusLogin(Map<String, Object> body, String nodeId) {
        var bus = pairingService.householdBus();
        if (bus == null) return;
        try {
            var login = bus.issue(HouseholdBus.Kind.MACHINE, nodeId);
            body.put("nats_user", login.user());
            body.put("nats_pass", login.pass());
            var ca = HouseholdTls.readCa(bus.dataDir());
            if (ca != null) {
                body.put("ca_pem", HouseholdTls.pem("CERTIFICATE", ca.getEncoded()));
                body.put("home_ca_fp", HouseholdTls.fingerprint(ca));
            }
        } catch (Exception e) {
            log.warn("Household join: no bus login for {} ({}); it cannot use this home's bus", nodeId, e.getMessage());
        }
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
