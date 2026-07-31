package org.wyrdsekai.between.mcp;

import org.wyrdsekai.between.TopologyRegister;
import org.wyrdsekai.between.federation.BilateralAgreement;
import org.wyrdsekai.between.federation.FederationCouncil;
import org.wyrdsekai.between.traversal.BetweenTraversal;

import java.time.Instant;
import java.util.*;

/**
 * Exposes The Between as MCP tools (§81, §79).
 * Each tool maps to a Between capability that external agents can invoke.
 */
public class BetweenMcpServer {

    /** MCP tool definition. */
    public record ToolDef(
        String name,
        String description,
        Map<String, ParameterDef> parameters
    ) {}

    /** MCP parameter definition. */
    public record ParameterDef(
        String type,
        String description,
        boolean required
    ) {}

    /** MCP tool call result. */
    public record ToolResult(
        boolean success,
        String content,
        Map<String, Object> data
    ) {
        public static ToolResult success(String content) {
            return new ToolResult(true, content, Map.of());
        }

        public static ToolResult success(String content, Map<String, Object> data) {
            return new ToolResult(true, content, data);
        }

        public static ToolResult error(String message) {
            return new ToolResult(false, message, Map.of());
        }
    }

    private final TopologyRegister topology;
    private final BetweenTraversal traversal;
    private final FederationCouncil council;

    public BetweenMcpServer(TopologyRegister topology, BetweenTraversal traversal,
                             FederationCouncil council) {
        this.topology = topology;
        this.traversal = traversal;
        this.council = council;
    }

    /** List all available MCP tools. */
    public List<ToolDef> listTools() {
        return List.of(
            new ToolDef("between.topology", "Get the current Between topology — connected peers, latency, jitter",
                Map.of()),
            new ToolDef("between.traverse", "Begin a journey between zones through The Between",
                Map.of(
                    "agentId", new ParameterDef("string", "Agent ID", true),
                    "sourceZoneId", new ParameterDef("string", "Source zone ID", true),
                    "targetZoneId", new ParameterDef("string", "Target zone ID", true),
                    "latencyMs", new ParameterDef("number", "Measured RTT in milliseconds", true)
                )),
            new ToolDef("between.journey_status", "Check the status of an active journey",
                Map.of(
                    "journeyId", new ParameterDef("string", "Journey ID", true)
                )),
            new ToolDef("between.check_ban", "Check if an entity is banned from a zone",
                Map.of(
                    "entityId", new ParameterDef("string", "Entity ID to check", true),
                    "zoneId", new ParameterDef("string", "Zone ID to check", false)
                )),
            new ToolDef("between.active_bans", "List all active federation-wide bans",
                Map.of()),
            new ToolDef("between.active_journeys", "List all in-transit journeys",
                Map.of())
        );
    }

    /** Call a tool by name with arguments. */
    public ToolResult callTool(String toolName, Map<String, String> args) {
        return switch (toolName) {
            case "between.topology" -> handleTopology();
            case "between.traverse" -> handleTraverse(args);
            case "between.journey_status" -> handleJourneyStatus(args);
            case "between.check_ban" -> handleCheckBan(args);
            case "between.active_bans" -> handleActiveBans();
            case "between.active_journeys" -> handleActiveJourneys();
            default -> ToolResult.error("Unknown tool: " + toolName);
        };
    }

    /** Total number of registered tools. */
    public int toolCount() {
        return listTools().size();
    }

    // --- Tool handlers ---

    private ToolResult handleTopology() {
        var desc = topology.describe();
        var data = new HashMap<String, Object>();
        data.put("connectedNodes", topology.connectedNodeCount());
        return ToolResult.success(desc, data);
    }

    private ToolResult handleTraverse(Map<String, String> args) {
        var agentId = args.get("agentId");
        var source = args.get("sourceZoneId");
        var target = args.get("targetZoneId");
        var latencyStr = args.get("latencyMs");

        if (agentId == null || source == null || target == null || latencyStr == null) {
            return ToolResult.error("Missing required parameters: agentId, sourceZoneId, targetZoneId, latencyMs");
        }

        double latencyMs;
        try {
            latencyMs = Double.parseDouble(latencyStr);
        } catch (NumberFormatException e) {
            return ToolResult.error("Invalid latencyMs: " + latencyStr);
        }

        var telemetry = new BetweenTraversal.TelemetrySnapshot(latencyMs, 0, 0, 1, Instant.now());
        var journey = traversal.depart(agentId, source, target, telemetry);

        var data = new HashMap<String, Object>();
        data.put("journeyId", journey.journeyId());
        data.put("travelTimeMs", journey.travelTime().toMillis());
        data.put("narrative", journey.narrative());

        return ToolResult.success(String.join("\n", journey.narrative()), data);
    }

    private ToolResult handleJourneyStatus(Map<String, String> args) {
        var journeyId = args.get("journeyId");
        if (journeyId == null) return ToolResult.error("Missing journeyId");

        var journey = traversal.checkArrival(journeyId);
        if (journey == null) return ToolResult.error("Journey not found: " + journeyId);

        var data = new HashMap<String, Object>();
        data.put("status", journey.status().name());
        data.put("hasArrived", journey.hasArrived());
        data.put("elapsedMs", journey.elapsed().toMillis());
        data.put("travelTimeMs", journey.travelTime().toMillis());

        return ToolResult.success("Journey " + journeyId + ": " + journey.status().name(), data);
    }

    private ToolResult handleCheckBan(Map<String, String> args) {
        var entityId = args.get("entityId");
        if (entityId == null) return ToolResult.error("Missing entityId");

        var zoneId = args.get("zoneId");
        var check = zoneId != null
            ? council.checkBanForZone(entityId, zoneId)
            : council.checkBan(entityId);

        var data = new HashMap<String, Object>();
        data.put("banned", check.banned());
        if (check.banned()) {
            data.put("reason", check.reason());
            data.put("issuingZoneId", check.issuingZoneId());
        }

        return ToolResult.success(check.banned()
            ? "BANNED: " + check.reason()
            : "Clear — no active bans", data);
    }

    private ToolResult handleActiveBans() {
        var bans = council.federationWideBans();
        var data = new HashMap<String, Object>();
        data.put("count", bans.size());

        var sb = new StringBuilder("Federation-wide bans: ").append(bans.size()).append("\n");
        bans.forEach(b -> sb.append("  ").append(b.entityId())
            .append(" — ").append(b.reason())
            .append(" (by ").append(b.issuingZoneId()).append(")\n"));

        return ToolResult.success(sb.toString().stripTrailing(), data);
    }

    private ToolResult handleActiveJourneys() {
        var journeys = traversal.activeJourneys();
        var data = new HashMap<String, Object>();
        data.put("count", journeys.size());

        var sb = new StringBuilder("Active journeys: ").append(journeys.size()).append("\n");
        journeys.forEach(j -> sb.append("  ").append(j.journeyId())
            .append(": ").append(j.agentId())
            .append(" ").append(j.sourceZoneId()).append(" → ").append(j.targetZoneId())
            .append(" (").append(j.status()).append(")\n"));

        return ToolResult.success(sb.toString().stripTrailing(), data);
    }
}
