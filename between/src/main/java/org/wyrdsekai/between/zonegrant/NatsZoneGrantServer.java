package org.wyrdsekai.between.zonegrant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.RelaySessionTransport;

import java.util.Base64;
import java.util.function.Predicate;

/**
 * #1184 — HOLDER side of the multi-node zone-secret grant. Subscribes to
 * {@code federation.zonegrant.{myZone}.request}, trust-gates the requesting NODE, ECIES-wraps the
 * zone master to the requester's X25519 public key via an injected {@link GrantIssuer} (which calls
 * {@code core.crypto.ZoneSecretService.grantTo}), and publishes a single {@link Response}.
 *
 * <p>Granting a zone MASTER is the highest-stakes federation action — it hands a peer the root of
 * the zone's secret argot. So: (a) the requester node MUST be a known household member ({@code
 * trustedNode}); (b) we only respond when THIS node actually holds the master (the issuer returns a
 * null blob otherwise and we stay silent, so a sibling that holds it can answer instead — no
 * spurious DENIED races on the shared zone subject).
 */
public final class NatsZoneGrantServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NatsZoneGrantServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Issues a grant on this (holder) node. Returns the base64 ECIES grant envelope, or {@code null}
     * if this node does not hold the zone master (caller should then stay silent). Throws on a real
     * crypto failure.
     */
    @FunctionalInterface
    public interface GrantIssuer {
        String issue(String zoneId, byte[] requesterX25519Spki) throws Exception;
    }

    private final RelaySessionTransport transport;
    private final String myZone;
    private final String myNodeId;
    private final Predicate<String> trustedNode;
    private final GrantIssuer issuer;
    private volatile Object subscription;

    public NatsZoneGrantServer(RelaySessionTransport transport, String myZone, String myNodeId,
                               Predicate<String> trustedNode, GrantIssuer issuer) {
        this.transport = transport;
        this.myZone = myZone;
        this.myNodeId = myNodeId;
        this.trustedNode = trustedNode;
        this.issuer = issuer;
    }

    /** Begin listening for grant requests addressed to this zone. */
    public void start() {
        if (transport == null || !transport.isConnected()) {
            log.warn("NatsZoneGrantServer for '{}' not started — transport not connected", myZone);
            return;
        }
        subscription = transport.subscribe(
            NatsZoneGrantProtocol.requestSubject(myZone), this::onRequest);
        log.info("NatsZoneGrantServer listening for zone-secret grant requests on '{}' (node {})",
            myZone, myNodeId);
    }

    private void onRequest(byte[] data) {
        NatsZoneGrantProtocol.Request req;
        try {
            req = MAPPER.readValue(data, NatsZoneGrantProtocol.Request.class);
        } catch (Exception e) {
            log.warn("NatsZoneGrantServer '{}' dropped unparseable grant request: {}", myZone, e.toString());
            return;
        }
        if (req.requesterNodeId() != null && req.requesterNodeId().equals(myNodeId)) {
            return;  // our own request echoed back on the shared zone subject — ignore.
        }

        // Trust gate — only a known household node may receive the zone master.
        if (req.requesterNodeId() == null || !trustedNode.test(req.requesterNodeId())) {
            log.info("NatsZoneGrantServer '{}' DENIED grant to untrusted node '{}'",
                myZone, req.requesterNodeId());
            respond(req, new NatsZoneGrantProtocol.Response(req.requestId(), myNodeId, myZone, null,
                "Node '" + req.requesterNodeId() + "' is not a known member of this household/zone."));
            return;
        }

        byte[] reqPub;
        try {
            reqPub = Base64.getDecoder().decode(req.requesterX25519PubBase64());
        } catch (Exception e) {
            respond(req, new NatsZoneGrantProtocol.Response(req.requestId(), myNodeId, myZone, null,
                "Malformed requester X25519 public key."));
            return;
        }

        try {
            String blob = issuer.issue(req.zoneId(), reqPub);
            if (blob == null) {
                // We don't hold the master — stay silent; a sibling holder may answer.
                log.debug("NatsZoneGrantServer '{}' has no master for '{}' — not responding to {}",
                    myZone, req.zoneId(), req.requesterNodeId());
                return;
            }
            log.info("NatsZoneGrantServer '{}' GRANTED zone '{}' master to node '{}'",
                myZone, req.zoneId(), req.requesterNodeId());
            respond(req, new NatsZoneGrantProtocol.Response(req.requestId(), myNodeId, req.zoneId(),
                blob, null));
        } catch (Exception e) {
            log.warn("NatsZoneGrantServer '{}' grant for '{}' threw: {}",
                myZone, req.zoneId(), e.toString());
            respond(req, new NatsZoneGrantProtocol.Response(req.requestId(), myNodeId, myZone, null,
                "Granter failed to wrap the zone master: " + e));
        }
    }

    private void respond(NatsZoneGrantProtocol.Request req, NatsZoneGrantProtocol.Response resp) {
        try {
            transport.publish(NatsZoneGrantProtocol.resultSubject(req.requestId()),
                MAPPER.writeValueAsBytes(resp));
        } catch (Exception e) {
            log.error("NatsZoneGrantServer '{}' failed to publish grant result for {}: {}",
                myZone, req.requestId(), e.toString());
        }
    }

    @Override
    public void close() {
        if (subscription != null && transport != null) {
            transport.closeDispatcherObj(subscription);
            subscription = null;
        }
    }
}
