package org.wyrdsekai.between.research;

import java.util.*;

/**
 * Network topology as simplicial complexes (§74 research).
 * k-simplices: 0=nodes, 1=edges (connections), 2=triangles (3-cliques), etc.
 * Provides foundation for persistent homology and spectral analysis.
 */
public class SimplicialComplex {

    /** A k-simplex represented by its vertex set. */
    public record Simplex(Set<String> vertices, double weight) implements Comparable<Simplex> {
        public int dimension() { return vertices.size() - 1; }

        @Override
        public int compareTo(Simplex other) {
            return Double.compare(this.weight, other.weight);
        }
    }

    private final List<Simplex> simplices = new ArrayList<>();
    private final Set<String> vertexSet = new LinkedHashSet<>();

    /** Add a 0-simplex (node). */
    public void addVertex(String vertex) {
        vertexSet.add(vertex);
        simplices.add(new Simplex(Set.of(vertex), 0.0));
    }

    /** Add a 1-simplex (edge) with weight. */
    public void addEdge(String v1, String v2, double weight) {
        vertexSet.add(v1);
        vertexSet.add(v2);
        simplices.add(new Simplex(Set.of(v1, v2), weight));
    }

    /** Add a 2-simplex (triangle) — filled face between 3 vertices. */
    public void addTriangle(String v1, String v2, String v3, double weight) {
        vertexSet.add(v1);
        vertexSet.add(v2);
        vertexSet.add(v3);
        simplices.add(new Simplex(Set.of(v1, v2, v3), weight));
    }

    /** Add an arbitrary k-simplex. */
    public void addSimplex(Set<String> vertices, double weight) {
        vertexSet.addAll(vertices);
        simplices.add(new Simplex(Set.copyOf(vertices), weight));
    }

    /** Build a Vietoris-Rips complex from pairwise distances up to threshold. */
    public static SimplicialComplex vietorisRips(Map<String, Map<String, Double>> distances,
                                                   double threshold) {
        var complex = new SimplicialComplex();
        var nodes = new ArrayList<>(distances.keySet());

        // Add 0-simplices
        nodes.forEach(complex::addVertex);

        // Add 1-simplices (edges within threshold)
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                var dist = distances.getOrDefault(nodes.get(i), Map.of())
                    .getOrDefault(nodes.get(j), Double.MAX_VALUE);
                if (dist <= threshold) {
                    complex.addEdge(nodes.get(i), nodes.get(j), dist);
                }
            }
        }

        // Add 2-simplices (triangles where all 3 edges exist)
        var edges = complex.simplicesOfDimension(1);
        for (int i = 0; i < edges.size(); i++) {
            for (int j = i + 1; j < edges.size(); j++) {
                var combined = new HashSet<>(edges.get(i).vertices());
                combined.addAll(edges.get(j).vertices());
                if (combined.size() == 3) {
                    // Check all 3 edges exist
                    var verts = new ArrayList<>(combined);
                    boolean allEdgesExist = edgeExists(edges, verts.get(0), verts.get(1))
                        && edgeExists(edges, verts.get(1), verts.get(2))
                        && edgeExists(edges, verts.get(0), verts.get(2));
                    if (allEdgesExist) {
                        var maxWeight = edges.stream()
                            .filter(e -> combined.containsAll(e.vertices()))
                            .mapToDouble(Simplex::weight).max().orElse(0);
                        complex.addTriangle(verts.get(0), verts.get(1), verts.get(2), maxWeight);
                    }
                }
            }
        }

        return complex;
    }

    /** Get all simplices of a given dimension. */
    public List<Simplex> simplicesOfDimension(int dimension) {
        return simplices.stream()
            .filter(s -> s.dimension() == dimension)
            .toList();
    }

    /** Total number of simplices. */
    public int size() {
        return simplices.size();
    }

    /** Number of vertices (0-simplices). */
    public int vertexCount() {
        return vertexSet.size();
    }

    /** Number of edges (1-simplices). */
    public int edgeCount() {
        return (int) simplices.stream().filter(s -> s.dimension() == 1).count();
    }

    /** Euler characteristic: V - E + F - ... */
    public int eulerCharacteristic() {
        int chi = 0;
        int maxDim = simplices.stream().mapToInt(Simplex::dimension).max().orElse(0);
        for (int d = 0; d <= maxDim; d++) {
            int count = simplicesOfDimension(d).size();
            chi += (d % 2 == 0) ? count : -count;
        }
        return chi;
    }

    /** Get the maximum dimension of any simplex. */
    public int maxDimension() {
        return simplices.stream().mapToInt(Simplex::dimension).max().orElse(-1);
    }

    /** Get all simplices sorted by weight (for filtration). */
    public List<Simplex> filtration() {
        return simplices.stream().sorted().toList();
    }

    /** Get vertex set. */
    public Set<String> vertices() {
        return Set.copyOf(vertexSet);
    }

    private static boolean edgeExists(List<Simplex> edges, String v1, String v2) {
        var target = Set.of(v1, v2);
        return edges.stream().anyMatch(e -> e.vertices().equals(target));
    }
}
