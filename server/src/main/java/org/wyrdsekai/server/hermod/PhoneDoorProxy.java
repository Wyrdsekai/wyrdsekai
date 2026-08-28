package org.wyrdsekai.server.hermod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.hermod.Capability;
import org.wyrdsekai.hermod.GossipTransport;
import org.wyrdsekai.hermod.Mesh;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.List;

/**
 * The zone's stand-in for a paired phone in the hermod mesh. Phones have
 * no NATS: the zone gossips on the phone's behalf (identity stamped from
 * the pairing record, never from the phone's claim) and serves the
 * phone's door subject with a proxy that forwards each knock down the
 * phone's live WebSocket and waits for its answer.
 *
 * Liveness is the connection: no WebSocket → knocks decline immediately,
 * and with no heartbeats the phone's advertisement ages out of every
 * capability table by TTL. Nothing here obliges the phone — it answers
 * each knock with its own admission verdict, like any other device.
 */
public final class PhoneDoorProxy {

    private static final Logger log = LoggerFactory.getLogger(PhoneDoorProxy.class);

    /** One live phone connection; the WS endpoint adapts WsContext to this. */
    public interface PhoneChannel {
        void send(String text);
    }

    /** Serves a device's door subject; NatsDoors::serve in wyrdsekai. */
    public interface DoorServer {
        void serve(String deviceId, Mesh.DoorProtocol door);
    }

    private record Pending(String envelopeId, CompletableFuture<Mesh.DoorProtocol.Outcome> outcome) {
    }

    private final Clock clock;
    private final Duration knockTimeout;
    private final Map<String, PhoneChannel> channels = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Set<String> served = ConcurrentHashMap.newKeySet();

    // Attached when the mesh comes up; null = mesh inert, phones are told so.
    private volatile GossipTransport gossip;
    private volatile DoorServer doors;
    private volatile String scopeId;

    public PhoneDoorProxy(Clock clock) {
        this(clock, Duration.ofSeconds(120));
    }

    public PhoneDoorProxy(Clock clock, Duration knockTimeout) {
        this.clock = clock;
        this.knockTimeout = knockTimeout;
    }

    public void attach(GossipTransport gossip, DoorServer doors, String scopeId) {
        this.gossip = gossip;
        this.doors = doors;
        this.scopeId = scopeId;
        log.info("hermod: phone door proxy attached (scope {})", scopeId);
    }

    public boolean attached() {
        return gossip != null && doors != null;
    }

    public String scopeId() {
        return scopeId;
    }

    /**
     * A phone's channel opened (already authenticated by the endpoint).
     * The SAME device may arrive through different transports over time —
     * LAN WebSocket at home, relay tunnel away — and roaming is exactly
     * this supersede: newest channel wins, identity and door unchanged.
     */
    public void connected(String deviceId, PhoneChannel channel) {
        var previous = channels.put(deviceId, channel);
        if (previous != null) {
            log.info("hermod: phone {} reconnected, superseding its old channel", deviceId);
            // Knocks that rode the OLD leg can't be answered on the new one
            // (answers return on the channel that carried the knock) —
            // decline them now instead of letting them wait out the timeout.
            declinePending(deviceId, "phone changed doors mid-errand");
        }
        // Serve the door subject once per device; the proxy door always
        // looks up the CURRENT channel, so reconnects need no re-serve.
        if (doors != null && served.add(deviceId)) {
            doors.serve(deviceId, proxyDoorFor(deviceId));
        }
        channel.send(PhoneDoorWire.encode(PhoneDoorWire.Hello.of(deviceId, scopeId)));
    }

    /**
     * A phone's channel closed: outstanding knocks decline, ads age out.
     * Channel-identity check: a STALE close (the old leg's teardown racing
     * in after a roam already superseded it) must not rip out the live
     * channel or decline the new leg's knocks.
     */
    public void disconnected(String deviceId, PhoneChannel channel) {
        if (!channels.remove(deviceId, channel)) {
            return; // a newer leg superseded this one — nothing of ours remains
        }
        declinePending(deviceId, "phone disconnected mid-errand");
    }

    private void declinePending(String deviceId, String reason) {
        pending.forEach((knockId, p) -> {
            if (knockId.startsWith(deviceId + "/")) {
                p.outcome().complete(new Mesh.DoorProtocol.Declined(reason));
            }
        });
    }

    /** A text message from the phone: heartbeat or answer. */
    public void message(String deviceId, String json) {
        switch (PhoneDoorWire.typeOf(json)) {
            case "heartbeat" -> heartbeat(deviceId, json);
            case "answer" -> answer(deviceId, json);
            default -> log.debug("hermod: phone {} sent an unknown message type", deviceId);
        }
    }

    private void heartbeat(String deviceId, String json) {
        var transport = gossip;
        if (transport == null) {
            return; // mesh inert — nothing to advertise into
        }
        try {
            var hb = PhoneDoorWire.decodeHeartbeat(json);
            // Identity is stamped HERE, from the authenticated device id —
            // the heartbeat has no identity fields to lie with.
            transport.publish(new Capability(
                deviceId, scopeId, hb.capabilityClass(),
                hb.models() == null ? List.of() : hb.models(),
                hb.residentDataDomains() == null ? List.of() : hb.residentDataDomains(),
                hb.charging(), hb.idle(), hb.loadFactor(), Instant.now(clock)));
        } catch (Exception e) {
            log.debug("hermod: phone {} heartbeat dropped: {}", deviceId, e.getMessage());
        }
    }

    private void answer(String deviceId, String json) {
        try {
            var a = PhoneDoorWire.decodeAnswer(json);
            var p = a.knockId() == null ? null : pending.remove(a.knockId());
            if (p == null) {
                log.debug("hermod: phone {} answered an unknown knock", deviceId);
                return;
            }
            p.outcome().complete(PhoneDoorWire.outcomeOf(a, p.envelopeId()));
        } catch (Exception e) {
            log.debug("hermod: phone {} answer dropped: {}", deviceId, e.getMessage());
        }
    }

    private Mesh.DoorProtocol proxyDoorFor(String deviceId) {
        return envelope -> {
            var channel = channels.get(deviceId);
            if (channel == null) {
                return new Mesh.DoorProtocol.Declined("phone offline");
            }
            var knockId = deviceId + "/" + UUID.randomUUID();
            var outcome = new CompletableFuture<Mesh.DoorProtocol.Outcome>();
            pending.put(knockId, new Pending(envelope.envelopeId(), outcome));
            try {
                channel.send(PhoneDoorWire.encode(PhoneDoorWire.Knock.of(knockId, envelope)));
                return outcome.get(knockTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                return new Mesh.DoorProtocol.Declined("phone did not answer");
            } finally {
                pending.remove(knockId);
            }
        };
    }
}
