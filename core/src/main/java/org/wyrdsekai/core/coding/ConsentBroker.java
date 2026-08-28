package org.wyrdsekai.core.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Live steward consent for backend permission requests (2026-08-16,
 * steward-approved design).
 *
 * <p>The invariant this preserves is the same one HOUSE_POLICY encoded
 * statically and P4b tests behaviorally: <b>silence is not consent</b>. A
 * pending consent that the steward does not answer within its wait window
 * resolves to {@code false} — exactly the {@code reject_once} outcome the
 * static policy always produced. The broker only ADDS the ability to say
 * yes in real time; it can never widen what silence permits.</p>
 *
 * <p>Flow: a backend's permission policy calls {@link #request}, which
 * registers the pending consent and fires the notifier (wired at zone
 * startup to the steward's surfaces — in-world tell, phone push; a stub in
 * tests and headless runs). The steward answers via {@link #answer}
 * (reached through the steward-token-gated consent routes / {@code wyrd
 * consent}). The requesting thread blocks in {@link #await} for at most
 * its window.</p>
 *
 * <p>Grants are single-use by design: one consent = one answered request.
 * There is no "always allow" here on purpose — every ask stays visible,
 * matching HOUSE_POLICY's refusal to ever pick {@code *_always} options.</p>
 */
public final class ConsentBroker {

    private static final Logger log = LoggerFactory.getLogger(ConsentBroker.class);

    /** One pending ask, as surfaces render it. */
    public record PendingConsent(String id, String backend, String taskId,
                                 String summary, Instant createdAt) {}

    private record Entry(PendingConsent consent, CompletableFuture<Boolean> decision) {}

    private static final ConsentBroker INSTANCE = new ConsentBroker();

    public static ConsentBroker get() {
        return INSTANCE;
    }

    private final Map<String, Entry> pending = new ConcurrentHashMap<>();
    private volatile Consumer<PendingConsent> notifier;

    /** Prefer {@link #get()} in production — fresh instances are for tests. */
    public ConsentBroker() {}

    /** Zone startup wires the steward's notification surfaces here. */
    public void setNotifier(Consumer<PendingConsent> notifier) {
        this.notifier = notifier;
    }

    /** Register a pending consent and notify the steward. */
    public PendingConsent request(String backend, String taskId, String summary) {
        var consent = new PendingConsent(UUID.randomUUID().toString(),
            backend == null ? "?" : backend,
            taskId == null ? "?" : taskId,
            summary == null ? "" : summary,
            Instant.now());
        pending.put(consent.id(), new Entry(consent, new CompletableFuture<>()));
        log.info("consent requested [{}]: {} task {} — {}",
            consent.id(), consent.backend(), consent.taskId(), consent.summary());
        var n = notifier;
        if (n != null) {
            try {
                n.accept(consent);
            } catch (Exception e) {
                log.warn("consent notifier threw (steward may not have been pinged): {}",
                    e.toString());
            }
        }
        return consent;
    }

    /**
     * Block for the steward's decision, at most {@code wait}. Timeout,
     * interrupt, or an unknown id all resolve to {@code false} — silence
     * is not consent. The entry is removed on resolution either way.
     */
    public boolean await(String id, Duration wait) {
        var entry = pending.get(id);
        if (entry == null) return false;
        try {
            return entry.decision().get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.info("consent [{}] unanswered after {}s — refused (silence is not consent)",
                id, wait.toSeconds());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            pending.remove(id);
        }
    }

    /**
     * The steward's answer. Returns {@code false} when the id is unknown
     * or already resolved (e.g. the ask timed out a moment earlier — the
     * refusal already happened and a late "allow" must not resurrect it).
     */
    public boolean answer(String id, boolean allow) {
        var entry = pending.get(id);
        if (entry == null) return false;
        boolean first = entry.decision().complete(allow);
        if (first) {
            log.info("consent [{}] answered by steward: {}", id, allow ? "ALLOW" : "DENY");
        }
        return first;
    }

    /** Snapshot of unanswered asks, oldest first, for list surfaces. */
    public List<PendingConsent> pending() {
        var out = new ArrayList<PendingConsent>();
        for (var e : pending.values()) {
            if (!e.decision().isDone()) out.add(e.consent());
        }
        out.sort((a, b) -> a.createdAt().compareTo(b.createdAt()));
        return out;
    }

    /** Test hook — drop all state (pending futures resolve to refused). */
    public void resetForTest() {
        for (var e : pending.values()) {
            e.decision().complete(false);
        }
        pending.clear();
        notifier = null;
    }
}
