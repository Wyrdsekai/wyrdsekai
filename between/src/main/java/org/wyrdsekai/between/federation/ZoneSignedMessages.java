package org.wyrdsekai.between.federation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.BetweenEnvelope;
import org.wyrdsekai.between.EnvelopeGuard;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.core.naming.EnvelopeVerificationMode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Signs and checks the plain-JSON messages zones send each other outside the gate protocol (cross-zone
 * tells, familiar copies, room peeks). Before 2026-09-28 these were raw JSON anyone on the relay could
 * send; a tell's self-asserted {@code fromZone} naming the local zone even passed as a tell from inside
 * the household, and a peek returned any room's snapshot to anyone who asked. Now the message travels inside an envelope
 * signed by the sending node and naming its zone; the receiver accepts it only when that zone holds an
 * active agreement with it and the key pinned for the zone signed it, within the timestamp window and
 * once.
 */
public final class ZoneSignedMessages {

    private static final Logger log = LoggerFactory.getLogger(ZoneSignedMessages.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Opened(String zoneId, JsonNode message) {}

    private final FederationService service;
    private final String localZoneId;
    private final NodeIdentity identity;
    private final String channel;
    /** Timestamp window and replay cache only: the zone key is checked here, against the agreement. */
    private final EnvelopeGuard freshness;

    public ZoneSignedMessages(FederationService service, String localZoneId, NodeIdentity identity, String channel) {
        this(service, localZoneId, identity, channel, Clock.systemUTC());
    }

    ZoneSignedMessages(FederationService service, String localZoneId, NodeIdentity identity, String channel,
                       Clock clock) {
        this.service = service;
        this.localZoneId = localZoneId;
        this.identity = identity;
        this.channel = channel;
        this.freshness = new EnvelopeGuard(localZoneId, null, null, null, null,
            EnvelopeVerificationMode.HARD, EnvelopeGuard.DEFAULT_MAX_SKEW, clock);
    }

    /** The message wrapped in an envelope this node signs; null when this node has no identity to sign with. */
    public byte[] seal(byte[] json) {
        if (identity == null) {
            log.warn("Federation: cannot sign a cross-zone {} (no node identity); not sent", channel);
            return null;
        }
        try {
            var payload = MAPPER.createObjectNode();
            payload.put("senderZone", localZoneId);
            payload.set("message", MAPPER.readTree(json));
            return BetweenEnvelope.create(identity.nodeId(), null, payload, identity).toBytes();
        } catch (Exception e) {
            log.warn("Federation: could not sign a cross-zone {}: {}", channel, e.getMessage());
            return null;
        }
    }

    /** A publisher that signs each message before handing it to {@code raw}. */
    public BiConsumer<String, byte[]> publisher(BiConsumer<String, byte[]> raw) {
        return (subject, json) -> {
            var sealed = seal(json);
            if (sealed != null) raw.accept(subject, sealed);
        };
    }

    /** A handler that passes on only messages {@link #open} accepts, as their plain JSON. */
    public Consumer<byte[]> handler(Consumer<byte[]> inner) {
        return data -> open(data).ifPresent(o -> inner.accept(o.message().toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** A subscriber whose handlers receive only messages {@link #open} accepts. */
    public BiConsumer<String, Consumer<byte[]>> subscriber(BiConsumer<String, Consumer<byte[]>> raw) {
        return (subject, inner) -> raw.accept(subject, handler(inner));
    }

    /** The zone that verifiably sent it and the message; empty (with a WARN) otherwise. */
    public Optional<Opened> open(byte[] data) {
        BetweenEnvelope env;
        try {
            var root = MAPPER.readTree(data);
            if (!root.has("sig") || !root.has("payload")) return refuse("?", "unsigned");
            env = BetweenEnvelope.fromBytes(data);
        } catch (Exception e) {
            return refuse("?", "unreadable");
        }
        var zone = env.payload().path("senderZone").asText("");
        if (zone.isBlank()) return refuse("?", "names no sender zone");
        if (zone.equals(localZoneId)) return refuse(zone, "claims to come from this zone");
        if (!freshness.admit("federation." + localZoneId + "." + channel, channel, env)) return Optional.empty();
        var key = service == null ? Optional.<byte[]>empty() : service.activeZoneKey(localZoneId, zone);
        if (key.isEmpty()) return refuse(zone, "no active agreement with a pinned key for this zone");
        boolean ok;
        try {
            ok = env.verify(key.get());
        } catch (RuntimeException e) {
            ok = false;
        }
        if (!ok) return refuse(zone, "not signed by the key pinned for this zone");
        return Optional.of(new Opened(zone, env.payload().path("message")));
    }

    private Optional<Opened> refuse(String zone, String why) {
        log.warn("Federation: DROPPED a cross-zone {} from '{}': {}", channel, zone, why);
        return Optional.empty();
    }
}
