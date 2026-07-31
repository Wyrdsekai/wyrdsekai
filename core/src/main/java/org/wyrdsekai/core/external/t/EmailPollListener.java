package org.wyrdsekai.core.external.t;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * (Phase T) — IMAP-style email-poll listener.
 *
 * <p>{@code world.inbound.email_watch(filter, hookName, opts?)} polls a
 * mailbox and dispatches an {@link InboundEvent} for each new message that
 * matches the filter. Production wires a real javax.mail / JakartaMail
 * provider in; the in-process default uses a {@link MailboxFetcher}
 * functional injector so tests can simulate inboxes deterministically without
 * network.</p>
 *
 * <p>Per-subscription cursor state ({@code lastSeenUid}) is kept in memory
 * and re-derived from the registry's persisted opts on restart. Production
 * deployments should persist the cursor in the {@code opts_json} blob so a
 * restart doesn't re-deliver the same messages.</p>
 */
public final class EmailPollListener {

    private static final Logger log = LoggerFactory.getLogger(EmailPollListener.class);

    /** A single inbound message — adapter-neutral shape. */
    public record EmailMessage(
        String uid,
        String from,
        String to,
        String subject,
        String body,
        Instant receivedAt,
        Map<String, String> headers
    ) {
        public Map<String, Object> toPayload() {
            var out = new LinkedHashMap<String, Object>();
            out.put("uid", uid);
            out.put("from", from);
            out.put("to", to);
            out.put("subject", subject);
            out.put("body", body);
            out.put("receivedAt", receivedAt == null ? null : receivedAt.toEpochMilli());
            out.put("headers", headers == null ? Map.of() : headers);
            return out;
        }
    }

    /**
     * Functional mailbox fetcher — production wires JavaMail; tests inject a
     * deterministic stub. Receives the {@code lastSeenUid} (or null on first
     * call) and returns messages newer than that.
     */
    @FunctionalInterface
    public interface MailboxFetcher {
        List<EmailMessage> fetchSince(Map<String, Object> filter, String lastSeenUid);
    }

    /** Default poll interval per §4.34 RSS prose (600s) — applied here too. */
    public static final int DEFAULT_POLL_SECONDS = 600;

    private final InboundSubscriptionRegistry registry;
    private final InboundDispatchService dispatch;
    private final ScheduledExecutorService scheduler;
    /** Item-id → fetcher; lets tests substitute per-item stubs. */
    private final Function<String, MailboxFetcher> fetcherResolver;

    private final ConcurrentHashMap<String, String> lastSeen = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    public EmailPollListener(InboundSubscriptionRegistry registry,
                              InboundDispatchService dispatch,
                              ScheduledExecutorService scheduler,
                              Function<String, MailboxFetcher> fetcherResolver) {
        this.registry = registry;
        this.dispatch = dispatch;
        this.scheduler = scheduler;
        this.fetcherResolver = fetcherResolver != null
            ? fetcherResolver
            : _ -> (filter, lastSeenUid) -> List.of();
    }

    /** Subscribe and arm the poller. */
    public Map<String, Object> subscribe(String itemId, String agentId,
                                           Map<String, Object> filter,
                                           String hookName, Map<String, Object> opts) {
        int interval = DEFAULT_POLL_SECONDS;
        if (opts != null && opts.get("pollSeconds") instanceof Number n) {
            interval = Math.max(5, n.intValue());
        }
        var combined = new LinkedHashMap<String, Object>();
        if (opts != null) combined.putAll(opts);
        combined.put("filter", filter == null ? Map.of() : filter);
        combined.put("pollSeconds", interval);
        var id = registry.add(itemId, agentId, "email", hookName,
            (filter == null ? Map.of() : filter).toString(),
            combined, null,
            opts == null ? null : (opts.get("capPerHour") instanceof Number n ? n.intValue() : null));
        arm(id, interval);
        return Map.of("ok", true, "subscriptionId", id, "pollSeconds", interval);
    }

    /** Manually trigger one poll cycle (used by tests). */
    public int poll(String subscriptionId) {
        var sub = registry.find(subscriptionId).orElse(null);
        if (sub == null || !"email".equals(sub.kind())) return 0;
        var filter = (Map<String, Object>) sub.opts().getOrDefault("filter", Map.of());
        var fetcher = fetcherResolver.apply(sub.itemId());
        if (fetcher == null) return 0;
        List<EmailMessage> msgs;
        try {
            msgs = fetcher.fetchSince(filter, lastSeen.get(subscriptionId));
        } catch (Exception e) {
            log.warn("email_watch fetch failed for sub={}: {}", subscriptionId, e.getMessage());
            return 0;
        }
        int delivered = 0;
        for (var m : msgs) {
            var event = InboundEvent.of("email", m.from(), m.toPayload());
            var outcome = dispatch.dispatch(subscriptionId, event);
            if (outcome.decision() == InboundSubscriptionRegistry.DeliveryDecision.DELIVER) {
                delivered++;
            }
            if (m.uid() != null) lastSeen.put(subscriptionId, m.uid());
        }
        return delivered;
    }

    private void arm(String id, int intervalSeconds) {
        if (scheduler == null) return;
        var prev = tasks.remove(id);
        if (prev != null) prev.cancel(false);
        var f = scheduler.scheduleAtFixedRate(() -> {
            try { poll(id); } catch (Exception e) {
                log.debug("email_watch poll error: {}", e.getMessage());
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        tasks.put(id, f);
    }

    /** Stop the poller (also called via cancel). */
    public void disarm(String subscriptionId) {
        var f = tasks.remove(subscriptionId);
        if (f != null) f.cancel(false);
        lastSeen.remove(subscriptionId);
    }
}
