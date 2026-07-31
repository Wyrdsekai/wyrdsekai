package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.scripting.api.ZoneCommandDispatcher;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Adapter from the scripting-module {@link ZoneCommandDispatcher}
 * interface to the core-module {@link LocalCommandRouter}. Lives in
 * core so {@code WorldApi} (in scripting) doesn't take a core
 * dependency.
 *
 * <p>. The adapter:</p>
 * <ul>
 *   <li>delegates to the process-wide {@link LocalCommandRouter}</li>
 *   <li>serialises {@link S2CMessage} response envelopes to JSON
 *       strings before invoking the script's response callback (keeps
 *       the scripting module free of protocol classes)</li>
 *   <li>swallows serialisation failures with a debug log; scripts get
 *       {@code null} back rather than an exception</li>
 * </ul>
 */
public final class WorldApiZoneCommandDispatcher implements ZoneCommandDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WorldApiZoneCommandDispatcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LocalCommandRouter router;

    public WorldApiZoneCommandDispatcher(LocalCommandRouter router) {
        this.router = router != null ? router : LocalCommandRouter.get();
    }

    @Override
    public boolean dispatch(String entityId, String command, List<String> args,
                            Map<String, String> payload, Consumer<String> respond) {
        Consumer<S2CMessage> jsonRespond = msg -> {
            try {
                respond.accept(MAPPER.writeValueAsString(msg));
            } catch (Exception e) {
                log.debug("ZoneCommandDispatcher: failed to serialise response: {}",
                    e.getMessage());
            }
        };
        return router.execute(entityId, command, args, payload, jsonRespond);
    }
}
