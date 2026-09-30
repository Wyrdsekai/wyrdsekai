package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.BetweenEnvelope;
import org.wyrdsekai.between.NatsBridge;
import org.wyrdsekai.core.identity.HouseholdStore;
import org.wyrdsekai.core.naming.HouseholdIdentity;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Spreads the household roster between machines. A machine learns the roster when it joins, so one that
 * joined earlier never heard of later ones, and signed account replication from a later machine would be
 * refused as a stranger. Each machine now announces its roster (public keys only); a receiver adds the
 * members it lacks, but only from a sender that is itself on its roster, and never changes a key it
 * already holds (that takes a key_rotation signed by the old key).
 */
public final class RosterGossip {

    private static final Logger log = LoggerFactory.getLogger(RosterGossip.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String LAYER = "household";
    static final String TOPIC = "roster";

    private final NatsBridge nats;
    private final String localNodeId;
    private final HouseholdStore roster;

    public RosterGossip(NatsBridge nats, String localNodeId, HouseholdStore roster) {
        this.nats = nats;
        this.localNodeId = localNodeId;
        this.roster = roster;
    }

    public void start() {
        nats.subscribeBroadcast(LAYER, TOPIC, this::onRoster);
        announce();
    }

    /** Broadcast this machine's roster (node ids and public keys). */
    public void announce() {
        var payload = MAPPER.createObjectNode();
        payload.put("type", "roster");
        var members = payload.putArray("members");
        for (var row : roster.all()) {
            var m = members.addObject();
            m.put("nodeId", row.householdId());
            m.put("publicKeyB64", Base64.getEncoder().encodeToString(row.publicKey()));
            if (row.x25519PublicKey() != null) {
                m.put("x25519PublicKeyB64", Base64.getEncoder().encodeToString(row.x25519PublicKey()));
            }
        }
        nats.broadcast(LAYER, TOPIC, payload);
    }

    /** The envelope has passed the bus check; roster changes additionally need a sender on our roster. */
    void onRoster(BetweenEnvelope env) {
        if (roster.get(env.src()).isEmpty()) {
            log.debug("Roster from {} ignored: that machine is not on this household's roster", env.src());
            return;
        }
        for (JsonNode m : env.payload().path("members")) {
            var nodeId = m.path("nodeId").asText("");
            if (nodeId.isBlank() || nodeId.equals(localNodeId)) continue;
            byte[] key;
            byte[] x25519;
            try {
                key = Base64.getDecoder().decode(m.path("publicKeyB64").asText(""));
                var x = m.path("x25519PublicKeyB64").asText("");
                x25519 = x.isBlank() ? null : Base64.getDecoder().decode(x);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (key.length == 0) continue;
            var existing = roster.get(nodeId);
            if (existing.isPresent()) {
                if (!Arrays.equals(existing.get().publicKey(), key)) {
                    log.warn("Roster from {} names node {} with a different key than ours; kept ours", env.src(), nodeId);
                } else if (existing.get().x25519PublicKey() == null && x25519 != null) {
                    roster.upsert(nodeId, key, existing.get().fingerprint(), existing.get().didKey(), x25519);
                }
                continue;
            }
            String did;
            try {
                did = HouseholdIdentity.fromSpkiBytes(key).did();
            } catch (RuntimeException e) {
                continue;
            }
            roster.upsert(nodeId, key, fingerprintOf(key), did, x25519);
            log.info("Household roster: added node {} (announced by {})", nodeId, env.src());
        }
    }

    private static String fingerprintOf(byte[] spki) {
        try {
            return HexFormat.ofDelimiter(":").formatHex(MessageDigest.getInstance("SHA-256").digest(spki));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
