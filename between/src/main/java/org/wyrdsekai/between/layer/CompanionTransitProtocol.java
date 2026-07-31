package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.BetweenEnvelope;
import org.wyrdsekai.between.NatsBridge;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Companion migration protocol (Wave 3: Companion Placement).
 *
 * Handles the state transfer when a companion migrates from one node to another.
 * Protocol:
 *   1. Source node: companion enters voluntary sleep (ForceEnergy → 0.0)
 *   2. Source node: exports runtime state (working memory, vitality, room, etc.)
 *   3. Source node: publishes transit message to NATS
 *   4. Target node: receives transit message
 *   5. Target node: spawns CompanionActor with imported state
 *   6. Target node: companion wakes in its room
 *
 * Soul manifest is NOT included — it's already replicated via SoulLayer.
 * CfC weights are NOT included — they're checkpointed via ForgeActor.
 * Only runtime volatile state travels with the companion.
 */
public final class CompanionTransitProtocol {

    private static final Logger log = LoggerFactory.getLogger(CompanionTransitProtocol.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    /**
     * Runtime state snapshot for companion migration.
     * Contains only volatile runtime state — soul manifest and CfC weights
     * are handled by SoulLayer and ForgeActor respectively.
     */
    public record TransitState(
        @JsonProperty("entityId") String entityId,
        @JsonProperty("sourceNodeId") String sourceNodeId,
        @JsonProperty("targetNodeId") String targetNodeId,
        @JsonProperty("roomId") String roomId,
        @JsonProperty("workingMemory") List<String> workingMemory,
        @JsonProperty("vitalityJson") String vitalityJson,  // serialized VitalityState
        @JsonProperty("reason") String reason,              // migration, eviction, failover
        @JsonProperty("timestamp") Instant timestamp
    ) {
        @JsonCreator
        public TransitState {}
    }

    /**
     * Listener for incoming transit messages.
     */
    public interface TransitListener {
        /**
         * Called when a companion transit message is received — this node should
         * spawn the companion with the imported state.
         */
        void onTransitArrival(TransitState state);
    }

    private final NatsBridge nats;
    private final String localNodeId;

    public CompanionTransitProtocol(NatsBridge nats, String localNodeId) {
        this.nats = nats;
        this.localNodeId = localNodeId;
    }

    /**
     * Publish a transit message — companion is leaving this node.
     * Called by the source node after exporting companion state.
     */
    public void publishTransit(TransitState state) {
        var subject = "companion.transit";
        nats.broadcast(subject, state.entityId(), MAPPER.valueToTree(state));
        log.info("Published companion transit: {} → {} (reason={})",
            state.entityId(), state.targetNodeId(), state.reason());
    }

    /**
     * Subscribe to transit messages for a specific companion.
     * Called by all nodes so any node can be a target.
     */
    public void subscribeTransit(String entityId, TransitListener listener) {
        nats.subscribeBroadcast("companion.transit", entityId, env -> {
            try {
                var state = MAPPER.convertValue(env.payload(), TransitState.class);
                // Only the target node should act on it
                if (localNodeId.equals(state.targetNodeId())) {
                    log.info("Received companion transit for {}: source={} reason={}",
                        entityId, state.sourceNodeId(), state.reason());
                    listener.onTransitArrival(state);
                }
            } catch (Exception e) {
                log.warn("Failed to parse transit message for {}: {}", entityId, e.getMessage());
            }
        });
    }

    /**
     * Subscribe to transit messages for any companion targeting this node.
     * Used during initial startup to catch in-flight migrations.
     */
    public void subscribeAllTransits(TransitListener listener) {
        nats.subscribeBroadcast("companion.transit", ">", env -> {
            try {
                var state = MAPPER.convertValue(env.payload(), TransitState.class);
                if (localNodeId.equals(state.targetNodeId())) {
                    log.info("Received companion transit for {}: source={} reason={}",
                        state.entityId(), state.sourceNodeId(), state.reason());
                    listener.onTransitArrival(state);
                }
            } catch (Exception e) {
                log.debug("Ignoring transit message not for this node");
            }
        });
    }

    /**
     * Build a TransitState from companion runtime data.
     * Called by CompanionActor before shutdown during migration.
     */
    public TransitState buildTransitState(String entityId, String targetNodeId,
                                           String roomId, List<String> workingMemory,
                                           String vitalityJson, String reason) {
        return new TransitState(
            entityId, localNodeId, targetNodeId,
            roomId, workingMemory, vitalityJson,
            reason, Instant.now()
        );
    }
}
