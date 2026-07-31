package org.wyrdsekai.core.external.t;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.item.ItemScheduleService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * (Phase T) — bridge between Phase A2's
 * {@link ItemScheduleService} (the scheduler side: arm/cancel/list timers) and
 * the Phase T listener model (the listener side: deliver an
 * {@link InboundEvent} to a hook when a scheduled timer fires).
 *
 * <p>Phase T's task brief calls out the overlap with {@code world.schedule.*}
 * from A2. Rather than build a parallel cron/timer engine, we reuse the
 * scheduler and register a fire-listener that translates each scheduled fire
 * into an {@link InboundEvent} of kind {@code "scheduled"}, then routes via
 * {@link InboundDispatchService}.</p>
 *
 * <p>One {@link InboundSubscriptionRegistry} entry mirrors each scheduled
 * timer for {@code world.inbound.list} consistency, but the actual timer state
 * lives in {@code item_schedules} (A2). Cancel/pause/resume on the inbound
 * side cascades to the schedule service.</p>
 */
public final class ScheduledListenerBridge {

    private static final Logger log = LoggerFactory.getLogger(ScheduledListenerBridge.class);

    private final InboundSubscriptionRegistry registry;
    private final InboundDispatchService dispatch;
    private final ItemScheduleService scheduleService;

    public ScheduledListenerBridge(InboundSubscriptionRegistry registry,
                                     InboundDispatchService dispatch,
                                     ItemScheduleService scheduleService) {
        this.registry = registry;
        this.dispatch = dispatch;
        this.scheduleService = scheduleService;
    }

    /**
     * Wire the scheduler's fire-listener so each timer fire dispatches to the
     * corresponding inbound subscription (if any). Idempotent — calling twice
     * is harmless because A2 keeps a single fire-listener slot.
     */
    public void wireFireListener() {
        if (scheduleService == null) {
            log.debug("ScheduledListenerBridge: no schedule service, skipping wire");
            return;
        }
        scheduleService.setFireListener((timerId, schedule) -> {
            // Find the inbound sub that mirrors this timer. We tag it via opts.
            var match = registry.byKind("scheduled").stream()
                .filter(s -> timerId.equals(s.opts().get("timerId")))
                .findFirst()
                .orElse(null);
            if (match == null) return;  // schedule fire that isn't an inbound sub
            var payload = Map.<String, Object>of(
                "timerId", timerId,
                "hookName", schedule.hookName(),
                "fireAt", System.currentTimeMillis(),
                "schedulePayload", schedule.payload());
            dispatch.dispatch(match.subscriptionId(),
                InboundEvent.of("scheduled", "schedule:" + timerId, payload));
        });
    }

    /**
     * Subscribe — creates a scheduled timer in A2 + an inbound subscription
     * mirror. The timer fires on the supplied cron expression (A2's tiny
     * subset) or fixed interval.
     */
    public Map<String, Object> subscribe(String itemId, String agentId, String cronExpr,
                                            String hookName, Map<String, Object> opts) {
        if (scheduleService == null) {
            return Map.of("ok", false, "error", "schedule service not wired");
        }
        Map<String, Object> schedRes;
        if (cronExpr != null && !cronExpr.isBlank()) {
            schedRes = scheduleService.scheduleCron(agentId, cronExpr, hookName, opts);
        } else if (opts != null && opts.get("intervalSeconds") instanceof Number n) {
            schedRes = scheduleService.scheduleEvery(agentId, n.longValue(), hookName, opts);
        } else {
            return Map.of("ok", false, "error", "cronExpr or opts.intervalSeconds required");
        }
        if (!Boolean.TRUE.equals(schedRes.get("ok"))) return schedRes;
        var timerId = String.valueOf(schedRes.get("timerId"));
        var subOpts = new LinkedHashMap<String, Object>();
        if (opts != null) subOpts.putAll(opts);
        subOpts.put("timerId", timerId);
        if (cronExpr != null) subOpts.put("cronExpr", cronExpr);
        var subId = registry.add(itemId, agentId, "scheduled", hookName,
            cronExpr == null ? "every:" + opts.get("intervalSeconds") : cronExpr,
            subOpts, null,
            opts == null ? null : (opts.get("capPerHour") instanceof Number n ? n.intValue() : null));
        return Map.of("ok", true, "subscriptionId", subId, "timerId", timerId);
    }

    /** Cascade cancel: remove the timer + the mirror subscription. */
    public boolean cancel(String agentId, String subscriptionId) {
        var sub = registry.find(subscriptionId).orElse(null);
        if (sub == null || !"scheduled".equals(sub.kind())) return false;
        var timerId = String.valueOf(sub.opts().get("timerId"));
        if (scheduleService != null && timerId != null && !timerId.isBlank()) {
            scheduleService.cancel(agentId, timerId);
        }
        return registry.cancel(agentId, subscriptionId);
    }
}
