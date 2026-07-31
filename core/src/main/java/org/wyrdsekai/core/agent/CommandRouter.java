package org.wyrdsekai.core.agent;

import org.wyrdsekai.common.protocol.S2CMessage;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Routes namespaced commands to zone services.
 * <p>
 * Both players (via WebSocket) and agents (via CompanionActor) use this
 * interface to send commands like {@code codeplane.create}, {@code iot.lights},
 * etc. The implementation handles namespace lookup, prefix stripping, and
 * response delivery.
 * <p>
 * Agents interact with zone services exactly like players — same commands,
 * same routing, same responses.
 */
public interface CommandRouter {

    /**
     * Execute a namespaced command.
     *
     * @param entityId  Who is sending (player ID or agent entity ID)
     * @param command   Full command string (e.g. "codeplane.create")
     * @param args      Positional arguments (never null — use List.of())
     * @param payload   Key-value payload (never null — use Map.of())
     * @param respond   Callback for response messages
     * @return true if the command was routed to a handler, false if no handler found
     */
    boolean execute(String entityId, String command, List<String> args,
                    Map<String, String> payload, Consumer<S2CMessage> respond);

    /**
     * Returns the set of currently registered zone namespaces.
     * Agents can check this to know what services are available.
     */
    Set<String> availableNamespaces();

    /**
     * Execute a namespaced command with permission checking.
     * Extracts namespace.action from the command string and checks the
     * agent's permissions before routing. If denied, sends an error
     * response and returns false.
     *
     * @param entityId    Who is sending
     * @param command     Full command string (e.g. "codeplane.create")
     * @param args        Positional arguments
     * @param payload     Key-value payload
     * @param respond     Callback for response messages
     * @param permissions Agent's permission set (nullable — null means no permission check)
     * @return true if the command was routed, false if denied or no handler
     */
    default boolean executeWithPermissions(String entityId, String command, List<String> args,
                                           Map<String, String> payload, Consumer<S2CMessage> respond,
                                           AgentPermissions permissions) {
        if (permissions != null) {
            var dot = command.indexOf('.');
            if (dot > 0 && dot < command.length() - 1) {
                var namespace = command.substring(0, dot);
                var action = command.substring(dot + 1);
                if (!permissions.isAllowed(namespace, action)) {
                    respond.accept(new S2CMessage.Error(0, "permission_denied",
                        "Not authorized for " + command, null));
                    return false;
                }
            }
        }
        return execute(entityId, command, args, payload, respond);
    }
}
