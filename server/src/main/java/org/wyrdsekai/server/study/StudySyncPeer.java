package org.wyrdsekai.server.study;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.identity.StudyOwnerGuard;
import org.wyrdsekai.core.library.StudyService;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.PairingService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side Study-sync peer — the home-zone
 * twin of the clients' {@code StudySyncLayer}. Makes the server a first-class
 * CRDT peer so a phone's local Study and the server's Study actually converge
 * (both authoritative), instead of the phone only seeing the zone live-rendered
 * over the tunnel.
 *
 * <p>Wire contract mirrors the clients exactly — <b>raw JSON</b> (NOT the
 * {@code NatsBridge} {@code BetweenEnvelope}), camelCase {@code StudySyncMessage}
 * with a {@code userDid} scoping field, over subjects:</p>
 * <pre>
 *   between.{household}.{src}.{dst}.study.state   — clock-summary advertisement
 *   between.{household}.{src}.{dst}.study.sync    — directed delta request/response
 * </pre>
 *
 * <p>We subscribe with a WILDCARD household token and reply using whatever token
 * the message arrived on, so we're agnostic to the phone's household/zone label
 * (the relay forwards {@code between.{zone}.>}; the phone must publish under a
 * token the relay forwards — see the client wiring). Because a study_state
 * carries the peer's clock summary, we answer it by PUSHING the delta the peer
 * is missing AND (if the peer is ahead) requesting theirs — convergence in the
 * fewest round-trips. Each message names its owner ({@code userDid}); items
 * merge into that user's Study.</p>
 */
public final class StudySyncPeer {

