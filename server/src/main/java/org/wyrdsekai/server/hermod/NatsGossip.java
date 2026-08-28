package org.wyrdsekai.server.hermod;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import org.wyrdsekai.hermod.Capability;
import org.wyrdsekai.hermod.GossipTransport;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * The household's NATS binding for hermod capability gossip. Subject is
 * household-scoped (same hh-* account isolation as the rest of the
 * mesh traffic). hermod core never sees this class — extraction ships
 * the GossipTransport interface, deployments bring their own wire.
 */
public final class NatsGossip implements GossipTransport {

    public static String subject(String householdId) {
        return "hh." + householdId + ".hermod.capability";
    }

    // ISO-8601 instants and lenient reads: phones (Kotlin) speak this wire
    // too, and devices in one household update at different times.
    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Connection nats;
    private final String subject;

    public NatsGossip(Connection nats, String householdId) {
        this.nats = nats;
        this.subject = subject(householdId);
    }

    @Override
    public void publish(Capability capability) {
        try {
            nats.publish(subject, JSON.writeValueAsBytes(capability));
        } catch (Exception e) {
            throw new IllegalStateException("capability publish failed", e);
        }
    }

    @Override
    public void subscribe(Consumer<Capability> onCapability) {
        Dispatcher d = nats.createDispatcher(msg -> {
            try {
                onCapability.accept(JSON.readValue(msg.getData(), Capability.class));
            } catch (Exception ignored) {
                // a malformed advertisement is dropped, never fatal
            }
        });
        d.subscribe(subject);
    }

    /** Codec exposed for tests: the wire format is plain JSON of the record. */
    static byte[] encode(Capability c) throws Exception { return JSON.writeValueAsBytes(c); }
    static Capability decode(byte[] b) throws Exception { return JSON.readValue(b, Capability.class); }
}
