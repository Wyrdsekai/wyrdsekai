package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.NatsBridge;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Household observability layer (Wave 8: Observability).
 *
 * Provides: service registry, job monitor, crash dumps, watchdog, cost dashboard.
 * All data aggregated from NATS gossip — visible from The Bridge's command console.
 */
public final class HouseholdObservability {

    private static final Logger log = LoggerFactory.getLogger(HouseholdObservability.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    // ── Service Registry ──

    /**
     * A registered service visible in the household service registry.
     */
    public record ServiceEntry(
        @JsonProperty("serviceId") String serviceId,
        @JsonProperty("serviceName") String serviceName,
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("state") String state,       // READY, BUSY, SLEEPING, STARTING, DOWN
        @JsonProperty("details") Map<String, String> details,  // service-specific stats
        @JsonProperty("lastSeen") Instant lastSeen
    ) {
        @JsonCreator
        public ServiceEntry {}
    }

    // ── Crash Dumps ──

    /**
     * A crash dump for a service or companion.
     */
    public record CrashDump(
        @JsonProperty("id") String id,
        @JsonProperty("serviceId") String serviceId,
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("errorMessage") String errorMessage,
        @JsonProperty("stackTrace") String stackTrace,
        @JsonProperty("context") Map<String, String> context,  // room, last action, state
        @JsonProperty("timestamp") Instant timestamp
    ) {
        @JsonCreator
        public CrashDump {}
    }

    // ── Watchdog ──

    /**
     * Watchdog alert — raised when a service doesn't heartbeat within expected interval.
     */
    public record WatchdogAlert(
        @JsonProperty("serviceId") String serviceId,
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("alertType") String alertType,  // TIMEOUT, DEGRADED, RESTARTED, DOWN
        @JsonProperty("message") String message,
        @JsonProperty("timestamp") Instant timestamp
    ) {
        @JsonCreator
        public WatchdogAlert {}
    }

    // ── Cost Tracking ──

    /**
     * Per-member cost entry for the household treasury.
     */
    public record MemberCost(
        @JsonProperty("memberId") String memberId,
        @JsonProperty("inferenceQueries") long inferenceQueries,
        @JsonProperty("apiCalls") long apiCalls,
        @JsonProperty("tokensUsed") long tokensUsed,
        @JsonProperty("storageBytes") long storageBytes,
        @JsonProperty("estimatedCostCents") long estimatedCostCents,
        @JsonProperty("period") String period,  // "2026-04-09" or "2026-04"
        @JsonProperty("timestamp") Instant timestamp
    ) {
        @JsonCreator
        public MemberCost {}
    }

    // ── State ──

    private final NatsBridge nats;
    private final String localNodeId;

    /** Service registry: serviceId → entry. */
    private final ConcurrentHashMap<String, ServiceEntry> services = new ConcurrentHashMap<>();

    /** Crash dump history (bounded). */
    private final Deque<CrashDump> crashDumps = new ConcurrentLinkedDeque<>();
    private static final int MAX_CRASH_DUMPS = 100;

    /** Active watchdog alerts. */
    private final ConcurrentHashMap<String, WatchdogAlert> activeAlerts = new ConcurrentHashMap<>();

    /** Cost tracking per member. */
    private final ConcurrentHashMap<String, MemberCost> memberCosts = new ConcurrentHashMap<>();

    /** Watchdog monitors: serviceId → expected heartbeat interval. */
    private final ConcurrentHashMap<String, Duration> watchdogMonitors = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        r -> { var t = new Thread(r, "household-observability"); t.setDaemon(true); return t; });

    public HouseholdObservability(NatsBridge nats, String localNodeId) {
        this.nats = nats;
        this.localNodeId = localNodeId;
    }

    // ── Service Registry ──

    /** Register a local service. Published to NATS for household visibility. */
    public void registerService(String serviceId, String serviceName,
                                 String state, Map<String, String> details) {
        var entry = new ServiceEntry(serviceId, serviceName, localNodeId,
            state, details != null ? details : Map.of(), Instant.now());
        services.put(serviceId, entry);
        nats.broadcast("observability", "service", MAPPER.valueToTree(entry));
    }

    /** Update a service's state. */
    public void updateServiceState(String serviceId, String state, Map<String, String> details) {
        var existing = services.get(serviceId);
        if (existing == null) return;
        var updated = new ServiceEntry(existing.serviceId(), existing.serviceName(),
            existing.nodeId(), state, details != null ? details : existing.details(),
            Instant.now());
        services.put(serviceId, updated);
        nats.broadcast("observability", "service", MAPPER.valueToTree(updated));
    }

