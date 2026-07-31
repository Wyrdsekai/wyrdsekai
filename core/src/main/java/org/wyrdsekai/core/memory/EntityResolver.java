package org.wyrdsekai.core.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * resolves a {@link ProbeClassifier.ProbeIntent}
 * against {@link MemoryEntityStore} to produce a deterministic answer.
 *
 * <p>Runs BEFORE Lucene retrieval in the recall pipeline. On hit, returns an
 * {@link EntityHit} with the entity value and the source memory text for
 * injection into the ReAct system prompt. On miss, returns empty —
 * pipeline falls through to multi-hop + V1 Lucene.</p>
 */
public final class EntityResolver {

    private static final Logger log = LoggerFactory.getLogger(EntityResolver.class);

    /** A resolved entity with supporting memory context. */
    public record EntityHit(
            String entityType,
            String entityRole,
            String entityValue,
            String memoryId,
            long timestamp) {}

    private final MemoryEntityStore store;

    public EntityResolver(MemoryEntityStore store) {
        this.store = store;
    }

    /**
     * Resolve a probe intent to an entity hit.
     *
     * <p>Temporal semantics:
     * <ul>
     *   <li>{@link ProbeClassifier.Temporal#LATEST} — newest row wins</li>
     *   <li>{@link ProbeClassifier.Temporal#ANY} — also newest row (append-only
     *       design means the latest write is the current truth)</li>
     *   <li>{@link ProbeClassifier.Temporal#EARLIEST} — not yet implemented,
     *       falls through to LATEST</li>
     * </ul>
     */
    public Optional<EntityHit> resolve(String did, ProbeClassifier.ProbeIntent intent) {
        if (store == null || did == null || intent == null) return Optional.empty();

        var row = store.findLatest(did, intent.entityType(), intent.entityRole());
        if (row.isPresent()) {
            var r = row.get();
            log.debug("EntityResolver hit: did={} type={} role={} value={}",
                    did, r.entityType(), r.entityRole(), r.entityValue());
            return Optional.of(new EntityHit(
                    r.entityType(),
                    r.entityRole(),
                    r.entityValue(),
                    r.memoryId(),
                    r.timestamp()));
        }

        // Fallback: type without role constraint (useful when role was null in plant
        // but specific in probe, or vice versa)
        if (intent.entityRole() != null) {
            var any = store.findLatest(did, intent.entityType(), null);
            if (any.isPresent()) {
                var r = any.get();
                log.debug("EntityResolver role-fallback hit: did={} type={} value={}",
                        did, r.entityType(), r.entityValue());
                return Optional.of(new EntityHit(
                        r.entityType(),
                        r.entityRole(),
                        r.entityValue(),
                        r.memoryId(),
                        r.timestamp()));
            }
        }

        return Optional.empty();
    }
}
