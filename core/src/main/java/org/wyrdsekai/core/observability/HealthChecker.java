package org.wyrdsekai.core.observability;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodic agent and system health monitoring (§105).
 * Runs health checks and aggregates results for the ER room.
 */
public class HealthChecker {

    /** A health check result. */
    public record HealthCheck(
        String checkId,
        String component,
        CheckType type,
        HealthLevel level,
        String message,
        Instant checkedAt,
        Map<String, String> metadata
    ) {}

    public enum CheckType {
        AGENT_VITALITY, AGENT_MEMORY, AGENT_IDENTITY,
        ROOM_SCRIPT, MCP_SERVICE, BETWEEN_NODE,
        SYSTEM_STORAGE, SYSTEM_COMPUTE, SYSTEM_NETWORK
    }

    public enum HealthLevel {
        HEALTHY, DEGRADED, CRITICAL, UNKNOWN
    }

    private final Map<String, HealthCheck> lastChecks = new ConcurrentHashMap<>();

    /** Record a health check result. */
    public HealthCheck record(String component, CheckType type, HealthLevel level,
                               String message, Map<String, String> metadata) {
        var check = new HealthCheck(
            "hc-" + component + "-" + type.name().toLowerCase(),
            component, type, level, message, Instant.now(),
            metadata != null ? Map.copyOf(metadata) : Map.of()
        );
        lastChecks.put(check.checkId(), check);
        return check;
    }

    /** Get the latest check for a component. */
    public Optional<HealthCheck> latestFor(String component) {
        return lastChecks.values().stream()
            .filter(c -> c.component().equals(component))
            .max(Comparator.comparing(HealthCheck::checkedAt));
    }

    /** Get all critical health issues. */
    public List<HealthCheck> criticalIssues() {
        return lastChecks.values().stream()
            .filter(c -> c.level() == HealthLevel.CRITICAL)
            .sorted(Comparator.comparing(HealthCheck::checkedAt).reversed())
            .toList();
    }

    /** Get all degraded or worse. */
    public List<HealthCheck> unhealthy() {
        return lastChecks.values().stream()
            .filter(c -> c.level() != HealthLevel.HEALTHY)
            .sorted(Comparator.comparing(HealthCheck::checkedAt).reversed())
            .toList();
    }

    /** Overall system health. */
    public HealthLevel overallHealth() {
        var checks = lastChecks.values();
        if (checks.isEmpty()) return HealthLevel.UNKNOWN;
        if (checks.stream().anyMatch(c -> c.level() == HealthLevel.CRITICAL))
            return HealthLevel.CRITICAL;
        if (checks.stream().anyMatch(c -> c.level() == HealthLevel.DEGRADED))
            return HealthLevel.DEGRADED;
        return HealthLevel.HEALTHY;
    }

    /** Human-readable health summary. */
    public String describe() {
        var sb = new StringBuilder("=== System Health ===\n");
        sb.append("Overall: ").append(overallHealth()).append("\n\n");
        for (var check : lastChecks.values().stream()
                .sorted(Comparator.comparing(HealthCheck::component)).toList()) {
            sb.append(check.component()).append(" [").append(check.type()).append("]: ")
                .append(check.level()).append(" — ").append(check.message()).append("\n");
        }
        return sb.toString();
    }

    public int checkCount() { return lastChecks.size(); }
}
