package org.wyrdsekai.core.economy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.QuotaPolicy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Records cross-zone resource consumption events (MeteringEvent).
 *
 * <p>For v1, metering is INFORMATIONAL — it
 * tracks usage against quotas for enforcement but doesn't debit CU balances.
 * In v2, this same data drives actual CU settlement via Gate escrow.</p>
 *
 * <p>Thread-safe singleton. Buffered in memory with bounded history.</p>
 */
public final class MeteringService {

    private static final Logger log = LoggerFactory.getLogger(MeteringService.class);
    private static final int MAX_EVENTS = 10_000;

    private static volatile MeteringService instance;

    public static void init() {
        instance = new MeteringService();
    }

    public static MeteringService get() {
        return instance;
    }

    /**
     * A single cross-zone consumption event.
     *
     * @param requestingZone  zone making the request
     * @param providingZone   zone providing the service
     * @param serviceClass    service class key (see ReferenceRates)
     * @param units           units consumed (tokens/1000, GB-hours, GB, minutes)
     * @param cuEquivalent    CU cost computed at reference rates
     * @param entityId        entity that triggered the usage
     * @param timestamp       when the event occurred
     */
    public record MeteringEvent(
        String requestingZone,
        String providingZone,
        String serviceClass,
        double units,
        double cuEquivalent,
        String entityId,
        Instant timestamp
    ) {}

    /** Daily usage totals per (bilateral-pair, service-class). */
    public record DailyUsage(
        String partnerZone,
        String serviceClass,
        LocalDate date,
        long totalUnits,
        double totalCU,
        int eventCount
    ) {}

    /** Recent events (bounded). */
    private final ConcurrentLinkedDeque<MeteringEvent> events =
        new ConcurrentLinkedDeque<>();

    /** Daily usage summaries: key = "{partner}|{serviceClass}|{date}" */
    private final ConcurrentHashMap<String, DailyUsage> dailyUsage = new ConcurrentHashMap<>();

    /**
     * Record a cross-zone consumption event.
     *
     * @return the computed CU equivalent (informational in v1)
     */
    public double record(String requestingZone, String providingZone, String serviceClass,
                         double units, String entityId) {
        return record(requestingZone, providingZone, serviceClass, units, entityId, 1.0);
    }

    /**
     * Record with a bilateral multiplier (e.g., 0.5 for partner rate, 0.0 for family).
     */
    public double record(String requestingZone, String providingZone, String serviceClass,
                         double units, String entityId, double bilateralMultiplier) {
        var cuEquivalent = ReferenceRates.calculate(serviceClass, units, bilateralMultiplier);
        var event = new MeteringEvent(
            requestingZone, providingZone, serviceClass, units,
            cuEquivalent, entityId, Instant.now());

        events.addFirst(event);
        while (events.size() > MAX_EVENTS) {
            events.removeLast();
        }

        updateDailyUsage(event);

        log.debug("Metered: {} {} {} units ({} CU) from {} to {}",
            entityId, serviceClass, units, cuEquivalent, requestingZone, providingZone);

        return cuEquivalent;
    }

    private void updateDailyUsage(MeteringEvent event) {
        var date = event.timestamp().atZone(ZoneOffset.UTC).toLocalDate();
        // The "partner" is the providing zone (the counterparty we consumed from).
        // Caller uses requestingZone = our zone, providingZone = partner.
        var partner = event.providingZone();
        var key = partner + "|" + event.serviceClass() + "|" + date;

        dailyUsage.merge(key,
            new DailyUsage(partner, event.serviceClass(), date,
                (long) event.units(), event.cuEquivalent(), 1),
            (existing, incoming) -> new DailyUsage(
                existing.partnerZone(),
                existing.serviceClass(),
                existing.date(),
                existing.totalUnits() + incoming.totalUnits(),
                existing.totalCU() + incoming.totalCU(),
                existing.eventCount() + incoming.eventCount()
            ));
    }

    /** Get today's usage for a specific partner+service. */
    public DailyUsage usageToday(String partnerZone, String serviceClass) {
        var date = LocalDate.now(ZoneOffset.UTC);
        var key = partnerZone + "|" + serviceClass + "|" + date;
        // Each event is tracked for BOTH zones, so we divide by 2 would be incorrect.
        // Instead, we keep separate entries: lookup by target zone returns the combined view.
        return dailyUsage.getOrDefault(key,
            new DailyUsage(partnerZone, serviceClass, date, 0, 0, 0));
    }

    /** Get today's total inference tokens used with a partner. */
    public long inferenceTokensToday(String partnerZone) {
        var small = usageToday(partnerZone, ReferenceRates.SERVICE_INFERENCE_SMALL);
        var large = usageToday(partnerZone, ReferenceRates.SERVICE_INFERENCE_LARGE);
        return small.totalUnits() * 1000 + large.totalUnits() * 1000;
    }

    /** Get today's total bandwidth used with a partner (bytes). */
    public long bandwidthToday(String partnerZone) {
        var usage = usageToday(partnerZone, ReferenceRates.SERVICE_BANDWIDTH);
        return usage.totalUnits() * 1024L * 1024L * 1024L;  // GB → bytes
    }

    /** Get all daily usage entries (for Counting House display). */
    public List<DailyUsage> allDailyUsage() {
        return new ArrayList<>(dailyUsage.values());
    }

    /** Check if a request would exceed the bilateral quota. */
    public boolean withinQuota(String partnerZone, QuotaPolicy quota,
                               String serviceClass, long requestUnits) {
        if (quota == null) return true;
        return switch (serviceClass) {
            case ReferenceRates.SERVICE_INFERENCE_SMALL,
                 ReferenceRates.SERVICE_INFERENCE_LARGE ->
                quota.allowInference(inferenceTokensToday(partnerZone), requestUnits * 1000);
            case ReferenceRates.SERVICE_BANDWIDTH ->
                quota.allowBandwidth(bandwidthToday(partnerZone), requestUnits * 1024L * 1024L * 1024L);
            default -> true;
        };
    }

    /** Recent events (most recent first). */
    public List<MeteringEvent> recentEvents(int limit) {
        var result = new ArrayList<MeteringEvent>();
        for (var event : events) {
            result.add(event);
            if (result.size() >= limit) break;
        }
        return result;
    }

    /** Total events recorded. */
    public int eventCount() {
        return events.size();
    }

    /** Clear all records (test only). */
    public void clear() {
        events.clear();
        dailyUsage.clear();
    }
}
