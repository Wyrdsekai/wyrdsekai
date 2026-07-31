package org.wyrdsekai.core.familiar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounds and tracks concurrent bunshin per primary agent (§5.1, §6).
 *
 * <h2>Ceilings (§5)</h2>
 * <ul>
 *   <li><b>default.maxConcurrent</b> — primary + bunshin count, steady state (spec default 2)</li>
 *   <li><b>elasticCeiling</b> — auto-grant {+1} if cost/inference-slot checks pass (spec default 3)</li>
 *   <li><b>absoluteCeiling</b> — steward-enforced hard cap (spec default 5)</li>
 * </ul>
 *
 * <h2>Single-primary invariant (§6.5)</h2>
 * A primary CompanionActor registers itself once via {@link #registerPrimary}.
 * Attempting to register a second primary under the same DID throws. This is
 * load-bearing: the agent you are talking to is never split across identities.
 *
 * <h2>Usage flow</h2>
 * <pre>
 *   scheduler.registerPrimary(did);
 *   var slot = scheduler.acquireSlot(did, ElasticProbe);  // empty if refused
 *   // ... spawn BunshinActor ...
 *   // on return:
 *   scheduler.releaseSlot(did, slotId);
 * </pre>
 *
 * <h2>Priority broadcasts</h2>
 * {@link #primaryActive} / {@link #primaryQuiescent} flip a per-DID flag that
 * BunshinActor instances can read to decide whether to yield before their
 * next inference turn. (§6.3: bunshin yields, never dies, on primary active.)
 */
public final class BunshinScheduler {

    private static final Logger log = LoggerFactory.getLogger(BunshinScheduler.class);

    public static final int DEFAULT_MAX_CONCURRENT = 2;
    public static final int DEFAULT_ELASTIC_CEILING = 3;
    public static final int DEFAULT_ABSOLUTE_CEILING = 5;

    // ── Global singleton (process-scoped) ──────────────────────────────────

    private static volatile BunshinScheduler INSTANCE;

    /** Lazy singleton accessor — creates with default ceilings on first call. */
    public static BunshinScheduler get() {
        var s = INSTANCE;
        if (s == null) {
            synchronized (BunshinScheduler.class) {
                s = INSTANCE;
                if (s == null) {
                    s = new BunshinScheduler();
                    INSTANCE = s;
                }
            }
        }
        return s;
    }

    /** Explicitly install a custom-configured instance (e.g. from config). */
    public static void install(BunshinScheduler s) {
        synchronized (BunshinScheduler.class) {
            INSTANCE = s;
        }
    }

    /** Test support — reset the singleton between tests. */
    public static void resetForTests() {
        synchronized (BunshinScheduler.class) {
            INSTANCE = null;
        }
    }

    /**
     * Pluggable check for whether elastic grants are allowed. Returns true
     * when both AgentCostTracker.canAfford and InferenceRouter.idleSlotsAvailable
     * are satisfied (§5.1). For the primitives pass, callers supply a
     * lambda or a noop.
     */
    @FunctionalInterface
    public interface ElasticProbe {
        boolean allowed();
        ElasticProbe NEVER = () -> false;
        ElasticProbe ALWAYS = () -> true;
    }

    /** Outcome of a slot-acquisition attempt. */
    public sealed interface Slot {
        String slotId();
        String primaryDid();
        boolean elastic();

        record Granted(String slotId, String primaryDid, boolean elastic) implements Slot {}
        record Refused(String slotId, String primaryDid, String reason) implements Slot {
            @Override public boolean elastic() { return false; }
        }
    }

    private record PrimaryState(
        Set<String> activeSlots,
        Set<String> elasticSlots,
        boolean primaryActive
    ) {}

    private final int maxConcurrent;
    private final int elasticCeiling;
    private final int absoluteCeiling;

    // Per-DID state. Registered primaries only.
    private final Map<String, PrimaryState> primaries = new ConcurrentHashMap<>();

    public BunshinScheduler() {
        this(DEFAULT_MAX_CONCURRENT, DEFAULT_ELASTIC_CEILING, DEFAULT_ABSOLUTE_CEILING);
    }

    public BunshinScheduler(int maxConcurrent, int elasticCeiling, int absoluteCeiling) {
        if (maxConcurrent < 1) maxConcurrent = DEFAULT_MAX_CONCURRENT;
        if (elasticCeiling < maxConcurrent) elasticCeiling = maxConcurrent;
        if (absoluteCeiling < elasticCeiling) absoluteCeiling = elasticCeiling;
        this.maxConcurrent = maxConcurrent;
        this.elasticCeiling = elasticCeiling;
        this.absoluteCeiling = absoluteCeiling;
    }

    // ── Primary registration (§6.5 single-primary invariant) ───────────────

    /**
     * Register a primary under its DID. Throws if one is already registered.
     * To replace, explicitly {@link #unregisterPrimary} first.
     */
    public void registerPrimary(String did) {
        requireDid(did);
        var prior = primaries.putIfAbsent(did, emptyState());
        if (prior != null) {
            throw new IllegalStateException("primary already registered for " + did
                + " — single-primary invariant violated (§6.5)");
        }
        log.debug("BunshinScheduler: primary registered for {}", did);
    }

    public void unregisterPrimary(String did) {
        primaries.remove(did);
    }

    public boolean hasPrimary(String did) {
        return primaries.containsKey(did);
    }

    // ── Slot acquisition ────────────────────────────────────────────────────

    /** Acquire a bunshin slot for the given primary. */
    public Slot acquireSlot(String did, ElasticProbe probe) {
        requireDid(did);
        if (probe == null) probe = ElasticProbe.NEVER;

        var slotId = UUID.randomUUID().toString();
        final var probeRef = probe;

        var result = new AtomicReference<Slot>(
            new Slot.Refused(slotId, did, "no primary registered"));

        primaries.computeIfPresent(did, (k, state) -> {
            int active = state.activeSlots().size();
            if (active < maxConcurrent) {
                state.activeSlots().add(slotId);
                result.set(new Slot.Granted(slotId, did, false));
                return state;
            }
            if (active < elasticCeiling && probeRef.allowed()) {
                state.activeSlots().add(slotId);
                state.elasticSlots().add(slotId);
                result.set(new Slot.Granted(slotId, did, true));
                log.info("BunshinScheduler: elastic bunshin granted for {} ({} active)",
                    did, active + 1);
                return state;
            }
            if (active >= absoluteCeiling) {
                result.set(new Slot.Refused(slotId, did,
                    "absolute ceiling " + absoluteCeiling + " reached"));
            } else if (active >= elasticCeiling) {
                result.set(new Slot.Refused(slotId, did,
                    "elastic ceiling " + elasticCeiling + " reached — user approval required"));
            } else {
                result.set(new Slot.Refused(slotId, did,
                    "concurrent ceiling " + maxConcurrent
                        + " reached and elastic check failed"));
            }
            return state;
        });

        return result.get();
    }

    /** Release a previously acquired slot. Returns true if it existed. */
    public boolean releaseSlot(String did, String slotId) {
        var state = primaries.get(did);
        if (state == null) return false;
        var removedActive = state.activeSlots().remove(slotId);
        state.elasticSlots().remove(slotId);
        return removedActive;
    }

    public int activeCount(String did) {
        var state = primaries.get(did);
        return state == null ? 0 : state.activeSlots().size();
    }

    public int elasticCount(String did) {
        var state = primaries.get(did);
        return state == null ? 0 : state.elasticSlots().size();
    }

    // ── Priority broadcasts (§6.3) ─────────────────────────────────────────

    public void primaryActive(String did) {
        primaries.computeIfPresent(did, (k, s) ->
            new PrimaryState(s.activeSlots(), s.elasticSlots(), true));
    }

    public void primaryQuiescent(String did) {
        primaries.computeIfPresent(did, (k, s) ->
            new PrimaryState(s.activeSlots(), s.elasticSlots(), false));
    }

    /** Whether bunshin for this DID should yield before the next inference turn. */
    public boolean shouldBunshinYield(String did) {
        var state = primaries.get(did);
        return state != null && state.primaryActive();
    }

    // ── Accessors ──────────────────────────────────────────────────────────

    public int maxConcurrent() { return maxConcurrent; }
    public int elasticCeiling() { return elasticCeiling; }
    public int absoluteCeiling() { return absoluteCeiling; }

    /** Snapshot of primaries. Useful for telemetry; immutable view. */
    public Set<String> registeredPrimaries() {
        return Collections.unmodifiableSet(primaries.keySet());
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static void requireDid(String did) {
        if (did == null || did.isBlank()) {
            throw new IllegalArgumentException("did required");
        }
    }

    private static PrimaryState emptyState() {
        return new PrimaryState(
            ConcurrentHashMap.newKeySet(),
            ConcurrentHashMap.newKeySet(),
            false);
    }
}
