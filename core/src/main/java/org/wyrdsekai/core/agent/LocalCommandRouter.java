package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.protocol.S2CMessage;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Process-wide concrete {@link CommandRouter}. Holds a map of
 * {@link NamespaceHandler}s keyed by namespace and dispatches
 * {@code <namespace>.<verb>} commands to whichever handler owns the
 * namespace.
 *
 * <p>. Single source of routing for
 * three entrypoints:</p>
 * <ul>
 *   <li>Player WebSocket — {@link
 *     org.wyrdsekai.common.protocol.C2SMessage.Command} payloads.</li>
 *   <li>Agent action — {@link
 *     org.wyrdsekai.core.agent.ActionParser.AgentAction.ZoneCommand} via
 *     {@link org.wyrdsekai.core.agent.CompanionActor#handleZoneCommand}.</li>
 *   <li>Room script — {@code world.zoneCommand(name, payload)} via
 *     {@link org.wyrdsekai.scripting.api.WorldApi}.</li>
 * </ul>
 *
 * <p>Each entrypoint hands the router an {@code entityId} (DID or
 * session ID), the full {@code namespace.verb} string, args, and a
 * payload. The router splits, looks up the handler, delegates. No
 * permission check happens here — the {@link
 * CommandRouter#executeWithPermissions} default wraps this with the
 * household policy gate when callers want it. Direct {@link #execute}
 * calls bypass the gate (e.g. system-level invocations).</p>
 *
 * <p>Registration is open: any module can call {@link #register} during
 * boot. Last writer wins (re-registering replaces the prior handler);
 * {@link #unregister} is idempotent. Both operations are
 * thread-safe.</p>
 */
public final class LocalCommandRouter implements CommandRouter {

    private static final Logger log = LoggerFactory.getLogger(LocalCommandRouter.class);

    private static volatile LocalCommandRouter INSTANCE;

    private final Map<String, NamespaceHandler> handlers = new ConcurrentHashMap<>();

    /**
     * Return the process-wide singleton, creating it on first call.
     * Idempotent and thread-safe. {@link
     * org.wyrdsekai.core.bootstrap.CoreServices} normally drives the
     * first construction, but tests and re-init paths land here too.
     */
    public static LocalCommandRouter get() {
        var local = INSTANCE;
        if (local != null) return local;
        synchronized (LocalCommandRouter.class) {
            if (INSTANCE == null) INSTANCE = new LocalCommandRouter();
            return INSTANCE;
        }
    }

    /** Test seam — drop the singleton. Production code never calls this. */
    public static synchronized void resetForTest() {
        if (INSTANCE != null) INSTANCE.handlers.clear();
        INSTANCE = null;
    }

    /**
     * Register a handler for a namespace. The namespace is the
     * dot-prefix in command strings — {@code "openhands"} for
     * {@code "openhands.create"}, etc.
     *
     * <p>Re-registering the same namespace replaces the prior handler
     * and logs an INFO line — useful for hot-reload of zone bridges.</p>
     *
     * @throws IllegalArgumentException when {@code namespace} is null,
     *     blank, or contains a dot (the dot is the namespace/verb
     *     separator).
     * @throws NullPointerException when {@code handler} is null.
     */
    public void register(String namespace, NamespaceHandler handler) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must be non-blank");
        }
        if (namespace.indexOf('.') >= 0) {
            throw new IllegalArgumentException(
                "namespace must not contain a dot (got '" + namespace + "')");
        }
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        var prior = handlers.put(namespace, handler);
        if (prior != null) {
            log.info("LocalCommandRouter: replaced handler for namespace '{}' "
                + "(prior={}, new={})", namespace,
                prior.getClass().getSimpleName(), handler.getClass().getSimpleName());
        } else {
            log.info("LocalCommandRouter: registered handler for namespace '{}' ({})",
                namespace, handler.getClass().getSimpleName());
        }
    }

    /**
     * Drop the handler for a namespace. No-op if no handler is
     * registered. After this call, commands under that namespace will
     * surface {@code unknown_namespace} errors until a new handler is
     * registered.
     */
    public void unregister(String namespace) {
        if (namespace == null) return;
        var prior = handlers.remove(namespace);
        if (prior != null) {
            log.info("LocalCommandRouter: unregistered handler for namespace '{}'", namespace);
        }
    }

    @Override
    public boolean execute(String entityId, String command, List<String> args,
                           Map<String, String> payload, Consumer<S2CMessage> respond) {
        if (command == null || command.isBlank()) {
            respond.accept(error("malformed_command", "command must be non-blank"));
            return false;
        }
        var dot = command.indexOf('.');
        if (dot <= 0 || dot >= command.length() - 1) {
            respond.accept(error("malformed_command",
                "command must be of the form '<namespace>.<verb>' (got '" + command + "')"));
            return false;
        }
        var namespace = command.substring(0, dot);
        var verb = command.substring(dot + 1);
        var handler = handlers.get(namespace);
        if (handler == null) {
            respond.accept(error("unknown_namespace",
                "no handler registered for '" + namespace + "' (available: "
                    + String.join(", ", handlers.keySet()) + ")"));
            return false;
        }
        try {
            handler.dispatch(entityId,
                verb,
                args == null ? List.of() : args,
                payload == null ? Map.of() : payload,
                respond);
            return true;
        } catch (Exception e) {
            log.warn("LocalCommandRouter: handler for '{}' threw on verb '{}': {}",
                namespace, verb, e.toString());
            respond.accept(error("handler_failure",
                namespace + "." + verb + " failed: " + e.getMessage()));
            return false;
        }
    }

    @Override
    public Set<String> availableNamespaces() {
        return Set.copyOf(handlers.keySet());
    }

    /** True iff a handler is registered for the namespace. */
    public boolean hasHandler(String namespace) {
        return namespace != null && handlers.containsKey(namespace);
    }

    private static S2CMessage.Error error(String code, String message) {
        return new S2CMessage.Error(0, code, message, null);
    }
}
