package org.wyrdsekai.core.agent;

import org.wyrdsekai.common.protocol.S2CMessage;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Handler for a single command namespace (e.g. {@code "openhands"},
 * {@code "iot"}). Registered against {@link LocalCommandRouter}; one
 * handler per namespace.
 *
 * <p>The router strips the namespace prefix and passes the bare verb
 * (e.g. {@code "create"}, {@code "run"}, {@code "diff"}) to
 * {@link #dispatch}. Handlers fan out internally — a coding-backend
 * handler maps {@code create} → {@code submitTask}, {@code run} →
 * {@code runArtifact}, etc.</p>
 *
 * <p>The {@code respond} callback delivers acknowledgements + terminal
 * responses to the caller as {@link S2CMessage} envelopes. A handler
 * MAY invoke it multiple times (e.g. one immediate ack, one terminal
 * narration) and MAY complete the underlying work asynchronously.</p>
 *
 * <p>.</p>
 */
public interface NamespaceHandler {

    /**
     * Dispatch a verb under this namespace.
     *
     * @param entityId  who is calling (player or agent DID/id)
     * @param verb      the part of the original command after the dot
     *                  (never null, never blank)
     * @param args      positional args (never null, never modified —
     *                  use {@link List#of()} for empty)
     * @param payload   key-value payload (never null — use {@link Map#of()}
     *                  for empty)
     * @param respond   callback for response envelopes; safe to invoke
     *                  multiple times. The router never calls back into
     *                  the handler from within {@code respond}.
     */
    void dispatch(String entityId, String verb, List<String> args,
                  Map<String, String> payload, Consumer<S2CMessage> respond);
}
