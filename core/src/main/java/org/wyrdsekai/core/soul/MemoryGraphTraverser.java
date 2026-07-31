package org.wyrdsekai.core.soul;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * In-memory graph traversal over CompactedMemory links.
 *
 * <p>Builds a bidirectional adjacency map from MemoryLink records and provides
 * BFS-based expansion, neighborhood queries, and shortest path for multi-hop
 * memory retrieval.
 *
 * <p>No external dependencies — pure Java. For Forge-time analysis (connected
 * components, PageRank), JGraphT can be added as an optional dependency.
 *
 * <p>Graph is rebuilt from CompactedMemory on each Forge cycle or lazily on
 * first query after memory changes.
 */
public final class MemoryGraphTraverser {

    private static final Logger log = LoggerFactory.getLogger(MemoryGraphTraverser.class);

    // Bidirectional adjacency: nodeId → set of (neighborId, strength, relation)
    private final Map<String, List<Edge>> adjacency = new HashMap<>();
    private final Map<String, MemoryNode> nodeIndex = new HashMap<>();
    private int nodeCount;
    private int edgeCount;

    /** An edge in the memory graph. */
    public record Edge(String targetId, float strength, String relation) {}

    /** A traversal result: a memory node with its graph distance from the seed. */
    public record TraversalResult(MemoryNode node, int distance, float pathStrength) {
        /** Combined relevance: importance × impressionDepth × pathStrength / (1 + distance). */
        public float relevance() {
            return node.importance() * node.impressionDepth() * pathStrength / (1 + distance);
        }
    }

    // ── Construction ────────────────────────────────────────────────────

    /** Build the graph from a CompactedMemory instance. */
    public static MemoryGraphTraverser fromMemory(CompactedMemory memory) {
        var traverser = new MemoryGraphTraverser();

        if (memory == null) return traverser;

        // Index nodes
        if (memory.nodes() != null) {
            for (var node : memory.nodes()) {
                traverser.nodeIndex.put(node.id(), node);
            }
            traverser.nodeCount = memory.nodes().size();
        }

        // Build bidirectional adjacency from links
        if (memory.links() != null) {
            for (var link : memory.links()) {
                traverser.addEdge(link.sourceId(), link.targetId(), link.strength(), link.relation());
                traverser.addEdge(link.targetId(), link.sourceId(), link.strength(), link.relation());
                traverser.edgeCount++;
            }
        }

        log.debug("Memory graph built: {} nodes, {} edges", traverser.nodeCount, traverser.edgeCount);
        return traverser;
    }

    private void addEdge(String fromId, String toId, float strength, String relation) {
        adjacency.computeIfAbsent(fromId, k -> new ArrayList<>())
            .add(new Edge(toId, strength, relation));
    }

    // ── Queries ─────────────────────────────────────────────────────────

    /**
     * BFS expansion: find all nodes reachable within maxHops from any seed node.
     * Returns nodes sorted by relevance (importance × path strength / distance).
     *
     * @param seedNodeIds starting points (typically from Lucene search results)
     * @param maxHops maximum graph distance (1 = direct neighbors, 2 = 2-hop)
     * @return traversal results sorted by relevance, excluding seeds
     */
    public List<TraversalResult> expand(List<String> seedNodeIds, int maxHops) {
        if (seedNodeIds == null || seedNodeIds.isEmpty() || maxHops < 1) {
            return List.of();
        }

        var visited = new HashSet<String>();
        var results = new ArrayList<TraversalResult>();

        // BFS queue: (nodeId, distance, cumulative path strength)
        var queue = new ArrayDeque<TraversalEntry>();

        for (var seedId : seedNodeIds) {
            visited.add(seedId);
            queue.add(new TraversalEntry(seedId, 0, 1.0f));
        }

        while (!queue.isEmpty()) {
            var current = queue.poll();

            if (current.distance >= maxHops) continue;

            var edges = adjacency.getOrDefault(current.nodeId, List.of());
            for (var edge : edges) {
                if (visited.contains(edge.targetId)) continue;
                visited.add(edge.targetId);

                int newDistance = current.distance + 1;
                float newStrength = current.pathStrength * edge.strength;

                var node = nodeIndex.get(edge.targetId);
                if (node != null) {
                    results.add(new TraversalResult(node, newDistance, newStrength));
                }

                if (newDistance < maxHops) {
                    queue.add(new TraversalEntry(edge.targetId, newDistance, newStrength));
                }
            }
        }

        // Sort by relevance descending
        results.sort(Comparator.comparingDouble(TraversalResult::relevance).reversed());
        return results;
    }

