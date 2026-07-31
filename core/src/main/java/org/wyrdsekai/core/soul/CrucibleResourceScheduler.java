package org.wyrdsekai.core.soul;

import java.time.Instant;
import java.util.*;

/**
 * Resource scheduling for Crucible growth cycles (§85.17.3).
 *
 * Variant count scales with available compute discovered via Between topology:
 * - Household (1 GPU): 3-5 variants, sequential, Level 1 always possible
 * - Community (multi-node): N variants across M nodes, DDP via Q5
 * - Primary machine (4 GPUs): Many parallel variants, Q11 island model
 *
 * Level 1 (behavioral) is always possible — no GPU needed.
 * Level 2 (LoRA fine-tuning) requires GPU compute.
 * Level 3 (substrate changes) requires model download + GPU.
 */
public class CrucibleResourceScheduler {

    /** Resource tier based on available compute. */
    public enum ResourceTier {
        PHONE,      // Phone only, Level 1 only, 1 variant
        HOUSEHOLD,  // 1 GPU, 3-5 variants, Level 1-2
        COMMUNITY,  // Multi-node, N variants, Level 1-3
        PRIMARY     // 4+ GPUs, many parallel variants, full island model
    }

    /** A compute node available for growth. */
    public record ComputeNode(
        String nodeId,
        int gpuCount,
        long vramMb,
        String currentModel,
        boolean available,
        Instant lastSeen
    ) {
        /** Whether this node can run LoRA fine-tuning (needs GPU + VRAM). */
        public boolean canFineTune() {
            return gpuCount > 0 && vramMb >= 8_000; // 8GB minimum for QLoRA
        }

        /** Whether this node can host a model for inference. */
        public boolean canInfer() {
            return available;
        }
    }

    /** Scheduling plan for a growth cycle. */
    public record GrowthPlan(
        ResourceTier tier,
        int maxVariants,
        List<Integer> allowedLevels,
        boolean parallelEval,
        Map<String, String> nodeAssignments,  // variantId → nodeId
        Instant plannedAt
    ) {
        /** Whether Level 2 (LoRA) is possible. */
        public boolean canLevel2() {
            return allowedLevels.contains(2);
        }

        /** Whether Level 3 (substrate change) is possible. */
        public boolean canLevel3() {
            return allowedLevels.contains(3);
        }
    }

    private final Map<String, ComputeNode> nodes = new LinkedHashMap<>();

    /** Register a compute node. */
    public void registerNode(ComputeNode node) {
        nodes.put(node.nodeId(), node);
    }

    /** Remove a node (went offline). */
    public void removeNode(String nodeId) {
        nodes.remove(nodeId);
    }

    /** Get all registered nodes. */
    public Collection<ComputeNode> allNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    /** Determine resource tier from available nodes. */
    public ResourceTier determineTier() {
        int totalGpus = nodes.values().stream().mapToInt(ComputeNode::gpuCount).sum();
        long totalNodes = nodes.values().stream().filter(ComputeNode::available).count();

        if (totalGpus >= 4) return ResourceTier.PRIMARY;
        if (totalNodes > 1 && totalGpus > 0) return ResourceTier.COMMUNITY;
        if (totalGpus > 0) return ResourceTier.HOUSEHOLD;
        return ResourceTier.PHONE;
    }

    /**
     * Plan a growth cycle based on available resources.
     *
     * @param requestedLevel   Desired modification level (1, 2, or 3)
     * @param requestedVariants Desired number of variants
     * @return Growth plan with actual capabilities
     */
    public GrowthPlan plan(int requestedLevel, int requestedVariants) {
        var tier = determineTier();
        var allowedLevels = new ArrayList<Integer>();
        allowedLevels.add(1); // Level 1 always available

        int maxVariants;
        boolean parallel;

        switch (tier) {
            case PHONE -> {
                maxVariants = 1;
                parallel = false;
            }
            case HOUSEHOLD -> {
                maxVariants = Math.min(requestedVariants, 5);
                parallel = false;
                if (hasFineTuneCapability()) allowedLevels.add(2);
            }
            case COMMUNITY -> {
                maxVariants = Math.min(requestedVariants, 10);
                parallel = true;
                if (hasFineTuneCapability()) allowedLevels.add(2);
                allowedLevels.add(3);
            }
            case PRIMARY -> {
                maxVariants = requestedVariants; // No cap
                parallel = true;
                allowedLevels.add(2);
                allowedLevels.add(3);
            }
            default -> {
                maxVariants = 1;
                parallel = false;
            }
        }

        // Cap to actual allowed level
        if (requestedLevel > Collections.max(allowedLevels)) {
            // Downgrade to highest available level
        }

        // Assign variants to nodes (round-robin for parallel)
        var assignments = new LinkedHashMap<String, String>();
        if (parallel) {
            var availableNodes = nodes.values().stream()
                .filter(ComputeNode::available).toList();
            for (int i = 0; i < maxVariants; i++) {
                var node = availableNodes.get(i % availableNodes.size());
                assignments.put("variant-" + i, node.nodeId());
            }
        } else if (!nodes.isEmpty()) {
            var primary = nodes.values().iterator().next();
            for (int i = 0; i < maxVariants; i++) {
                assignments.put("variant-" + i, primary.nodeId());
            }
        }

        return new GrowthPlan(tier, maxVariants,
            Collections.unmodifiableList(allowedLevels), parallel,
            Collections.unmodifiableMap(assignments), Instant.now());
    }

    /** Check if any node can do fine-tuning. */
    public boolean hasFineTuneCapability() {
        return nodes.values().stream().anyMatch(ComputeNode::canFineTune);
    }

    /** Total available VRAM across all nodes. */
    public long totalAvailableVram() {
        return nodes.values().stream()
            .filter(ComputeNode::available)
            .mapToLong(ComputeNode::vramMb)
            .sum();
    }

    /** Count of available GPU nodes. */
    public int availableGpuNodes() {
        return (int) nodes.values().stream()
            .filter(n -> n.available() && n.gpuCount() > 0)
            .count();
    }
}
