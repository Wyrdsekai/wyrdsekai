package org.wyrdsekai.between.research;

import java.util.*;

/**
 * Persistent homology — compute persistence barcodes from a simplicial complex filtration (§74 research).
 * Identifies stable vs ephemeral topological features across quality thresholds.
 *
 * Each barcode interval (birth, death) represents a topological feature:
 *   - H0: connected components (birth=edge weight where nodes merge)
 *   - H1: loops/cycles (birth=edge weight that creates cycle, death=when filled)
 *
 * Long bars = stable features. Short bars = noise.
 */
public class PersistentHomology {

    /** A persistence barcode interval. */
    public record BarInterval(
        int dimension,     // homological dimension (0=components, 1=loops, etc.)
        double birth,      // weight at which feature appears
        double death,      // weight at which feature dies (Double.POSITIVE_INFINITY if never)
        String label       // optional label
    ) {
        public double persistence() {
            return death == Double.POSITIVE_INFINITY ? Double.POSITIVE_INFINITY : death - birth;
        }

        public boolean isInfinite() {
            return death == Double.POSITIVE_INFINITY;
        }
    }

    /** Persistence diagram — collection of barcode intervals. */
    public record PersistenceDiagram(List<BarInterval> intervals) {

        /** Get intervals of a specific dimension. */
        public List<BarInterval> forDimension(int dim) {
            return intervals.stream().filter(i -> i.dimension() == dim).toList();
        }

        /** Count of features per dimension. */
        public Map<Integer, Integer> featureCounts() {
            var counts = new LinkedHashMap<Integer, Integer>();
            for (var interval : intervals) {
                counts.merge(interval.dimension(), 1, Integer::sum);
            }
            return counts;
        }

        /** Get the N most persistent (longest-lived) features. */
        public List<BarInterval> mostPersistent(int n) {
            return intervals.stream()
                .filter(i -> !i.isInfinite())
                .sorted(Comparator.comparingDouble(BarInterval::persistence).reversed())
                .limit(n)
                .toList();
        }

        /** Count of infinite (never-dying) features. */
        public int infiniteFeatures() {
            return (int) intervals.stream().filter(BarInterval::isInfinite).count();
        }
    }

    /**
     * Compute H0 persistence (connected components) using Union-Find.
     * As edges are added in filtration order, components merge.
     */
    public static PersistenceDiagram computeH0(SimplicialComplex complex) {
        var filtration = complex.filtration();
        var vertices = new ArrayList<>(complex.vertices());

        // Union-Find
        var parent = new HashMap<String, String>();
        var birth = new HashMap<String, Double>();
        vertices.forEach(v -> {
            parent.put(v, v);
            birth.put(v, 0.0);
        });

        var intervals = new ArrayList<BarInterval>();

        // Process edges in filtration order
        for (var simplex : filtration) {
            if (simplex.dimension() != 1) continue;

            var verts = new ArrayList<>(simplex.vertices());
            var root1 = find(parent, verts.get(0));
            var root2 = find(parent, verts.get(1));

            if (!root1.equals(root2)) {
                // Merge: the younger component dies
                var birth1 = birth.get(root1);
                var birth2 = birth.get(root2);
                String survivor, dying;
                if (birth1 <= birth2) {
                    survivor = root1;
                    dying = root2;
                } else {
                    survivor = root2;
                    dying = root1;
                }
                parent.put(dying, survivor);
                intervals.add(new BarInterval(0, birth.get(dying), simplex.weight(),
                    "component merged at " + simplex.weight()));
            }
        }

        // Remaining components are infinite
        var roots = new HashSet<String>();
        for (var v : vertices) {
            roots.add(find(parent, v));
        }
        for (var root : roots) {
            intervals.add(new BarInterval(0, birth.get(root), Double.POSITIVE_INFINITY,
                "surviving component"));
        }

        return new PersistenceDiagram(List.copyOf(intervals));
    }

    /**
     * Compute Betti numbers from a persistence diagram at a given threshold.
     * Betti_k = number of k-dimensional features alive at the threshold.
     */
    public static Map<Integer, Integer> bettiNumbers(PersistenceDiagram diagram, double threshold) {
        var betti = new LinkedHashMap<Integer, Integer>();
        for (var interval : diagram.intervals()) {
            if (interval.birth() <= threshold
                && (interval.isInfinite() || interval.death() > threshold)) {
                betti.merge(interval.dimension(), 1, Integer::sum);
            }
        }
        return betti;
    }

    private static String find(Map<String, String> parent, String x) {
        while (!parent.get(x).equals(x)) {
            parent.put(x, parent.get(parent.get(x))); // path compression
            x = parent.get(x);
        }
        return x;
    }
}
