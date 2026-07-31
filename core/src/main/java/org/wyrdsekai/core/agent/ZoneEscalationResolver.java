package org.wyrdsekai.core.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Resolves escalation options for phone companions that cannot
 * handle a capability request locally (resource constraints).
 *
 * Four escalation tiers (companion chooses):
 * 1. Relay inference — route code-gen to zone LLM via InferenceRouter
 * 2. Visit Workshop — visit Workshop on capable node via Between transit
 * 3. Ask peer — broadcast CapabilityRequest, peer creates skill, pushes to FamilyLocker
 * 4. Bud — spawn child bud on capable node for persistent development
 *
 * Uses ZoneNode records (converted from TopologyRegister by the caller)
 * to avoid coupling core module to the between module.
 */
public final class ZoneEscalationResolver {

    private ZoneEscalationResolver() {}

    /**
     * Simplified view of a zone peer node.
     * Callers create these from TopologyRegister.ConnectionState.
     */
    public record ZoneNode(
        String nodeId,
        boolean connected,
        double latencyMs,
        Map<String, String> capabilities
    ) {
        public boolean hasInference() {
            return capabilities != null && capabilities.containsKey("inference_model");
        }
        public boolean hasGpu() {
            return capabilities != null
                && capabilities.containsKey("gpu_count")
                && !"0".equals(capabilities.get("gpu_count"));
        }
        public String inferenceModel() {
            return capabilities != null
                ? capabilities.getOrDefault("inference_model", "unknown")
                : "unknown";
        }
    }

    /** An available escalation path with its target node and cost. */
    public record EscalationOption(
        EscalationTier tier,
        String targetNodeId,
        String description,
        double estimatedEnergyCost,
        double estimatedLatencyMs
    ) {}

    /** Escalation tiers ordered by increasing cost and capability. */
    public enum EscalationTier {
        /** Route inference to a zone node's LLM. Cheapest, fastest. */
        RELAY_INFERENCE,
        /** Visit the Workshop room on a capable node. Mid-cost. */
        VISIT_WORKSHOP,
        /** Ask a peer companion to create the skill. Async, mid-cost. */
        ASK_PEER,
        /** Spawn a child bud on a capable node. Most expensive, persistent. */
        BUD
    }

    /**
     * Resolve available escalation options from zone peer nodes.
     * Returns options sorted by estimated cost (cheapest first).
     *
     * @param zoneNodes    Discovered zone peers (empty list if offline)
     * @param localNodeId  This node's ID (excluded from relay targets)
     * @param needsGpu     Whether the task requires GPU (code generation typically does)
     * @return Sorted list of available escalation options
     */
    public static List<EscalationOption> resolve(
            List<ZoneNode> zoneNodes, String localNodeId, boolean needsGpu) {
        if (zoneNodes == null || zoneNodes.isEmpty()) return List.of();

        var options = new ArrayList<EscalationOption>();

        for (var node : zoneNodes) {
            if (node.nodeId().equals(localNodeId)) continue;
            if (!node.connected()) continue;

            // Skip GPU-required tasks if node has no GPU
            if (needsGpu && !node.hasGpu()) continue;

            // Tier 1: Relay inference (if node has an inference model)
            if (node.hasInference()) {
                options.add(new EscalationOption(
                    EscalationTier.RELAY_INFERENCE,
                    node.nodeId(),
                    "Relay inference to " + node.nodeId() + " (" + node.inferenceModel() + ")",
                    0.08,
                    node.latencyMs() + 500
                ));
            }

            // Tier 2: Visit Workshop (if node has inference)
            if (node.hasInference()) {
                options.add(new EscalationOption(
                    EscalationTier.VISIT_WORKSHOP,
                    node.nodeId(),
                    "Visit Workshop on " + node.nodeId(),
                    0.12,
                    node.latencyMs() + 2000
                ));
            }

            // Tier 3: Ask peer (any connected node with inference)
            if (node.hasInference()) {
                options.add(new EscalationOption(
                    EscalationTier.ASK_PEER,
                    node.nodeId(),
                    "Ask peer on " + node.nodeId() + " to create skill",
                    0.10,
                    node.latencyMs() + 5000
                ));
            }

            // Tier 4: Bud (needs GPU for persistent development)
            if (node.hasGpu()) {
                options.add(new EscalationOption(
                    EscalationTier.BUD,
                    node.nodeId(),
                    "Spawn bud on " + node.nodeId() + " for persistent development",
                    0.25,
                    node.latencyMs() + 10000
                ));
            }
        }

        // Sort by estimated energy cost (cheapest first)
        options.sort((a, b) -> Double.compare(a.estimatedEnergyCost, b.estimatedEnergyCost));
        return options;
    }

    /**
     * Build a human-readable zone context string from zone peers.
     * Used by CapabilityContextBuilder for Layer 2.7.
     *
     * @param zoneNodes    Discovered zone peers
     * @param localNodeId  This node's ID
     * @return Zone context string or null if no peers
     */
    public static String buildZoneContext(List<ZoneNode> zoneNodes, String localNodeId) {
        if (zoneNodes == null || zoneNodes.isEmpty()) return null;

        var connected = zoneNodes.stream()
            .filter(n -> n.connected() && !n.nodeId().equals(localNodeId))
            .toList();
        if (connected.isEmpty()) return null;

        var sb = new StringBuilder();
        sb.append("## Household Zone (").append(connected.size()).append(" peers)\n");

        for (var node : connected) {
            sb.append("- ").append(node.nodeId());
            if (node.hasInference()) {
                sb.append(": ").append(node.inferenceModel());
            }
            if (node.hasGpu()) {
                sb.append(" [GPU");
                var caps = node.capabilities();
                if (caps != null && caps.containsKey("gpu_free_vram_mb")) {
                    sb.append(" ").append(caps.get("gpu_free_vram_mb")).append("MB free");
                }
                sb.append("]");
            }
            if (node.latencyMs() > 0) {
                sb.append(String.format(" (%.0fms)", node.latencyMs()));
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
