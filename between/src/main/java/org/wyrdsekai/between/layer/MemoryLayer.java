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

/**
 * Shared memory layer for The Between.
 * Provides a distributed key-value store across household nodes,
 * used for agent memory sharing and cross-room context.
 *
 * Memory entries have TTL and are propagated to peers via NATS.
 * Entries expire automatically; the layer is volatile (lost on full cluster restart).
 */
public class MemoryLayer extends AbstractBehavior<MemoryLayer.Command> {

    private static final Logger log = LoggerFactory.getLogger(MemoryLayer.class);
    private static final int DEFAULT_TTL_SECONDS = 3600; // 1 hour

    public sealed interface Command {}

    /** Store a memory entry (local + propagate to peers). */
    public record Store(String namespace, String key, String value,
                        int ttlSeconds) implements Command {
        public Store(String namespace, String key, String value) {
            this(namespace, key, value, DEFAULT_TTL_SECONDS);
        }
    }

    /** Received a memory entry from a peer. */
    public record ReceiveEntry(String fromNode, String namespace,
                                String key, String value,
                                long expiresAt) implements Command {}

    /** Retrieve a memory entry. */
    public record Retrieve(String namespace, String key,
                            ActorRef<RetrieveResult> replyTo) implements Command {}

    /** Retrieve result. */
    public record RetrieveResult(String namespace, String key,
                                  String value, boolean found) {}

    /** List all entries in a namespace. */
    public record ListNamespace(String namespace,
                                 ActorRef<NamespaceEntries> replyTo) implements Command {}

    /** Namespace entries response. */
    public record NamespaceEntries(String namespace,
                                    Map<String, String> entries) {}

    /** Delete a memory entry. */
    public record Delete(String namespace, String key) implements Command {}

    /** Periodic TTL sweep. */
    private record TtlSweep() implements Command {}

    /** Internal entry with expiry. */
    private record MemoryEntry(String value, long expiresAt, String sourceNode) {}

    // namespace → key → entry
    private final Map<String, Map<String, MemoryEntry>> store = new HashMap<>();
    private final String localNodeId;

    private MemoryLayer(ActorContext<Command> context, String localNodeId) {
        super(context);
        this.localNodeId = localNodeId;

        // Schedule TTL sweep every 30 seconds
        context.getSystem().scheduler().scheduleAtFixedRate(
            Duration.ofSeconds(30),
            Duration.ofSeconds(30),
            () -> context.getSelf().tell(new TtlSweep()),
            context.getExecutionContext());

        log.info("MemoryLayer started for node {}", localNodeId);
    }

    public static Behavior<Command> create(String localNodeId) {
        return Behaviors.setup(ctx -> new MemoryLayer(ctx, localNodeId));
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(Store.class, this::onStore)
            .onMessage(ReceiveEntry.class, this::onReceive)
            .onMessage(Retrieve.class, this::onRetrieve)
            .onMessage(ListNamespace.class, this::onList)
            .onMessage(Delete.class, this::onDelete)
            .onMessage(TtlSweep.class, this::onSweep)
            .build();
    }

    private Behavior<Command> onStore(Store cmd) {
        long expiresAt = Instant.now().getEpochSecond() + cmd.ttlSeconds();
        var ns = store.computeIfAbsent(cmd.namespace(), _ -> new HashMap<>());
        ns.put(cmd.key(), new MemoryEntry(cmd.value(), expiresAt, localNodeId));
        log.debug("Stored {}/{} (ttl={}s)", cmd.namespace(), cmd.key(), cmd.ttlSeconds());
        return this;
    }

    private Behavior<Command> onReceive(ReceiveEntry cmd) {
        var ns = store.computeIfAbsent(cmd.namespace(), _ -> new HashMap<>());
        var existing = ns.get(cmd.key());
        // Only apply if newer (higher expiry = written later)
        if (existing == null || cmd.expiresAt() > existing.expiresAt()) {
            ns.put(cmd.key(), new MemoryEntry(cmd.value(), cmd.expiresAt(), cmd.fromNode()));
        }
        return this;
    }

    private Behavior<Command> onRetrieve(Retrieve cmd) {
        var ns = store.get(cmd.namespace());
        if (ns == null) {
            cmd.replyTo().tell(new RetrieveResult(cmd.namespace(), cmd.key(), null, false));
            return this;
        }
        var entry = ns.get(cmd.key());
        if (entry == null || entry.expiresAt() < Instant.now().getEpochSecond()) {
            cmd.replyTo().tell(new RetrieveResult(cmd.namespace(), cmd.key(), null, false));
        } else {
            cmd.replyTo().tell(new RetrieveResult(cmd.namespace(), cmd.key(), entry.value(), true));
        }
        return this;
    }

    private Behavior<Command> onList(ListNamespace cmd) {
        var ns = store.get(cmd.namespace());
        if (ns == null) {
            cmd.replyTo().tell(new NamespaceEntries(cmd.namespace(), Map.of()));
            return this;
        }
        long now = Instant.now().getEpochSecond();
        var live = new HashMap<String, String>();
        for (var e : ns.entrySet()) {
            if (e.getValue().expiresAt() > now) {
                live.put(e.getKey(), e.getValue().value());
            }
        }
        cmd.replyTo().tell(new NamespaceEntries(cmd.namespace(), Map.copyOf(live)));
        return this;
    }

    private Behavior<Command> onDelete(Delete cmd) {
        var ns = store.get(cmd.namespace());
        if (ns != null) {
            ns.remove(cmd.key());
        }
        return this;
    }

    private Behavior<Command> onSweep(TtlSweep tick) {
        long now = Instant.now().getEpochSecond();
        int swept = 0;
        for (var ns : store.values()) {
            var iter = ns.entrySet().iterator();
            while (iter.hasNext()) {
                if (iter.next().getValue().expiresAt() < now) {
                    iter.remove();
                    swept++;
                }
            }
        }
        if (swept > 0) {
            log.debug("TTL sweep: removed {} expired entries", swept);
        }
        return this;
    }
}
