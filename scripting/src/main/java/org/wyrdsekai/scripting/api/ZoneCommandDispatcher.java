package org.wyrdsekai.scripting.api;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Host-side hook for {@code world.zoneCommand(name, payload)} from room
 * (and item) scripts. Implementations dispatch the command through the
 * process-wide command router and route response envelopes back to the
 * caller as JSON-encoded strings.
 *
 * <p>. The scripting module owns this
 * interface so {@code WorldApi} never depends on core's
 * {@code LocalCommandRouter}; the adapter lives in the core module
 * (see {@code WorldApiZoneCommandDispatcher}).</p>
 */
public interface ZoneCommandDispatcher {

    /**
     * Dispatch a namespaced command (e.g. {@code "openhands.create"}).
     *
     * @param entityId  the calling entity (player or agent) id; never null
     * @param command   full {@code namespace.verb} string
     * @param args      positional arg list (never null; empty allowed)
     * @param payload   string-keyed payload (never null; empty allowed)
     * @param respond   callback receiving JSON-serialised response
     *                  envelopes ({@link
     *                  org.wyrdsekai.common.protocol.S2CMessage} types
     *                  rendered as JSON strings — keeps scripts free of
     *                  protocol-class dependencies). Safe to invoke
     *                  multiple times for ack + terminal frames.
     * @return true if the command was routed to a handler, false if no
     *     handler is registered (in which case {@code respond} has
     *     already been invoked with an error envelope)
     */
    boolean dispatch(String entityId, String command, List<String> args,
                     Map<String, String> payload, Consumer<String> respond);
}