    /** Get all registered services. */
    public List<ServiceEntry> getServices() {
        return services.values().stream()
            .sorted(Comparator.comparing(ServiceEntry::serviceName))
            .toList();
    }

    // ── Crash Dumps ──

    /** Record a crash dump. Published to NATS and stored locally. */
    public void recordCrash(String serviceId, String errorMessage, String stackTrace,
                            Map<String, String> context) {
        var dump = new CrashDump(
            UUID.randomUUID().toString().substring(0, 8),
            serviceId, localNodeId, errorMessage,
            stackTrace != null ? stackTrace.substring(0, Math.min(stackTrace.length(), 4000)) : null,
            context, Instant.now());
        crashDumps.addFirst(dump);
        while (crashDumps.size() > MAX_CRASH_DUMPS) crashDumps.removeLast();
        nats.broadcast("observability", "crash", MAPPER.valueToTree(dump));
        log.error("Crash dump recorded: {} on {}: {}", serviceId, localNodeId, errorMessage);
    }

    /** Get recent crash dumps. */
    public List<CrashDump> getRecentCrashes(int limit) {
        return crashDumps.stream().limit(limit).toList();
    }

    // ── Watchdog ──

    /** Register a service for watchdog monitoring. */
    public void monitorService(String serviceId, Duration expectedInterval) {
        watchdogMonitors.put(serviceId, expectedInterval);
    }

    /** Start the watchdog checker. */
    public void startWatchdog() {
        scheduler.scheduleAtFixedRate(() -> {
            var now = Instant.now();
            for (var entry : watchdogMonitors.entrySet()) {
                var serviceId = entry.getKey();
                var expectedInterval = entry.getValue();
                var service = services.get(serviceId);

                if (service == null) continue;
                var age = Duration.between(service.lastSeen(), now);
                if (age.compareTo(expectedInterval.multipliedBy(3)) > 0) {
                    var alert = new WatchdogAlert(serviceId, service.nodeId(),
                        "TIMEOUT", serviceId + " heartbeat timeout (" + age.toSeconds() + "s)",
                        now);
                    activeAlerts.put(serviceId, alert);
                    nats.broadcast("observability", "alert", MAPPER.valueToTree(alert));
                    log.warn("Watchdog alert: {} on {} — timeout {}s",
                        serviceId, service.nodeId(), age.toSeconds());
                } else {
                    activeAlerts.remove(serviceId);
                }
            }
        }, 10000, 10000, TimeUnit.MILLISECONDS);
    }

    /** Get active watchdog alerts. */
    public List<WatchdogAlert> getActiveAlerts() {
        return List.copyOf(activeAlerts.values());
    }

    // ── Cost Tracking ──

    /** Update cost tracking for a member. */
    public void updateMemberCost(MemberCost cost) {
        memberCosts.put(cost.memberId(), cost);
    }

    /** Get cost breakdown for all members. */
    public List<MemberCost> getCostBreakdown() {
        return memberCosts.values().stream()
            .sorted(Comparator.comparing(MemberCost::memberId))
            .toList();
    }

    // ── Replication ──

    /** Start subscribing to observability updates from other nodes. */
    public void startReplication() {
        nats.subscribeBroadcast("observability", "service", env -> {
            try {
                var entry = MAPPER.convertValue(env.payload(), ServiceEntry.class);
                services.put(entry.serviceId(), entry);
            } catch (Exception e) {
                log.debug("Failed to parse service entry: {}", e.getMessage());
            }
        });
        nats.subscribeBroadcast("observability", "crash", env -> {
            try {
                var dump = MAPPER.convertValue(env.payload(), CrashDump.class);
                crashDumps.addFirst(dump);
                while (crashDumps.size() > MAX_CRASH_DUMPS) crashDumps.removeLast();
            } catch (Exception e) {
                log.debug("Failed to parse crash dump: {}", e.getMessage());
            }
        });
        nats.subscribeBroadcast("observability", "alert", env -> {
            try {
                var alert = MAPPER.convertValue(env.payload(), WatchdogAlert.class);
                activeAlerts.put(alert.serviceId(), alert);
            } catch (Exception e) {
                log.debug("Failed to parse alert: {}", e.getMessage());
            }
        });
        log.info("HouseholdObservability: replication started");
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }
}
