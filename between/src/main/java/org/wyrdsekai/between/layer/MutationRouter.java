package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.BetweenEnvelope;
import org.wyrdsekai.between.NatsBridge;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Routes room mutations through the primary node for consistency.
 *
 * Reads and chat (say, emote, look, enter, leave) are always local — fast.
 * Mutations (take unique item, drop, place, modify room) go through
 * the room's primary node for exactly-once semantics.
 *
 * Each mutation carries:
 * - epoch: fencing token from RoomPrimaryProtocol (rejects stale primaries)
 * - idempotencyKey: UUID (prevents duplicate execution on retry)
 *
 * Usage: callers check isMutation(command) before sending to RoomActor.
 * If mutation + not primary → forward via NATS to primary node.
 * If mutation + primary → execute locally with epoch validation.
 */
public final class MutationRouter {

    private static final Logger log = LoggerFactory.getLogger(MutationRouter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    /** Commands that mutate room state and require primary coordination. */
    private static final Set<String> MUTATION_COMMANDS = Set.of(
        "TakeObject",       // item ownership transfer (unique items)
        "DropObject",       // item placement
        "CreateRoom",       // room structure mutation
        "AddExit",          // room structure mutation
        "UpdateHints",      // room metadata mutation
        "SetBehaviorScript", // room script mutation
        "Quarantine",       // moderation mutation
        "Unquarantine"      // moderation mutation
    );

    /** Commands that are always local — no primary coordination needed. */
    // SayInRoom, EmoteInRoom, WhisperInRoom, LookRoom, EnterRoom, LeaveRoom,
    // Subscribe, Unsubscribe, GetSnapshot, BroadcastRemoteEvent, SelectHint,
    // UseObject (read-only interaction), UpdateEntityDescription

    /**
     * Forwarded mutation request — sent via NATS to the primary node.
     */
    public record ForwardedMutation(
        @JsonProperty("type") String type,
        @JsonProperty("roomId") String roomId,
        @JsonProperty("epoch") long epoch,
        @JsonProperty("idempotencyKey") String idempotencyKey,
        @JsonProperty("command") JsonNode command,
        @JsonProperty("sourceNodeId") String sourceNodeId,
        @JsonProperty("timestamp") Instant timestamp
    ) {
        @JsonCreator public ForwardedMutation {}
    }

    /**
     * Mutation result — sent back from primary to requesting node.
     */
    public record MutationResult(
        @JsonProperty("idempotencyKey") String idempotencyKey,
        @JsonProperty("success") boolean success,
        @JsonProperty("reason") String reason,
        @JsonProperty("epoch") long epoch
    ) {
        @JsonCreator public MutationResult {}
    }

    private final NatsBridge nats;
    private final String localNodeId;
    private final RoomPrimaryProtocol primaryProtocol;

    /**
     * Dedup table: idempotencyKey → result. Prevents duplicate execution.
     *
     * <p>Populated on the primary by {@link #publishResult} AND on every node by the
     * {@code room.mutation/result} broadcast subscription (see {@link #startListening}).
     * The latter is the durable-dedup fix for the handover gap proven in
     * {@code spec/tla/PrimaryFencing.tla} (P3, {@code NoDoubleApply} under
     * {@code DurableDedup=FALSE}): because every standby node records results as they
     * are gossiped, a node that later wins a primary handover already holds the prior
     * dedup entries, so a retry that straddles the failover is recognised as a duplicate
     * instead of being applied a second time.</p>
     */
    private final ConcurrentHashMap<String, MutationResult> dedupTable = new ConcurrentHashMap<>();

    /** Pending mutation handler — set by BetweenActor to process forwarded mutations. */
    private volatile BiConsumer<String, ForwardedMutation> mutationHandler;

    public MutationRouter(NatsBridge nats, String localNodeId,
                           RoomPrimaryProtocol primaryProtocol) {
        this.nats = nats;
        this.localNodeId = localNodeId;
        this.primaryProtocol = primaryProtocol;
    }

    /**
     * Check if a command type requires primary coordination.
     * Clonable item takes are NOT mutations (clone is created locally).
     */
    public static boolean isMutation(String commandType) {
        return MUTATION_COMMANDS.contains(commandType);
    }

    /**
     * Check if a TakeObject command requires primary coordination.
     * Only unique (non-cloneable) items need primary routing.
     */
    public static boolean isTakeUnique(boolean itemCloneable) {
        return !itemCloneable;
    }

    /**
     * Generate an idempotency key for a mutation.
     */
    public static String newIdempotencyKey() {
        return UUID.randomUUID().toString();
    }

    /**
     * Start listening for forwarded mutations on NATS.
     * Called by BetweenActor during startup.
     */
    public void startListening() {
        nats.subscribeBroadcast("room.mutation", "forward", env -> {
            try {
                var mutation = MAPPER.convertValue(env.payload(), ForwardedMutation.class);
                // Only process if we're primary for this room
                if (!primaryProtocol.isPrimary(mutation.roomId())) {
                    log.debug("Ignoring forwarded mutation for {} — not primary", mutation.roomId());
                    return;
                }
                handleForwardedMutation(mutation);
            } catch (Exception e) {
                log.warn("Failed to process forwarded mutation: {}", e.getMessage());
            }
        });

        nats.subscribeBroadcast("room.mutation", "result", env -> recordBroadcastResult(env.payload()));

        log.info("MutationRouter: listening for forwarded mutations");
    }

    /**
     * Set the handler for executing mutations locally (on the primary).
     * The handler receives (roomId, mutation) and should execute the command
     * on the local RoomActor, then call publishResult().
     */
    public void setMutationHandler(
            BiConsumer<String, ForwardedMutation> handler) {
        this.mutationHandler = handler;
    }

    /**
     * Forward a mutation to the primary node via NATS.
     * Called by non-primary nodes when they receive a mutation command.
     */
    public CompletionStage<MutationResult> forwardToPrimary(
            String roomId, String commandType, JsonNode commandData) {
        var epoch = primaryProtocol.getRemoteEpoch(roomId);
        var key = newIdempotencyKey();

        var mutation = new ForwardedMutation(
            commandType, roomId, epoch, key,
            commandData, localNodeId, Instant.now());

        var payload = MAPPER.valueToTree(mutation);
        nats.broadcast("room.mutation", "forward", payload);

        // Wait for result (timeout 5s)
        var future = new CompletableFuture<MutationResult>();
        // Simple polling — in production, use a NATS request/reply inbox
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            var result = dedupTable.get(key);
            if (result != null) {
                future.complete(result);
                scheduler.shutdown();
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
        scheduler.schedule(() -> {
            if (!future.isDone()) {
                future.complete(new MutationResult(key, false, "Primary unreachable (timeout)", epoch));
                scheduler.shutdown();
            }
        }, 5, TimeUnit.SECONDS);

        return future;
    }

    /**
     * Publish a mutation result (called by primary after executing).
     */
    public void publishResult(MutationResult result) {
        dedupTable.put(result.idempotencyKey(), result);
        nats.broadcast("room.mutation", "result", MAPPER.valueToTree(result));
    }

    /**
     * Check if a mutation has already been processed (idempotency check).
     */
    public boolean isDuplicate(String idempotencyKey) {
        return dedupTable.containsKey(idempotencyKey);
    }

    /**
     * Record a mutation result broadcast on {@code room.mutation/result} into the
     * local dedup table — invoked on EVERY node by the result subscription.
     *
     * <p>This is the durable-dedup fix for the handover gap proven in
     * {@code spec/tla/PrimaryFencing.tla} (P3, {@code NoDoubleApply}). Because every
     * standby node records results as they are gossiped, a node that later wins a
     * primary handover already holds the prior idempotency keys, so a retry that
     * straddles the failover is recognised as a duplicate instead of being applied a
     * second time. It also lets the requesting node's {@link #forwardToPrimary} poll
     * resolve from its own table. Package-private + null-tolerant so it is unit
     * testable without a live NATS bridge. Uses {@code putIfAbsent} so a node never
     * overwrites a result it already authored.</p>
     */
    void recordBroadcastResult(JsonNode payload) {
        try {
            var result = MAPPER.convertValue(payload, MutationResult.class);
            if (result != null && result.idempotencyKey() != null) {
                dedupTable.putIfAbsent(result.idempotencyKey(), result);
            }
        } catch (Exception e) {
            log.debug("MutationRouter: ignoring malformed mutation result: {}", e.getMessage());
        }
    }

    // ── Internal ──

    private void handleForwardedMutation(ForwardedMutation mutation) {
        // Fencing check: reject stale epochs
        if (!primaryProtocol.isValidEpoch(mutation.roomId(), mutation.epoch())) {
            log.warn("Rejecting mutation for {} — stale epoch {} (current: {})",
                mutation.roomId(), mutation.epoch(),
                primaryProtocol.getEpoch(mutation.roomId()));
            publishResult(new MutationResult(
                mutation.idempotencyKey(), false,
                "Stale epoch (primary changed)", primaryProtocol.getEpoch(mutation.roomId())));
            return;
        }

        // Idempotency check
        if (isDuplicate(mutation.idempotencyKey())) {
            log.debug("Duplicate mutation {} — returning cached result", mutation.idempotencyKey());
            publishResult(dedupTable.get(mutation.idempotencyKey()));
            return;
        }

        // Execute via handler
        if (mutationHandler != null) {
            mutationHandler.accept(mutation.roomId(), mutation);
        } else {
            publishResult(new MutationResult(
                mutation.idempotencyKey(), false,
                "No mutation handler configured", primaryProtocol.getEpoch(mutation.roomId())));
        }
    }
}