    private static final Logger log = LoggerFactory.getLogger(StudySyncPeer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Connection nats;
    private final String zoneId;          // for logging / context
    private final String serverDeviceId;  // vector-clock slot key + subject dst token
    private final StudyService study;
    // Auth: every inbound message must carry a token proving it speaks for its
    // userDid — otherwise any holder of the shared phone NATS creds could read or
    // WRITE any user's Study (incl. journal_private). Session token (mcp.login)
    // or device pairing token both count; both are nullable for tests.
    private final AuthService auth;
    private final PairingService pairing;
    private Dispatcher dispatcher;

    public StudySyncPeer(Connection nats, String zoneId, String serverDeviceId, StudyService study,
                         AuthService auth, PairingService pairing) {
        this.nats = nats;
        this.zoneId = zoneId;
        this.serverDeviceId = serverDeviceId;
        this.study = study;
        this.auth = auth;
        this.pairing = pairing;
    }

    /** Subscribe to the study-sync subjects and mark our clock slot on the store. */
    public void start() {
        study.setServerDeviceId(serverDeviceId);
        dispatcher = nats.createDispatcher(this::onMessage);
        // Audit F6 (pre-OSS): scope the subscribe to OUR zone's first token, not a
        // wildcard `between.*.*.*`. Phones publish study frames on
        // `between.{zoneId}.{src}.{dst}.study.*` (StudySyncLayer keys on config.zoneId),
        // and their per-household relay account is ACL-scoped to exactly
        // `between.{zoneId}.*.*.study.{state,sync}` — so a zone-scoped subscribe
        // still catches everything a phone can legally send, while a `*` first token
        // would (a) demand a relay-wide `between.>` subscribe grant and (b) let this
        // zone's server observe OTHER households' study clock-summary metadata on a
        // shared relay. On the local-NATS leg the value is identical (no ACL either way).
        dispatcher.subscribe("between." + zoneId + ".*.*.study.state");
        dispatcher.subscribe("between." + zoneId + ".*." + serverDeviceId + ".study.sync");
        log.info("[StudySync] peer up (zone={}, device={}) — subscribed {}.study.state + directed study.sync",
            zoneId, serverDeviceId, "between." + zoneId + ".*.*");
    }

    public void stop() {
        if (dispatcher != null) {
            try { nats.closeDispatcher(dispatcher); } catch (Exception ignored) { /* best effort */ }
            dispatcher = null;
        }
    }

    private void onMessage(Message msg) {
        try {
            // between.{hh}.{src}.{dst}.study.{state|sync}
            var parts = msg.getSubject().split("\\.");
            if (parts.length < 6) return;
            String household = parts[1];
            String src = parts[2];
            if (serverDeviceId.equals(src)) return;   // ignore our own traffic

            var body = MAPPER.readTree(msg.getData());
            String type = body.path("type").asText("");
            String userDid = body.path("userDid").asText("");
            if (userDid.isEmpty()) return;   // unscoped — can't route to a user's Study
            if (!authenticates(body.path("token").asText(""), userDid)) {
                log.debug("[StudySync] dropped unauthenticated {} for {} (src={})",
                    type, userDid, src);
                return;
            }

            // QUARANTINE unresolvable owners rather than merging them. Without
            // this, one un-upgraded device still holding a placeholder identity
            // (the mobile 'local-user' default) pushes its items back after the
            // household has migrated, and the split silently regrows — undoing
            // the migration for everyone.
            // No-op until person provisioning is enabled.
            if (!StudyOwnerGuard.isAcceptable(userDid)) {
                log.warn("[StudySync] QUARANTINED {} from {} — owner '{}' does not resolve "
                    + "to a person on this node; not merging", type, src, userDid);
                return;
            }

            switch (type) {
                case "study_state" -> handleState(household, src, userDid, body);
                case "study_delta_request" -> handleDeltaRequest(household, src, userDid, body);
                case "study_delta" -> handleDelta(userDid, body);
                default -> { /* unknown type — ignore */ }
            }
        } catch (Exception e) {
            log.debug("[StudySync] dropped malformed message on {}: {}", msg.getSubject(), e.toString());
        }
    }

    /**
     * A peer advertised its clock summary. Push what it's missing from us, and if
     * it's ahead of us on any slot, request its newer items too.
     */
    private void handleState(String household, String src, String userDid, JsonNode body) {
        var peerSummary = parseClock(body.get("clockSummary"));
        var delta = study.getDeltaForPeer(userDid, peerSummary);
        if (!delta.isEmpty()) publishDelta(household, src, userDid, delta);

        var ourSummary = study.buildClockSummary(userDid);
        if (peerAhead(peerSummary, ourSummary)) publishDeltaRequest(household, src, userDid, ourSummary);
    }

    /** A peer asked for items newer than its clock — send them. */
    private void handleDeltaRequest(String household, String src, String userDid, JsonNode body) {
        var peerSummary = parseClock(body.get("clockSummary"));
        var delta = study.getDeltaForPeer(userDid, peerSummary);
        if (!delta.isEmpty()) publishDelta(household, src, userDid, delta);
    }

    /** A peer sent items — merge with vector-clock CRDT rules. */
    private void handleDelta(String userDid, JsonNode body) {
        var items = parseItems(body.get("items"));
        if (!items.isEmpty()) study.mergeFromPeer(userDid, items);
    }

    // --- publish ---

    private void publishDelta(String household, String dst, String userDid,
                              List<StudyService.StudyMergeItem> items) {
        try {
            var out = MAPPER.createObjectNode();
            out.put("type", "study_delta");
            out.put("deviceId", serverDeviceId);
            out.put("userDid", userDid);
            out.set("items", MAPPER.valueToTree(items));
            nats.publish(syncSubject(household, dst), MAPPER.writeValueAsBytes(out));
            log.debug("[StudySync] pushed {} item(s) to {} for {}", items.size(), dst, userDid);
        } catch (Exception e) {
            log.debug("[StudySync] publishDelta failed: {}", e.toString());
        }
    }

    private void publishDeltaRequest(String household, String dst, String userDid,
                                     Map<String, Integer> ourSummary) {
        try {
            var out = MAPPER.createObjectNode();
            out.put("type", "study_delta_request");
            out.put("deviceId", serverDeviceId);
            out.put("userDid", userDid);
            out.set("clockSummary", MAPPER.valueToTree(ourSummary));
            nats.publish(syncSubject(household, dst), MAPPER.writeValueAsBytes(out));
        } catch (Exception e) {
            log.debug("[StudySync] publishDeltaRequest failed: {}", e.toString());
        }
    }

    private String syncSubject(String household, String dst) {
        return "between." + household + "." + serverDeviceId + "." + dst + ".study.sync";
    }

    // --- helpers ---

    /**
     * True iff {@code token} proves the sender speaks for {@code userDid}: a live
     * session (mcp.login) or a device pairing token bound to that user. With no
     * auth backends configured (unit tests) this is permissive; in the server
     * they are always wired, so an empty/foreign token drops the message.
     */
    private boolean authenticates(String token, String userDid) {
        if (auth == null && pairing == null) return true;   // test rigs only
        if (token == null || token.isBlank()) return false;
        if (auth != null) {
            var user = auth.validateSession(token);
            if (user.isPresent() && userDid.equals(user.get().id())) return true;
        }
        if (pairing != null) {
            var deviceUser = pairing.findUserForDevice(token);
            if (deviceUser.isPresent() && userDid.equals(deviceUser.get())) return true;
        }
        return false;
    }

    private static boolean peerAhead(Map<String, Integer> peer, Map<String, Integer> ours) {
        for (var e : peer.entrySet()) {
            if (e.getValue() != null && e.getValue() > ours.getOrDefault(e.getKey(), 0)) return true;
        }
        return false;
    }

    private static Map<String, Integer> parseClock(JsonNode node) {
        var m = new HashMap<String, Integer>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asInt()));
        }
        return m;
    }

    // Package-visible for the wire-contract test.
    static List<StudyService.StudyMergeItem> parseItems(JsonNode itemsNode) {
        var out = new ArrayList<StudyService.StudyMergeItem>();
        if (itemsNode != null && itemsNode.isArray()) {
            for (var it : itemsNode) {
                try {
                    out.add(MAPPER.treeToValue(it, StudyService.StudyMergeItem.class));
                } catch (Exception e) {
                    // skip a single malformed item, keep the rest
                }
            }
        }
        return out;
    }
}
