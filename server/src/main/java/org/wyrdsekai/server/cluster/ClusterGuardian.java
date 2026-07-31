package org.wyrdsekai.server.cluster;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.cluster.typed.Cluster;
import org.apache.pekko.cluster.typed.SelfUp;
import org.apache.pekko.cluster.typed.Subscribe;
import org.apache.pekko.persistence.typed.ReplicaId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Top-level cluster lifecycle manager.
 * Monitors Pekko Cluster membership and provides:
 * - Self-up notification (cluster has formed)
 * - Replica ID resolution for Replicated Event Sourcing
 * - Split-brain resolution configuration (keep-majority)
 */
public class ClusterGuardian extends AbstractBehavior<ClusterGuardian.Command> {

    private static final Logger log = LoggerFactory.getLogger(ClusterGuardian.class);

    public sealed interface Command {}

    /** Cluster has formed — this node is up. */
    private record ClusterSelfUp(SelfUp event) implements Command {}

    /** Query cluster state. */
    public record GetClusterState(ActorRef<ClusterState> replyTo) implements Command {}

    /** Cluster state response. */
    public record ClusterState(
        String selfNodeId,
        ReplicaId selfReplica,
        Set<ReplicaId> allReplicas,
        int memberCount,
        boolean selfUp
    ) {}

    private final Cluster cluster;
    private final ReplicaId selfReplica;
    private final Set<ReplicaId> allReplicas;
    private boolean selfUp = false;

    private ClusterGuardian(ActorContext<Command> context,
                            ReplicaId selfReplica,
                            Set<ReplicaId> allReplicas) {
        super(context);
        this.selfReplica = selfReplica;
        this.allReplicas = allReplicas;
        this.cluster = Cluster.get(context.getSystem());

        // Subscribe to cluster self-up events
        var selfUpAdapter = context.messageAdapter(SelfUp.class, ClusterSelfUp::new);
        cluster.subscriptions().tell(new Subscribe<>(selfUpAdapter, SelfUp.class));

        log.info("ClusterGuardian started — self={}, allReplicas={}",
            selfReplica.id(), allReplicas.stream().map(ReplicaId::id).collect(Collectors.joining(",")));
    }

    public static Behavior<Command> create(ReplicaId selfReplica, Set<ReplicaId> allReplicas) {
        return Behaviors.setup(ctx -> new ClusterGuardian(ctx, selfReplica, allReplicas));
    }

    /** Create for single-node deployment. */
    public static Behavior<Command> createSingleNode() {
        var local = new ReplicaId("local");
        return create(local, Set.of(local));
    }

    /** Create from discovered node IDs. */
    public static Behavior<Command> create(String selfNodeId, Set<String> allNodeIds) {
        var selfReplica = new ReplicaId(selfNodeId);
        var allReplicas = allNodeIds.stream()
            .map(ReplicaId::new)
            .collect(Collectors.toSet());
        return create(selfReplica, allReplicas);
    }

    /** Resolve self replica ID from system config or default. */
    public static ReplicaId resolveReplicaId(ActorSystem<?> system) {
        var config = system.settings().config();
        if (config.hasPath("wyrdsekai.replica-id")) {
            return new ReplicaId(config.getString("wyrdsekai.replica-id"));
        }
        return new ReplicaId("local");
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(ClusterSelfUp.class, this::onSelfUp)
            .onMessage(GetClusterState.class, this::onGetClusterState)
            .build();
    }

    private Behavior<Command> onSelfUp(ClusterSelfUp msg) {
        this.selfUp = true;
        log.info("Cluster self-up: this node ({}) has joined the cluster",
            selfReplica.id());
        return this;
    }

    private int memberCount() {
        int count = 0;
        for (var ignored : cluster.state().getMembers()) count++;
        return count;
    }

    private Behavior<Command> onGetClusterState(GetClusterState msg) {
        msg.replyTo().tell(new ClusterState(
            selfReplica.id(),
            selfReplica,
            allReplicas,
            memberCount(),
            selfUp
        ));
        return this;
    }
}
