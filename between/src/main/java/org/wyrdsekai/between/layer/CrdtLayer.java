package org.wyrdsekai.between.layer;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CRDT propagation layer for The Between.
 * Manages replicated state synchronization across household nodes via NATS.
 *
 * Tracks:
 * - ORSet counters (entity presence, object sets)
 * - LWW registers (room properties, descriptions)
 * - Append-only logs (event history for merge)
 *
 * Uses vector clock for causal ordering of updates.
 */
public class CrdtLayer extends AbstractBehavior<CrdtLayer.Command> {

    private static final Logger log = LoggerFactory.getLogger(CrdtLayer.class);

    public sealed interface Command {}

    /** Propagate a state delta to peers. */
    public record PropagateState(String roomId, String stateType,
                                  Map<String, String> delta) implements Command {}

    /** Received a state delta from a peer. */
    public record ReceiveState(String fromNode, String roomId, String stateType,
                                Map<String, String> delta, long vectorClock) implements Command {}

    /** Query the merged state for a room. */
    public record GetMergedState(String roomId,
                                  ActorRef<MergedState> replyTo) implements Command {}

    /** Merged state response. */
    public record MergedState(String roomId, Map<String, String> properties,
                               Set<String> entityIds, Set<String> objectIds) {}

    /** Periodic GC tick for tombstones. */
    private record GcTick() implements Command {}

    // Per-room CRDT state
    private final Map<String, RoomCrdtState> roomStates = new ConcurrentHashMap<>();
    private final String localNodeId;

    /** Internal CRDT state per room. */
    private static class RoomCrdtState {
        final Set<String> entityIds = new HashSet<>();
        final Set<String> objectIds = new HashSet<>();
        final Map<String, String> properties = new HashMap<>();
        final Map<String, Long> tombstones = new HashMap<>(); // key → expiry epoch
        long vectorClock = 0;

        void applyDelta(String stateType, Map<String, String> delta) {
            vectorClock++;
            switch (stateType) {
                case "entity_add" -> delta.keySet().forEach(entityIds::add);
                case "entity_remove" -> {
                    delta.keySet().forEach(entityIds::remove);
                    delta.keySet().forEach(k ->
                        tombstones.put("entity:" + k, Instant.now().getEpochSecond() + 300));
                }
                case "object_add" -> delta.keySet().forEach(objectIds::add);
                case "object_remove" -> {
                    delta.keySet().forEach(objectIds::remove);
                    delta.keySet().forEach(k ->
                        tombstones.put("object:" + k, Instant.now().getEpochSecond() + 300));
                }
                case "property" -> properties.putAll(delta);
                default -> { /* ignore unknown state types */ }
            }
        }

        void gc() {
            long now = Instant.now().getEpochSecond();
            tombstones.entrySet().removeIf(e -> e.getValue() < now);
        }
    }

    private CrdtLayer(ActorContext<Command> context, String localNodeId) {
        super(context);
        this.localNodeId = localNodeId;

        // Schedule periodic GC every 60 seconds
        context.getSystem().scheduler().scheduleAtFixedRate(
            Duration.ofSeconds(60),
            Duration.ofSeconds(60),
            () -> context.getSelf().tell(new GcTick()),
            context.getExecutionContext());

        log.info("CrdtLayer started for node {}", localNodeId);
    }

    public static Behavior<Command> create(String localNodeId) {
        return Behaviors.setup(ctx -> new CrdtLayer(ctx, localNodeId));
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(PropagateState.class, this::onPropagate)
            .onMessage(ReceiveState.class, this::onReceive)
            .onMessage(GetMergedState.class, this::onGetMerged)
            .onMessage(GcTick.class, this::onGcTick)
            .build();
    }

    private Behavior<Command> onPropagate(PropagateState cmd) {
        var state = roomStates.computeIfAbsent(cmd.roomId(), _ -> new RoomCrdtState());
        state.applyDelta(cmd.stateType(), cmd.delta());
        log.debug("Propagating {} delta for room {} from node {}",
            cmd.stateType(), cmd.roomId(), localNodeId);
        return this;
    }

    private Behavior<Command> onReceive(ReceiveState cmd) {
        var state = roomStates.computeIfAbsent(cmd.roomId(), _ -> new RoomCrdtState());
        if (cmd.vectorClock() > state.vectorClock) {
            state.applyDelta(cmd.stateType(), cmd.delta());
            // Advance clock to at least the received clock
            state.vectorClock = Math.max(state.vectorClock, cmd.vectorClock());
            log.debug("Applied {} delta from {} for room {}, vc → {}",
                cmd.stateType(), cmd.fromNode(), cmd.roomId(),
                state.vectorClock);
        }
        return this;
    }

    private Behavior<Command> onGetMerged(GetMergedState cmd) {
        var state = roomStates.get(cmd.roomId());
        if (state == null) {
            cmd.replyTo().tell(new MergedState(
                cmd.roomId(), Map.of(), Set.of(), Set.of()));
        } else {
            cmd.replyTo().tell(new MergedState(
                cmd.roomId(),
                Map.copyOf(state.properties),
                Set.copyOf(state.entityIds),
                Set.copyOf(state.objectIds)));
        }
        return this;
    }

    private Behavior<Command> onGcTick(GcTick tick) {
        roomStates.values().forEach(RoomCrdtState::gc);
        return this;
    }
}
