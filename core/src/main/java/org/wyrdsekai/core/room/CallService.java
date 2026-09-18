package org.wyrdsekai.core.room;

import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.EntityRegistry;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * {@code call <companion>}: the caller asks a companion to come to where they are. The
 * companion's actor decides (bondholder only, the follow's gates) and answers with one
 * line for the caller. Shared by the ssh shell and the web socket so both say the same
 * thing.
 */
public final class CallService {

    private static final Logger log = LoggerFactory.getLogger(CallService.class);

    private CallService() {}

    /**
     * @param callerId      the id the room knows the caller by
     * @param callerRoomId  the caller's room (nullable; the actor looks it up)
     * @return one line for the caller
     */
    public static String call(String callerId, String callerName, String target,
                              String callerRoomId, Duration timeout) {
        if (callerId == null || callerId.startsWith("anon-")) return "You must be logged in to call someone.";
        if (target == null || target.isBlank()) return "Call whom? Usage: call <companion>";
        var registry = EntityRegistry.get();
        if (registry == null) return "Nobody answers.";
        String agentId = null;
        String agentName = null;
        for (var entityId : registry.allEntities()) {
            if (!registry.isAgent(entityId)) continue;
            var name = registry.nameOf(entityId).orElse(null);
            if (name != null && name.equalsIgnoreCase(target)) { agentId = entityId; agentName = name; break; }
        }
        if (agentId == null) return "There is no companion called " + target + " here.";
        var ref = ZoneGuardian.getCompanionRef(null, agentId);
        if (ref == null) return agentName + " is not awake in this world right now.";
        var scheduler = Rooms.scheduler();
        if (scheduler == null) return agentName + " cannot be reached.";
        final var name = agentName;
        try {
            return AskPattern.<CompanionActor.Command, String>ask(ref,
                    replyTo -> new CompanionActor.CalledBy(callerId, callerName, callerRoomId, replyTo),
                    timeout, scheduler)
                .toCompletableFuture().get(timeout.toMillis() + 500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("call {}: no answer ({})", name, e.toString());
            return name + " did not answer.";
        }
    }
}
