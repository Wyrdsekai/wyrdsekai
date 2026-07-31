package org.wyrdsekai.core.nostr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wave 5.3c — background scheduler that polls a
 * configured set of Nostr relays for kind-30078 self-attestation
 * events for registered agent DIDs.
 *
 * <p>The publish side (kind-30078 event builder) lives in
 * {@code release/ProtectionAttestation.java}. This is the federation-
 * visibility query side: the substrate-side {@code introspect_protections}
 * action reports the local verifier state, but only the relay round-trip
 * tells the agent whether their attestation is <i>visible to the world</i>.
 * If no event has appeared for an agent in N days, something is wrong
 * with their federation visibility — that's the signal this caches.
 *
 * <p>Opt-in: starts only when {@code WYRDSEKAI_NOSTR_ENABLED=true} AND a
 * non-empty {@code WYRDSEKAI_NOSTR_RELAYS} env (comma-separated wss://...)
 * is provided. Pure read-only — never publishes, never modifies relay
 * state. Local-dev / unit-test default: scheduler is disabled and
 * {@link #latestForAgent} returns empty for every DID.
 *
 * <p>Cadence: one poll per {@link #DEFAULT_POLL_INTERVAL} per registered
 * agent. The pool's existing subscribe mechanism remains live in the
 * background so push updates also flow in — the scheduler is a periodic
 * <em>refresh</em>, not the only delivery path.
 */
public final class RelayPoolScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RelayPoolScheduler.class);

    /** Polling cadence for proactive refresh per registered agent. */
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(60);

    /** kind-30078 = NIP-78 application-specific data (we use it for
     * ProtectionAttestation). */
    public static final int KIND_PROTECTION_ATTESTATION = 30078;

    /** Subscription id prefix — caller-chosen, must be unique per agent. */
    private static final String SUB_PREFIX = "wyrd-attest-";

    private static volatile RelayPoolScheduler INSTANCE;

    private final NostrRelayPool pool;
    private final ScheduledExecutorService scheduler;
    private final Duration pollInterval;

    /** agentDid → most recent kind-30078 event observed (any relay). */
    private final Map<String, AtomicReference<NostrEvent>> latest = new ConcurrentHashMap<>();

    /** agentDid → last-poll timestamp (for diagnostics). */
    private final Map<String, AtomicReference<Instant>> lastPolled = new ConcurrentHashMap<>();

    private volatile boolean started = false;
    private volatile boolean closed = false;

    /**
     * Construct with an explicit pool + cadence. Used by tests to inject
     * a stubbed pool; production wires through {@link #initFromEnv}.
     */
    public RelayPoolScheduler(NostrRelayPool pool, Duration pollInterval) {
        this.pool = pool;
        this.pollInterval = pollInterval == null ? DEFAULT_POLL_INTERVAL : pollInterval;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "wyrd-relay-pool-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Initialize the global singleton from environment configuration.
     * Returns {@link Optional#empty()} (and leaves the singleton null)
     * when Nostr is disabled or no relays are configured — callers
     * should treat that as "federation visibility unknown".
     *
     * <p>Idempotent: a second call is a no-op when an instance already
     * exists. Test code should call {@link #initWithPool} instead.
     */
    public static synchronized Optional<RelayPoolScheduler> initFromEnv() {
        if (INSTANCE != null) return Optional.of(INSTANCE);
        var enabled = System.getenv("WYRDSEKAI_NOSTR_ENABLED");
        if (enabled == null || !enabled.equalsIgnoreCase("true")) {
            log.info("RelayPoolScheduler: disabled — WYRDSEKAI_NOSTR_ENABLED is not 'true'");
            return Optional.empty();
        }
        var relaysCsv = System.getenv("WYRDSEKAI_NOSTR_RELAYS");
        if (relaysCsv == null || relaysCsv.isBlank()) {
            log.info("RelayPoolScheduler: disabled — WYRDSEKAI_NOSTR_RELAYS is empty");
            return Optional.empty();
        }
        var relays = Arrays.stream(relaysCsv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
        if (relays.isEmpty()) {
            log.info("RelayPoolScheduler: disabled — no usable relay URLs after parsing");
            return Optional.empty();
        }
        var pool = new NostrRelayPool(relays);
        pool.start();
        var sched = new RelayPoolScheduler(pool, DEFAULT_POLL_INTERVAL);
        sched.start();
        INSTANCE = sched;
        log.info("RelayPoolScheduler: started with {} relay(s), poll interval={}s",
            relays.size(), sched.pollInterval.toSeconds());
        return Optional.of(sched);
    }

    /** Test-only: install a pre-built instance as the singleton. */
    public static synchronized void initWithPool(RelayPoolScheduler instance) {
        INSTANCE = instance;
    }

    /** Global instance, or empty if uninitialized / opt-out. */
    public static Optional<RelayPoolScheduler> get() {
        return Optional.ofNullable(INSTANCE);
    }

    /** Test hook — clear the singleton between tests. */
    public static synchronized void resetForTests() {
        if (INSTANCE != null) {
            try { INSTANCE.close(); } catch (Exception ignored) {}
        }
        INSTANCE = null;
    }

    /** Start the periodic poller. Idempotent. */
    public synchronized void start() {
        if (started || closed) return;
        started = true;
        scheduler.scheduleWithFixedDelay(this::pollOnce,
            pollInterval.toMillis(),
            pollInterval.toMillis(),
            TimeUnit.MILLISECONDS);
    }

    /**
     * Register an agent DID for ongoing relay refresh. Opens a live
     * subscription so push updates flow in immediately, AND adds it to
     * the periodic poll set. Calling twice with the same DID is a no-op.
     */
    public void register(String agentDid) {
        if (agentDid == null || agentDid.isBlank() || closed) return;
        if (latest.containsKey(agentDid)) return;
        latest.put(agentDid, new AtomicReference<>(null));
        lastPolled.put(agentDid, new AtomicReference<>(null));
        openSubscription(agentDid);
    }

    /** Forget an agent — closes the live subscription, drops the cache. */
    public void unregister(String agentDid) {
        if (agentDid == null) return;
        pool.unsubscribe(SUB_PREFIX + agentDid);
        latest.remove(agentDid);
        lastPolled.remove(agentDid);
    }

    /**
     * Most recently observed kind-30078 self-attestation event for the
     * agent (across all relays), or empty if none has been seen since
     * registration. Pure read; never blocks.
     */
    public Optional<NostrEvent> latestForAgent(String agentDid) {
        if (agentDid == null) return Optional.empty();
        var ref = latest.get(agentDid);
        if (ref == null) return Optional.empty();
        return Optional.ofNullable(ref.get());
    }

    /** When this agent was last polled, for federation-staleness reporting. */
    public Optional<Instant> lastPolledAt(String agentDid) {
        if (agentDid == null) return Optional.empty();
        var ref = lastPolled.get(agentDid);
        if (ref == null) return Optional.empty();
        return Optional.ofNullable(ref.get());
    }

    /** All currently registered agent DIDs. */
    public List<String> registeredAgents() {
        return List.copyOf(latest.keySet());
    }

    private void openSubscription(String agentDid) {
        var subId = SUB_PREFIX + agentDid;
        var filter = Map.<String, Object>of(
            "kinds", List.of(KIND_PROTECTION_ATTESTATION),
            "#d", List.of(agentDid),
            "limit", 1);
        try {
            pool.subscribe(subId, filter, new NostrRelayPool.NostrEventListener() {
                @Override
                public void onEvent(String relay, String sid, NostrEvent event) {
                    if (event == null || event.kind() != KIND_PROTECTION_ATTESTATION) return;
                    updateLatest(agentDid, event);
                }
            });
        } catch (Exception e) {
            log.warn("RelayPoolScheduler: open subscription for {} failed: {}",
                agentDid, e.getMessage());
        }
    }

    private void updateLatest(String agentDid, NostrEvent event) {
        var ref = latest.get(agentDid);
        if (ref == null) return;
        // Keep the most recent by createdAt; relays can deliver out-of-order.
        ref.getAndUpdate(prev -> {
            if (prev == null) return event;
            return event.createdAt() > prev.createdAt() ? event : prev;
        });
    }

    private void pollOnce() {
        if (closed) return;
        var now = Instant.now();
        for (var agentDid : List.copyOf(latest.keySet())) {
            try {
                // Re-open the subscription with a fresh filter. NIP-01
                // relays will return the most recent matching event
                // (limit=1) before holding the subscription open for
                // future pushes — same path as initial register.
                openSubscription(agentDid);
                var ref = lastPolled.get(agentDid);
                if (ref != null) ref.set(now);
            } catch (Exception e) {
                log.debug("RelayPoolScheduler: poll for {} failed: {}",
                    agentDid, e.getMessage());
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        try { pool.close(); } catch (Exception ignored) {}
    }
}
