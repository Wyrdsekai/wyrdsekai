package org.wyrdsekai.core.room;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-entity monotonic transit epoch — the dup-safety fence for cross-zone
 * companion relocation (spec/tla/TransitToken.tla, P1).
 *
 * <p>The relocate handoff is a single-owner transfer: the companion must be
 * hosted in exactly one zone. The existing {@code containsKey} re-tether guard
 * in {@code ZoneGuardian.onRelocateArrive} stops a <em>concurrent</em> in-zone
 * double-spawn, but keys on presence, not on the token — so it does not stop a
 * <em>stale</em> token that arrives after this zone has departed and re-hosted
 * across a depart/return cycle. The model's {@code NoDuplication} violation under
 * {@code AllowRedeliver} is exactly that interleaving.</p>
 *
 * <p>The fix is the model's {@code (entityId, epoch)} idempotency: each DEPART
 * mints a strictly-higher epoch for the entity; each ARRIVE is applied only if
 * its epoch is strictly newer than the highest this zone has seen for that
 * entity. Because both the source and the target advance their view on every hop,
 * epochs are monotonic for the entity across the whole bounce (a Lamport clock
 * per entity), so a redelivered (epoch == seen) or stale (epoch &lt; seen) token
 * is ignored.</p>
 *
 * <p>A pre-fence peer / legacy snapshot carries epoch 0; {@link #isFreshArrival}
 * treats epoch &lt;= 0 as "unfenced" and defers to the caller's presence guard,
 * so a fenced zone still interoperates with an un-upgraded peer.</p>
 *
 * <p>{@code ZoneGuardian} is a single-threaded actor, but this is a plain
 * concurrent map so it is independently unit-testable and safe if shared.</p>
 */
public final class TransitEpochTracker {

    /** entityId -> highest transit epoch this zone has minted or accepted. */
    private final ConcurrentHashMap<String, Long> highestEpoch = new ConcurrentHashMap<>();

    /** Mint the next epoch for a DEPART: one past the highest seen for this entity. */
    public long mintDepartEpoch(String entityId) {
        return highestEpoch.merge(entityId, 1L, (cur, one) -> cur + 1L);
    }

    /**
     * Decide whether an ARRIVE with the given epoch is a fresh (strictly-newer)
     * handoff that should be applied, vs a stale/duplicate token that must be
     * ignored. Atomically advances the per-entity high-water mark when fresh.
     *
     * @return {@code true} if the caller should proceed with the spawn;
     *         {@code false} if the token is stale/duplicate and must be dropped.
     */
    public boolean isFreshArrival(String entityId, long epoch) {
        if (epoch <= 0L) {
            return true;   // pre-fence peer — defer to the presence (containsKey) guard
        }
        var fresh = new AtomicBoolean(false);
        highestEpoch.compute(entityId, (k, current) -> {
            if (current == null || epoch > current) {
                fresh.set(true);
                return epoch;        // advance the clock
            }
            return current;          // stale or duplicate — keep current high
        });
        return fresh.get();
    }

    /** Test/diagnostic: the highest epoch tracked for an entity (0 if none). */
    long highWater(String entityId) {
        return highestEpoch.getOrDefault(entityId, 0L);
    }
}
