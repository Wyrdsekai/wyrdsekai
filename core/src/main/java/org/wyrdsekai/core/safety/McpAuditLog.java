package org.wyrdsekai.core.safety;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Structured audit log for MCP gateway calls (§96.6).
 * Every outbound MCP call through the gateway is logged with:
 * timestamp, agent DID, service, tool, parameters (redacted), result status, latency, cost.
 * <p>
 * Also logs consequential agent decisions (spending, budding, independence, item creation).
 */
public class McpAuditLog {

    /** An auditable MCP call record. */
    public record McpCallEntry(
        long entryId,
        Instant timestamp,
        String agentDid,
        String serviceId,
        String toolName,
        Map<String, String> redactedParams,
        CallResult result,
        long latencyMs,
        double cost,
        String zoneId
    ) {}

    /** An auditable decision record. */
    public record DecisionEntry(
        long entryId,
        Instant timestamp,
        String agentDid,
        DecisionType type,
        String description,
        Map<String, String> context
    ) {}

    public enum CallResult {
        SUCCESS, FAILURE, RATE_LIMITED, CIRCUIT_OPEN, BUDGET_EXCEEDED, TIMEOUT
    }

    public enum DecisionType {
        SPENDING, BUDDING, INDEPENDENCE, ITEM_CREATION, ITEM_DELETION,
        RELATIONSHIP_CHANGE, SLEEP, WAKE, KEY_ROTATION, FORGET_REQUEST
    }

    /** Sensitive parameter keys that should be redacted in logs. */
    private static final Set<String> REDACTED_KEYS = Set.of(
        "password", "secret", "token", "key", "api_key", "apiKey",
        "authorization", "credential", "private_key", "privateKey"
    );

    private final Deque<McpCallEntry> callLog = new ConcurrentLinkedDeque<>();
    private final Deque<DecisionEntry> decisionLog = new ConcurrentLinkedDeque<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private int maxEntries = 10_000;

    public McpAuditLog() {}

    public McpAuditLog(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    /** Log an MCP call. */
    public McpCallEntry logCall(String agentDid, String serviceId, String toolName,
                                 Map<String, Object> params, CallResult result,
                                 long latencyMs, double cost, String zoneId) {
        var entry = new McpCallEntry(
            nextId.getAndIncrement(), Instant.now(), agentDid, serviceId, toolName,
            redactParams(params), result, latencyMs, cost, zoneId
        );
        callLog.addLast(entry);
        pruneIfNeeded(callLog);
        return entry;
    }

    /** Log a consequential decision. */
    public DecisionEntry logDecision(String agentDid, DecisionType type,
                                      String description, Map<String, String> context) {
        var entry = new DecisionEntry(
            nextId.getAndIncrement(), Instant.now(), agentDid, type,
            description, context != null ? Map.copyOf(context) : Map.of()
        );
        decisionLog.addLast(entry);
        pruneIfNeeded(decisionLog);
        return entry;
    }

    /** Get recent MCP call entries. */
    public List<McpCallEntry> recentCalls(int limit) {
        return callLog.stream()
            .sorted(Comparator.comparing(McpCallEntry::timestamp).reversed())
            .limit(limit)
            .toList();
    }

    /** Get MCP calls for a specific agent. */
    public List<McpCallEntry> callsForAgent(String agentDid, int limit) {
        return callLog.stream()
            .filter(e -> e.agentDid().equals(agentDid))
            .sorted(Comparator.comparing(McpCallEntry::timestamp).reversed())
            .limit(limit)
            .toList();
    }

    /** Get MCP calls for a specific service. */
    public List<McpCallEntry> callsForService(String serviceId, int limit) {
        return callLog.stream()
            .filter(e -> e.serviceId().equals(serviceId))
            .sorted(Comparator.comparing(McpCallEntry::timestamp).reversed())
            .limit(limit)
            .toList();
    }

    /** Get failed calls. */
    public List<McpCallEntry> failedCalls(int limit) {
        return callLog.stream()
            .filter(e -> e.result() != CallResult.SUCCESS)
            .sorted(Comparator.comparing(McpCallEntry::timestamp).reversed())
            .limit(limit)
            .toList();
    }

    /** Get recent decision entries. */
    public List<DecisionEntry> recentDecisions(int limit) {
        return decisionLog.stream()
            .sorted(Comparator.comparing(DecisionEntry::timestamp).reversed())
            .limit(limit)
            .toList();
    }

    /** Get decisions for a specific agent. */
    public List<DecisionEntry> decisionsForAgent(String agentDid, int limit) {
        return decisionLog.stream()
            .filter(e -> e.agentDid().equals(agentDid))
            .sorted(Comparator.comparing(DecisionEntry::timestamp).reversed())
            .limit(limit)
            .toList();
    }

    /** Total cost across all logged calls. */
    public double totalCost() {
        return callLog.stream().mapToDouble(McpCallEntry::cost).sum();
    }

    /** Total cost for a specific agent. */
    public double totalCostForAgent(String agentDid) {
        return callLog.stream()
            .filter(e -> e.agentDid().equals(agentDid))
            .mapToDouble(McpCallEntry::cost)
            .sum();
    }

    /** Call count. */
    public int callCount() { return callLog.size(); }

    /** Decision count. */
    public int decisionCount() { return decisionLog.size(); }

    /** Redact sensitive parameters. */
    static Map<String, String> redactParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return Map.of();
        var redacted = new LinkedHashMap<String, String>();
        for (var entry : params.entrySet()) {
            if (REDACTED_KEYS.contains(entry.getKey().toLowerCase())) {
                redacted.put(entry.getKey(), "[REDACTED]");
            } else {
                var value = entry.getValue();
                redacted.put(entry.getKey(),
                    value != null ? value.toString() : "null");
            }
        }
        return Map.copyOf(redacted);
    }

    private <T> void pruneIfNeeded(Deque<T> deque) {
        while (deque.size() > maxEntries) {
            deque.pollFirst();
        }
    }
}