    /**
     * Get the immediate neighborhood of a node (1-hop).
     * Useful for expanding context around a retrieved memory.
     */
    public List<TraversalResult> neighborhood(String nodeId) {
        return expand(List.of(nodeId), 1);
    }

    /**
     * Get the extended neighborhood (N-hop) of a node.
     */
    public List<TraversalResult> neighborhood(String nodeId, int radius) {
        return expand(List.of(nodeId), radius);
    }

    /**
     * Find the shortest path between two memory nodes using BFS.
     * Returns the path as a list of node IDs (from → to), or empty if not connected.
     */
    public List<String> shortestPath(String fromId, String toId) {
        if (fromId.equals(toId)) return List.of(fromId);
        if (!adjacency.containsKey(fromId)) return List.of();

        var visited = new HashSet<String>();
        var parent = new HashMap<String, String>();
        var queue = new ArrayDeque<String>();

        visited.add(fromId);
        queue.add(fromId);

        while (!queue.isEmpty()) {
            var current = queue.poll();

            for (var edge : adjacency.getOrDefault(current, List.of())) {
                if (visited.contains(edge.targetId)) continue;
                visited.add(edge.targetId);
                parent.put(edge.targetId, current);

                if (edge.targetId.equals(toId)) {
                    // Reconstruct path
                    var path = new ArrayList<String>();
                    var node = toId;
                    while (node != null) {
                        path.add(node);
                        node = parent.get(node);
                    }
                    Collections.reverse(path);
                    return path;
                }

                queue.add(edge.targetId);
            }
        }

        return List.of(); // not connected
    }

    /**
     * Find connected components — groups of memories that form clusters.
     * Useful for Forge-time analysis: strongly connected components may be
     * merge candidates during consolidation.
     *
     * @return list of components, each is a set of node IDs
     */
    public List<Set<String>> connectedComponents() {
        var visited = new HashSet<String>();
        var components = new ArrayList<Set<String>>();

        for (var nodeId : nodeIndex.keySet()) {
            if (visited.contains(nodeId)) continue;

            // BFS from this node
            var component = new HashSet<String>();
            var queue = new ArrayDeque<String>();
            queue.add(nodeId);
            visited.add(nodeId);

            while (!queue.isEmpty()) {
                var current = queue.poll();
                component.add(current);

                for (var edge : adjacency.getOrDefault(current, List.of())) {
                    if (!visited.contains(edge.targetId) && nodeIndex.containsKey(edge.targetId)) {
                        visited.add(edge.targetId);
                        queue.add(edge.targetId);
                    }
                }
            }

            components.add(component);
        }

        // Sort by size descending
        components.sort(Comparator.comparingInt(Set<String>::size).reversed());
        return components;
    }

    /**
     * Get a node by ID.
     */
    public Optional<MemoryNode> getNode(String nodeId) {
        return Optional.ofNullable(nodeIndex.get(nodeId));
    }

    /** Number of nodes in the graph. */
    public int nodeCount() { return nodeCount; }

    /** Number of edges (undirected) in the graph. */
    public int edgeCount() { return edgeCount; }

    /** Whether the graph is empty. */
    public boolean isEmpty() { return nodeCount == 0; }

    // ── Internal ────────────────────────────────────────────────────────

    private record TraversalEntry(String nodeId, int distance, float pathStrength) {}
}