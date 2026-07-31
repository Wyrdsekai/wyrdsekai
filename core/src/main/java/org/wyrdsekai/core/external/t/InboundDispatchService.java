package org.wyrdsekai.core.external.t;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * (Phase T) — single fan-in point for inbound
 * events from any listener adapter (webhook, RSS, IMAP, MQTT, file watcher,
 * scheduled, SSE, WebSocket, ...).
 *
 * <p>Listener adapters call {@link #dispatch(String, InboundEvent)} as soon as
 * they observe an event. The service consults
 * {@link InboundSubscriptionRegistry#evaluate} (rate limit + paused state),
 * then asks {@link HookCallbackInvoker} to call the item's named hook
 * inside the GraalJS sandbox.</p>
 *
 * <p>The service maintains process-wide counters ({@link #deliveredCount},
 * {@link #droppedCount}, {@link #rateLimitedCount}) and an optional audit
 * listener that test hooks bind to so they can observe deliveries
 * deterministically without poking GraalJS internals.</p>
 *
 * <p>Singleton — first {@link #init} wins. Subsequent calls return the
 * existing instance.</p>
 */
public final class InboundDispatchService {

    private static final Logger log = LoggerFactory.getLogger(InboundDispatchService.class);

    private static volatile InboundDispatchService INSTANCE;

    private final InboundSubscriptionRegistry registry;
    private final HookCallbackInvoker invoker;

    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong rateLimited = new AtomicLong();

    /** Test/audit hook — fired after each dispatch (delivered or dropped). */
    private volatile BiConsumer<InboundEvent, DispatchOutcome> auditListener;

    /** Outcome record for the audit listener + tests. */
    public record DispatchOutcome(
        String subscriptionId,
        String itemId,
        InboundSubscriptionRegistry.DeliveryDecision decision,
        Map<String, Object> result   // null when not delivered
    ) {}

    private InboundDispatchService(InboundSubscriptionRegistry registry,
                                     HookCallbackInvoker invoker) {
        this.registry = registry;
        this.invoker = invoker;
    }

    /** First call wins. Pass the registry the listener adapters will share. */
    public static InboundDispatchService init(InboundSubscriptionRegistry registry,
                                                HookCallbackInvoker invoker) {
        if (INSTANCE == null) {
            synchronized (InboundDispatchService.class) {
                if (INSTANCE == null) {
                    INSTANCE = new InboundDispatchService(
                        registry == null ? InboundSubscriptionRegistry.get(null) : registry,
                        invoker == null ? HookCallbackInvoker.defaults() : invoker);
                }
            }
        }
        return INSTANCE;
    }

    /** Get the existing instance, throwing if {@link #init} hasn't been called. */
    public static InboundDispatchService get() {
        if (INSTANCE == null) {
            throw new IllegalStateException("InboundDispatchService.init() not called");
        }
        return INSTANCE;
    }

    public static synchronized void resetForTesting() {
        INSTANCE = null;
    }

    public InboundSubscriptionRegistry registry() { return registry; }

    public void setAuditListener(BiConsumer<InboundEvent, DispatchOutcome> listener) {
        this.auditListener = listener;
    }

    /**
     * Deliver one event to the named subscription's hook. Returns the outcome
     * record so callers can log + the audit listener can observe.
     */
    public DispatchOutcome dispatch(String subscriptionId, InboundEvent event) {
        var sub = registry.find(subscriptionId).orElse(null);
        if (sub == null) {
            dropped.incrementAndGet();
            var outcome = new DispatchOutcome(subscriptionId, null,
                InboundSubscriptionRegistry.DeliveryDecision.NOT_FOUND, null);
            fireAudit(event, outcome);
            log.debug("dispatch: subscription {} not found, drop", subscriptionId);
            return outcome;
        }
        var decision = registry.evaluate(subscriptionId);
        switch (decision) {
            case PAUSED -> {
                dropped.incrementAndGet();
                var outcome = new DispatchOutcome(subscriptionId, sub.itemId(), decision, null);
                fireAudit(event, outcome);
                return outcome;
            }
            case RATE_LIMITED -> {
                rateLimited.incrementAndGet();
                var outcome = new DispatchOutcome(subscriptionId, sub.itemId(), decision, null);
                fireAudit(event, outcome);
                log.warn("dispatch: subscription {} rate-limited (item={} hook={} kind={})",
                    subscriptionId, sub.itemId(), sub.hookName(), sub.kind());
                return outcome;
            }
            case NOT_FOUND -> {
                dropped.incrementAndGet();
                var outcome = new DispatchOutcome(subscriptionId, sub.itemId(), decision, null);
                fireAudit(event, outcome);
                return outcome;
            }
            case DELIVER -> {
                // fall-through to invocation below
            }
        }
        var result = invoker.invoke(sub.itemId(), sub.agentId(), sub.hookName(), event);
        delivered.incrementAndGet();
        var outcome = new DispatchOutcome(subscriptionId, sub.itemId(),
            InboundSubscriptionRegistry.DeliveryDecision.DELIVER, result);
        fireAudit(event, outcome);
        return outcome;
    }

    /** Process-wide counters for the {@code wyrd inbound stats} CLI / audit. */
    public Map<String, Long> stats() {
        var m = new LinkedHashMap<String, Long>();
        m.put("delivered", delivered.get());
        m.put("dropped", dropped.get());
        m.put("rateLimited", rateLimited.get());
        m.put("active", (long) registry.size());
        return m;
    }

    public long deliveredCount() { return delivered.get(); }
    public long droppedCount() { return dropped.get(); }
    public long rateLimitedCount() { return rateLimited.get(); }

    private void fireAudit(InboundEvent event, DispatchOutcome outcome) {
        var listener = auditListener;
        if (listener != null) {
            try {
                listener.accept(event, outcome);
            } catch (Exception e) {
                log.debug("audit listener threw: {}", e.getMessage());
            }
        }
    }
}
