package org.wyrdsekai.server.ws;

import org.wyrdsekai.common.protocol.S2CMessage;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Handler for namespaced zone commands (§83.7).
 *
 * Zone-type adapters (CodePlane, HomeKit, etc.) implement this interface
 * to receive commands routed by namespace prefix. A command like
 * "codeplane.approve" is split into namespace="codeplane", action="approve".
 *
 * Implementations are registered in WyrdWebSocket via
 * {@link WyrdWebSocket#registerZoneHandler(String, ZoneCommandHandler)}.
 */
@FunctionalInterface
public interface ZoneCommandHandler {

    /**
     * Handle a namespaced command from a client session.
     *
     * @param playerId  The player/agent ID that sent the command
     * @param action    The action part after the namespace dot (e.g. "approve" from "codeplane.approve")
     * @param args      Command arguments
     * @param payload   Structured key-value payload for zone actions
     * @param respond   Callback to send S2CMessage responses back to the client session
     */
    void handle(String playerId, String action, List<String> args,
                Map<String, String> payload, Consumer<S2CMessage> respond);
}
