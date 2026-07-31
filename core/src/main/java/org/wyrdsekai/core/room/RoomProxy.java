package org.wyrdsekai.core.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.RoomSnapshot;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

/**
 * Lightweight proxy actor for rooms owned by a remote primary node.
 * Forwards commands via a transport function (NATS request/reply).
 * Maintains local subscribers and delivers events from the primary.
 *
 * Accepts the same RoomCommand protocol as RoomActor, so callers
 * (sessions, companions) don't know whether they're talking to a
 * local room or a remote proxy.
 *
 * Async responses are handled directly in CompletableFuture callbacks
 * rather than routed back through the actor mailbox, since the only
 * state mutation is the volatile cachedSnapshot field.
 */
public final class RoomProxy extends AbstractBehavior<RoomCommand> {

    private static final Logger log = LoggerFactory.getLogger(RoomProxy.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final String roomId;
    private final BiFunction<String, String, CompletionStage<String>> transport;
    private final List<SubscriberEntry> subscribers = new CopyOnWriteArrayList<>();
    private volatile RoomSnapshot cachedSnapshot;

    private record SubscriberEntry(ActorRef<RoomNotification> ref, String entityId) {}

    private RoomProxy(ActorContext<RoomCommand> context, String roomId,
                      BiFunction<String, String, CompletionStage<String>> transport) {
        super(context);
        this.roomId = roomId;
        this.transport = transport;
        log.info("RoomProxy created for {} (remote primary)", roomId);
    }

    public static Behavior<RoomCommand> create(String roomId,
                                                BiFunction<String, String, CompletionStage<String>> transport) {
        return Behaviors.setup(ctx -> new RoomProxy(ctx, roomId, transport));
    }

    /** Update cached state from primary broadcast. */
    public void updateCachedSnapshot(RoomSnapshot snapshot) {
        this.cachedSnapshot = snapshot;
    }

    /** Deliver a remote event to local subscribers. */
    public void deliverRemoteEvent(WorldEvent event) {
        var notification = new RoomNotification(event);
        for (var sub : subscribers) {
            try {
                sub.ref().tell(notification);
            } catch (Exception e) {
                log.debug("Failed to deliver event to subscriber: {}", e.getMessage());
            }
        }
    }

    @Override
    public Receive<RoomCommand> createReceive() {
        return newReceiveBuilder()
            .onMessage(RoomCommand.Subscribe.class, this::onSubscribe)
            .onMessage(RoomCommand.Unsubscribe.class, this::onUnsubscribe)
            .onMessage(RoomCommand.BroadcastRemoteEvent.class, this::onBroadcastRemoteEvent)
            .onMessage(RoomCommand.LookRoom.class, this::onLookRoom)
            .onMessage(RoomCommand.GetSnapshot.class, this::onGetSnapshot)
            // All other commands: forward to primary
            .onMessage(RoomCommand.EnterRoom.class, cmd -> forward(cmd, cmd.replyTo()))
            .onMessage(RoomCommand.LeaveRoom.class, cmd -> forward(cmd, cmd.replyTo()))
            .onMessage(RoomCommand.SayInRoom.class, cmd -> forward(cmd, cmd.replyTo()))
            .onMessage(RoomCommand.EmoteInRoom.class, cmd -> forward(cmd, cmd.replyTo()))
            .onMessage(RoomCommand.WhisperInRoom.class, cmd -> forward(cmd, cmd.replyTo()))
            .onMessage(RoomCommand.TakeObject.class, cmd -> forward(cmd, cmd.replyTo()))
            .onMessage(RoomCommand.DropObject.class, cmd -> forward(cmd, cmd.replyTo()))
            .onMessage(RoomCommand.UseObject.class, cmd -> forward(cmd, cmd.replyTo()))
            .onMessage(RoomCommand.SelectHint.class, cmd -> forward(cmd, cmd.replyTo()))
            .onMessage(RoomCommand.CreateRoom.class, cmd -> forward(cmd, cmd.replyTo()))
            .onMessage(RoomCommand.AddExit.class, cmd -> forward(cmd, cmd.replyTo()))
            .onMessage(RoomCommand.UpdateHints.class, cmd -> forward(cmd, cmd.replyTo()))
            .onMessage(RoomCommand.Quarantine.class, cmd -> forward(cmd, cmd.replyTo()))
            .onMessage(RoomCommand.Unquarantine.class, cmd -> forward(cmd, cmd.replyTo()))
            .onMessage(RoomCommand.SetBehaviorScript.class, cmd -> forward(cmd, cmd.replyTo()))
            .onMessage(RoomCommand.UpdateEntityDescription.class, cmd -> forward(cmd, cmd.replyTo()))
            // Audit 2026-07-11: these four fell to unhandled — sit/stand timed out,
            // renames vanished, and every scripted-item world effect was silently
            // dropped in remote rooms, despite the class javadoc promising full
            // RoomCommand parity with RoomActor.
            .onMessage(RoomCommand.SetPosture.class, cmd -> forward(cmd, cmd.replyTo()))
            .onMessage(RoomCommand.ClearPosture.class, cmd -> forward(cmd, cmd.replyTo()))
            .onMessage(RoomCommand.UpdateEntityName.class, cmd -> forward(cmd, cmd.replyTo()))
            .onMessage(RoomCommand.ItemBridgeAction.class, this::forwardFireAndForget)
            .build();
    }

    // ── Local handlers ──

    private Behavior<RoomCommand> onSubscribe(RoomCommand.Subscribe cmd) {
        subscribers.add(new SubscriberEntry(cmd.subscriber(), cmd.entityId()));
        log.debug("RoomProxy {}: subscriber added (total: {})", roomId, subscribers.size());
        return this;
    }

    private Behavior<RoomCommand> onUnsubscribe(RoomCommand.Unsubscribe cmd) {
        subscribers.removeIf(e -> e.ref().equals(cmd.subscriber()));
        return this;
    }

    private Behavior<RoomCommand> onBroadcastRemoteEvent(RoomCommand.BroadcastRemoteEvent cmd) {
        deliverRemoteEvent(cmd.event());
        return this;
    }

    private Behavior<RoomCommand> onLookRoom(RoomCommand.LookRoom cmd) {
        if (cachedSnapshot != null) {
            cmd.replyTo().tell(new RoomResponse.Ok(cachedSnapshot));
            return this;
        }
        return forward(cmd, cmd.replyTo());
    }

    private Behavior<RoomCommand> onGetSnapshot(RoomCommand.GetSnapshot cmd) {
        if (cachedSnapshot != null) {
            cmd.replyTo().tell(cachedSnapshot);
        } else {
            // Forward to primary — handle specially since replyTo type differs
            try {
                var json = MAPPER.writeValueAsString(cmd);
                transport.apply(roomId, json)
                    .whenComplete((responseJson, err) -> {
                        if (err == null) {
                            try {
                                var snapshot = MAPPER.readValue(responseJson, RoomSnapshot.class);
                                cachedSnapshot = snapshot;
                                cmd.replyTo().tell(snapshot);
                            } catch (Exception ignored) {}
                        }
                    });
            } catch (Exception ignored) {}
        }
        return this;
    }

    // ── Forwarding ──

    /**
     * Forward a command to the primary node and deliver the response to replyTo.
     * Response handling is in the CompletableFuture callback — no actor mailbox roundtrip needed.
     */
    /** Forward a reply-less command to the primary; failures are logged only. */
    private Behavior<RoomCommand> forwardFireAndForget(RoomCommand cmd) {
        try {
            var json = MAPPER.writeValueAsString(cmd);
            transport.apply(roomId, json).whenComplete((r, err) -> {
                if (err != null) {
                    log.warn("RoomProxy {}: fire-and-forget command failed: {}",
                        roomId, err.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("RoomProxy {}: fire-and-forget serialize failed: {}", roomId, e.getMessage());
        }
        return Behaviors.same();
    }

    private Behavior<RoomCommand> forward(RoomCommand cmd, ActorRef<RoomResponse> replyTo) {
        try {
            var json = MAPPER.writeValueAsString(cmd);
            transport.apply(roomId, json)
                .whenComplete((responseJson, err) -> {
                    if (err != null) {
                        log.warn("RoomProxy {}: command failed: {}", roomId, err.getMessage());
                        replyTo.tell(new RoomResponse.Rejected("unavailable",
                            "Primary node unreachable: " + err.getMessage()));
                    } else {
                        try {
                            var response = MAPPER.readValue(responseJson, RoomResponse.class);
                            replyTo.tell(response);
                            // Update cache from response snapshot
                            if (response instanceof RoomResponse.Ok ok && ok.snapshot() != null) {
                                cachedSnapshot = ok.snapshot();
                            } else if (response instanceof RoomResponse.ObjectTakenOk taken
                                       && taken.snapshot() != null) {
                                cachedSnapshot = taken.snapshot();
                            }
                        } catch (Exception e) {
                            log.warn("RoomProxy {}: bad response: {}", roomId, e.getMessage());
                            replyTo.tell(new RoomResponse.Rejected("proxy_error",
                                "Failed to deserialize response"));
                        }
                    }
                });
        } catch (Exception e) {
            log.warn("RoomProxy {}: serialize failed: {}", roomId, e.getMessage());
            replyTo.tell(new RoomResponse.Rejected("proxy_error",
                "Failed to serialize command: " + e.getMessage()));
        }
        return this;
    }
}
