package org.wyrdsekai.between.layer;

import org.apache.pekko.actor.typed.ActorRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.layer.MutationRouter.ForwardedMutation;
import org.wyrdsekai.between.layer.MutationRouter.MutationResult;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomCommandDispatcher;
import org.wyrdsekai.core.room.RoomRegistry;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Primary-side executor for mutations forwarded through {@link MutationRouter}.
 *
 * <p>Definitive re-audit fix (#33-3): {@link MutationRouter#setMutationHandler}
 * had no production caller, so a mutation that reached the primary fell through
 * to a {@code "No mutation handler configured"} failure. This builds the real
 * handler: resolve the local (real, non-proxy) {@code RoomActor}, apply the
 * serialized command through {@link RoomCommandDispatcher} (the same executor
 * ZoneGuardian uses for cross-node room proxying), and publish a
 * {@link MutationResult} through a sink.</p>
 *
 * <p>The result <em>sink</em> is injected rather than calling
 * {@link MutationRouter#publishResult} directly so the executor is unit-testable
 * without a live NATS bridge (production passes {@code router::publishResult}).</p>
 */
public final class RoomMutationExecutor {

    private static final Logger log = LoggerFactory.getLogger(RoomMutationExecutor.class);

    private RoomMutationExecutor() {}

    /**
     * Production handler: looks rooms up in the global {@link RoomRegistry} and
     * publishes results back through the router's NATS broadcast.
     */
    public static BiConsumer<String, ForwardedMutation> forRegistry(MutationRouter router) {
        return build(roomId -> RoomRegistry.get().ref(roomId), router::publishResult);
    }

    /**
     * Testable core: {@code lookup} resolves a roomId to its local actor ref (or
     * null), {@code resultSink} receives the outcome (production wires this to
     * {@code MutationRouter.publishResult}).
     */
    public static BiConsumer<String, ForwardedMutation> build(
            Function<String, ActorRef<RoomCommand>> lookup,
            Consumer<MutationResult> resultSink) {
        return (roomId, mutation) -> {
            var roomRef = lookup.apply(roomId);
            if (roomRef == null || roomRef.path().name().startsWith("room-proxy-")) {
                log.warn("RoomMutationExecutor: no local primary RoomActor for {} — "
                    + "cannot apply forwarded mutation {} (handover race?)",
                    roomId, mutation.idempotencyKey());
                resultSink.accept(new MutationResult(
                    mutation.idempotencyKey(), false,
                    "Room not resident on this primary", mutation.epoch()));
                return;
            }
            var commandJson = mutation.command() == null ? "{}" : mutation.command().toString();
            RoomCommandDispatcher.dispatch(roomRef, commandJson)
                .whenComplete((respJson, err) -> {
                    boolean ok = err == null && respJson != null
                        && !respJson.contains("\"rejected\"");
                    String reason = err != null ? err.getMessage()
                        : (ok ? null : respJson);
                    resultSink.accept(new MutationResult(
                        mutation.idempotencyKey(), ok, reason, mutation.epoch()));
                });
        };
    }
}
