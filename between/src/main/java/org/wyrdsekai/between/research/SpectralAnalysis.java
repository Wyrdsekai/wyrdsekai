package org.wyrdsekai.between.research;

import java.util.*;

/**
 * Spectral analysis of the topology register graph (§74 research).
 * Computes the graph Laplacian and its eigenvalue properties.
 *
 * The Laplacian matrix L = D - A (degree matrix minus adjacency matrix).
 * Its spectral properties reveal:
 *   - λ₁ (Fiedler value): algebraic connectivity — how well-connected the graph is
 *   - Number of zero eigenvalues = number of connected components
 *   - Spectral gap: robustness to node removal
 */
public class SpectralAnalysis {

    /** Laplacian analysis result. */
    public record LaplacianResult(
        double[][] laplacian,
        double fiedlerValue,
        int connectedComponents,
        double spectralGap,
        List<String> nodeOrder
    ) {
        public int nodeCount() { return nodeOrder.size(); }
    }

    /** Graph connectivity assessment. */
    public record ConnectivityAssessment(
        int connectedComponents,
        double algebraicConnectivity,
        String classification,
        List<String> isolatedNodes
    ) {}

    /**
     * Compute the graph Laplacian from an adjacency representation.
     * @param adjacency map of node → (neighbor → edge weight)
     */
    public static LaplacianResult computeLaplacian(Map<String, Map<String, Double>> adjacency) {
        var nodes = new ArrayList<>(adjacency.keySet());
        int n = nodes.size();
        var nodeIndex = new HashMap<String, Integer>();
        for (int i = 0; i < n; i++) nodeIndex.put(nodes.get(i), i);

        double[][] L = new double[n][n];

        // Build L = D - A
        for (int i = 0; i < n; i++) {
            var neighbors = adjacency.getOrDefault(nodes.get(i), Map.of());
            double degree = 0;
            for (var entry : neighbors.entrySet()) {
                var j = nodeIndex.get(entry.getKey());
                if (j != null) {
                    L[i][j] = -entry.getValue();
                    degree += entry.getValue();
                }
            }
            L[i][i] = degree;
        }

        // Estimate eigenvalues via power iteration (simplified)
        double fiedler = estimateFiedlerValue(L, n);
        int components = estimateConnectedComponents(adjacency);
        double spectralGap = fiedler; // For connected graphs, spectral gap ≈ fiedler value

        return new LaplacianResult(L, fiedler, components, spectralGap, nodes);
    }

    /**
     * Assess graph connectivity.
     */
    public static ConnectivityAssessment assessConnectivity(Map<String, Map<String, Double>> adjacency) {
        var laplacian = computeLaplacian(adjacency);
        var isolated = adjacency.entrySet().stream()
            .filter(e -> e.getValue().isEmpty())
            .map(Map.Entry::getKey)
            .toList();

        String classification;
        if (laplacian.connectedComponents() == 1 && laplacian.fiedlerValue() > 0.5) {
            classification = "well-connected";
        } else if (laplacian.connectedComponents() == 1) {
            classification = "connected but fragile";
        } else if (laplacian.connectedComponents() <= 3) {
            classification = "partially connected";
        } else {
            classification = "fragmented";
        }

        return new ConnectivityAssessment(
            laplacian.connectedComponents(),
            laplacian.fiedlerValue(),
            classification,
            isolated
        );
    }

    /**
     * Estimate node importance via degree centrality.
     * @return map of nodeId → centrality score (0 to 1)
     */
    public static Map<String, Double> degreeCentrality(Map<String, Map<String, Double>> adjacency) {
        int n = adjacency.size();
        if (n <= 1) return Map.of();

        var centrality = new LinkedHashMap<String, Double>();
        for (var entry : adjacency.entrySet()) {
            centrality.put(entry.getKey(), (double) entry.getValue().size() / (n - 1));
        }
        return centrality;
    }

    // --- Approximation algorithms ---

    private static double estimateFiedlerValue(double[][] L, int n) {
        if (n <= 1) return 0.0;
        if (n == 2) return L[0][0] + L[1][1]; // simplified for 2-node case

        // Simple approximation: min non-zero diagonal minus max off-diagonal
        double minDiag = Double.MAX_VALUE;
        double maxOffDiag = 0;
        for (int i = 0; i < n; i++) {
            if (L[i][i] > 0 && L[i][i] < minDiag) minDiag = L[i][i];
            for (int j = 0; j < n; j++) {
                if (i != j && Math.abs(L[i][j]) > maxOffDiag) {
                    maxOffDiag = Math.abs(L[i][j]);
                }
            }
        }

        return minDiag == Double.MAX_VALUE ? 0.0 : Math.max(0, minDiag - maxOffDiag);
    }

    private static int estimateConnectedComponents(Map<String, Map<String, Double>> adjacency) {
        var visited = new HashSet<String>();
        int components = 0;

        for (var node : adjacency.keySet()) {
            if (!visited.contains(node)) {
                bfs(node, adjacency, visited);
                components++;
            }
        }
        return components;
    }

    private static void bfs(String start, Map<String, Map<String, Double>> adjacency,
                             Set<String> visited) {
        var queue = new ArrayDeque<String>();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            var current = queue.poll();
            for (var neighbor : adjacency.getOrDefault(current, Map.of()).keySet()) {
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }
    }
}
