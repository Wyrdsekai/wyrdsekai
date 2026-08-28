package org.wyrdsekai.server.hermod;

import io.nats.client.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.hermod.Mesh;

import java.time.Duration;

/**
 * NATS request/reply binding for hermod doors. Server side answers this
 * device's own door subject; client side knocks on any other device's.
 * Unreachable or silent doors DECLINE (the mesh simply tries the next
 * candidate) — a dead peer must never fail an errand outright.
 */
public final class NatsDoors implements HermodService.RemoteDoors {

    private static final Logger log = LoggerFactory.getLogger(NatsDoors.class);
    private static final Duration KNOCK_TIMEOUT = Duration.ofSeconds(150);

    private final Connection nats;
    private final String scopeId;

    public NatsDoors(Connection nats, String scopeId) {
        this.nats = nats;
        this.scopeId = scopeId;
    }

    /** Answer knocks on this device's own door. */
    public void serve(String deviceId, Mesh.DoorProtocol ownDoor) {
        var subject = DoorWire.doorSubject(scopeId, deviceId);
        var dispatcher = nats.createDispatcher(msg -> {
            var reply = DoorWire.answer(msg.getData(), ownDoor);
            if (msg.getReplyTo() != null) {
                nats.publish(msg.getReplyTo(), reply);
            }
        });
        dispatcher.subscribe(subject);
        log.info("hermod: door open at {}", subject);
    }

    @Override
    public Mesh.DoorProtocol doorTo(String deviceId) {
        return envelope -> {
            try {
                var msg = nats.request(
                    DoorWire.doorSubject(scopeId, deviceId),
                    DoorWire.encodeEnvelope(envelope),
                    KNOCK_TIMEOUT);
                if (msg == null) {
                    return new Mesh.DoorProtocol.Declined(deviceId + " did not answer");
                }
                return DoorWire.decodeAnswer(msg.getData(), envelope.envelopeId());
            } catch (Exception e) {
                return new Mesh.DoorProtocol.Declined(
                    deviceId + " unreachable: " + e.getMessage());
            }
        };
    }
}
