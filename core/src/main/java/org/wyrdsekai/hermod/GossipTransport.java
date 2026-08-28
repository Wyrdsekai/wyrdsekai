package org.wyrdsekai.hermod;

import java.util.function.Consumer;

/**
 * Transport-agnostic gossip: hermod core never binds a wire protocol.
 * The household deployment supplies an adapter (NATS in wyrdsekai's
 * server module); tests supply an in-memory loopback. Extraction ships
 * this interface, not a broker dependency.
 */
public interface GossipTransport {
    void publish(Capability capability);
    void subscribe(Consumer<Capability> onCapability);
}
