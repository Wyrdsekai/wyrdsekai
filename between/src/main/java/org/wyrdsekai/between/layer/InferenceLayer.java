package org.wyrdsekai.between.layer;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * Inference distribution layer for The Between.
 * Routes inference requests across household nodes based on:
 * - Available compute capacity (GPU memory, CPU load)
 * - Model availability per node
 * - Network latency between requester and inference node
 *
 * Each node advertises its inference capabilities periodically.
 * The layer maintains a routing table for optimal request placement.
 */
public class InferenceLayer extends AbstractBehavior<InferenceLayer.Command> {

    private static final Logger log = LoggerFactory.getLogger(InferenceLayer.class);

    public sealed interface Command {}

    /** Advertise this node's inference capacity. */
    public record AdvertiseCapacity(
        String nodeId,
        List<String> availableModels,
        long freeMemoryMb,
        double cpuLoad,
        int activeRequests,
        int maxConcurrent
    ) implements Command {}

    /** Request inference routing decision. */
    public record RouteInference(
        String requesterNodeId,
        String modelId,
        int estimatedTokens,
        ActorRef<RoutingDecision> replyTo
    ) implements Command {}

    /** Routing decision response. */
    public record RoutingDecision(
        String targetNodeId,
        boolean local,
        double estimatedLatencyMs
    ) {}

    /** Query the routing table. */
    public record GetRoutingTable(ActorRef<RoutingTable> replyTo) implements Command {}

    /** Routing table snapshot. */
    public record RoutingTable(Map<String, NodeCapacity> nodes) {}

    /** Per-node capacity record. */
    public record NodeCapacity(
        String nodeId,
        List<String> models,
        long freeMemoryMb,
        double cpuLoad,
        int activeRequests,
        int maxConcurrent,
        Instant lastAdvertised
    ) {}

    private final String localNodeId;
    private final Map<String, NodeCapacity> routingTable = new HashMap<>();

    private InferenceLayer(ActorContext<Command> context, String localNodeId) {
        super(context);
        this.localNodeId = localNodeId;
        log.info("InferenceLayer started for node {}", localNodeId);
    }

    public static Behavior<Command> create(String localNodeId) {
        return Behaviors.setup(ctx -> new InferenceLayer(ctx, localNodeId));
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(AdvertiseCapacity.class, this::onAdvertise)
            .onMessage(RouteInference.class, this::onRoute)
            .onMessage(GetRoutingTable.class, this::onGetTable)
            .build();
    }

    private Behavior<Command> onAdvertise(AdvertiseCapacity cmd) {
        routingTable.put(cmd.nodeId(), new NodeCapacity(
            cmd.nodeId(), cmd.availableModels(), cmd.freeMemoryMb(),
            cmd.cpuLoad(), cmd.activeRequests(), cmd.maxConcurrent(),
            Instant.now()));
        log.debug("Node {} advertised: {} models, {}MB free, {}/{} active",
            cmd.nodeId(), cmd.availableModels().size(), cmd.freeMemoryMb(),
            cmd.activeRequests(), cmd.maxConcurrent());
        return this;
    }

    private Behavior<Command> onRoute(RouteInference cmd) {
        // Find best node: has model, lowest load, sufficient memory
        var bestNode = routingTable.values().stream()
            .filter(n -> n.models().contains(cmd.modelId()))
            .filter(n -> n.activeRequests() < n.maxConcurrent())
            .filter(n -> n.freeMemoryMb() > 100) // minimum 100MB free
            .min(Comparator.comparingDouble(NodeCapacity::cpuLoad))
            .orElse(null);

        if (bestNode == null) {
            // Fallback to local node
            cmd.replyTo().tell(new RoutingDecision(localNodeId, true, 0));
        } else {
            boolean isLocal = bestNode.nodeId().equals(localNodeId);
            double estimatedLatency = isLocal ? 0 : 50.0; // placeholder
            cmd.replyTo().tell(new RoutingDecision(
                bestNode.nodeId(), isLocal, estimatedLatency));
        }
        return this;
    }

    private Behavior<Command> onGetTable(GetRoutingTable cmd) {
        cmd.replyTo().tell(new RoutingTable(Map.copyOf(routingTable)));
        return this;
    }
}
