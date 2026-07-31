package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class MemoryGraphTraverserTest {

    private CompactedMemory memory;
    private MemoryGraphTraverser traverser;

    private static MemoryNode node(String id, String content, float importance) {
        return new MemoryNode(id, content, List.of(), importance, 0.5f, false,
            null, Instant.now(), 0, null);
    }

    private static MemoryNode formativeNode(String id, String content) {
        return new MemoryNode(id, content, List.of(), 1.0f, 1.0f, true,
            null, Instant.now(), 0, null);
    }

    @BeforeEach
    void setUp() {
        // Build a small graph:
        //   A --0.9-- B --0.8-- C
        //   |                   |
        //   +--0.5-- D --0.7---+
        //            |
        //            E (isolated from the main cluster via D only)
        var nodes = List.of(
            node("A", "User likes mythology", 0.8f),
            node("B", "Found books on Norse gods", 0.6f),
            node("C", "Thor defends Asgard", 0.5f),
            node("D", "User asked about history", 0.4f),
            formativeNode("E", "User's name is Masumi")
        );
        var links = List.of(
            new CompactedMemory.MemoryLink("A", "B", 0.9f, "thematic"),
            new CompactedMemory.MemoryLink("B", "C", 0.8f, "causal"),
            new CompactedMemory.MemoryLink("A", "D", 0.5f, "temporal"),
            new CompactedMemory.MemoryLink("C", "D", 0.7f, "thematic"),
            new CompactedMemory.MemoryLink("D", "E", 0.6f, "temporal")
        );
        memory = new CompactedMemory(nodes, links, Map.of("mythology", 0.9f));
        traverser = MemoryGraphTraverser.fromMemory(memory);
    }

    @Nested
    class Construction {

        @Test
        void buildFromMemory() {
            assertThat(traverser.nodeCount()).isEqualTo(5);
            assertThat(traverser.edgeCount()).isEqualTo(5);
        }

        @Test
        void emptyMemoryProducesEmptyGraph() {
            var empty = MemoryGraphTraverser.fromMemory(CompactedMemory.empty());
            assertThat(empty.isEmpty()).isTrue();
            assertThat(empty.nodeCount()).isEqualTo(0);
        }

        @Test
        void nullMemoryProducesEmptyGraph() {
            var empty = MemoryGraphTraverser.fromMemory(null);
            assertThat(empty.isEmpty()).isTrue();
        }
    }

    @Nested
    class Expansion {

        @Test
        void oneHopFromA() {
            var results = traverser.expand(List.of("A"), 1);
            var ids = results.stream().map(r -> r.node().id()).toList();
            assertThat(ids).containsExactlyInAnyOrder("B", "D");
        }

        @Test
        void twoHopsFromA() {
            var results = traverser.expand(List.of("A"), 2);
            var ids = results.stream().map(r -> r.node().id()).toList();
            // A→B→C, A→D→E, A→D→C (via D)
            assertThat(ids).containsExactlyInAnyOrder("B", "C", "D", "E");
        }

        @Test
        void distanceTrackedCorrectly() {
            var results = traverser.expand(List.of("A"), 2);
            var bResult = results.stream().filter(r -> r.node().id().equals("B")).findFirst();
            var cResult = results.stream().filter(r -> r.node().id().equals("C")).findFirst();

            assertThat(bResult).isPresent();
            assertThat(bResult.get().distance()).isEqualTo(1);

            assertThat(cResult).isPresent();
            assertThat(cResult.get().distance()).isEqualTo(2);
        }

        @Test
        void pathStrengthDecays() {
            var results = traverser.expand(List.of("A"), 2);
            var bResult = results.stream().filter(r -> r.node().id().equals("B")).findFirst().get();
            var cResult = results.stream().filter(r -> r.node().id().equals("C")).findFirst().get();

            // A→B: strength 0.9
            assertThat(bResult.pathStrength()).isCloseTo(0.9f, within(0.01f));
            // A→B→C: strength 0.9 * 0.8 = 0.72
            assertThat(cResult.pathStrength()).isCloseTo(0.72f, within(0.01f));
        }

        @Test
        void multiSeedExpansion() {
            // Start from both A and E — should cover entire graph in 1 hop
            var results = traverser.expand(List.of("A", "E"), 1);
            var ids = results.stream().map(r -> r.node().id()).toList();
            assertThat(ids).containsExactlyInAnyOrder("B", "D");
        }

        @Test
        void emptySeeds() {
            var results = traverser.expand(List.of(), 2);
            assertThat(results).isEmpty();
        }
    }

    @Nested
    class Neighborhood {

        @Test
        void directNeighbors() {
            var results = traverser.neighborhood("B");
            var ids = results.stream().map(r -> r.node().id()).toList();
            assertThat(ids).containsExactlyInAnyOrder("A", "C");
        }

        @Test
        void extendedNeighborhood() {
            var results = traverser.neighborhood("B", 2);
            var ids = results.stream().map(r -> r.node().id()).toList();
            // B→A→D, B→C→D (and D→E wouldn't be reached at radius 2 from B?
            // B→A is 1 hop, A→D is 2 hops. B→C→D is also 2 hops.
            assertThat(ids).contains("A", "C", "D");
        }
    }

    @Nested
    class ShortestPath {

        @Test
        void directPath() {
            var path = traverser.shortestPath("A", "B");
            assertThat(path).containsExactly("A", "B");
        }

        @Test
        void twoHopPath() {
            var path = traverser.shortestPath("A", "C");
            assertThat(path).hasSize(3);
            assertThat(path.getFirst()).isEqualTo("A");
            assertThat(path.getLast()).isEqualTo("C");
        }

        @Test
        void sameNode() {
            var path = traverser.shortestPath("A", "A");
            assertThat(path).containsExactly("A");
        }

        @Test
        void allNodesConnected() {
            // Graph is fully connected — every node can reach every other
            var path = traverser.shortestPath("A", "E");
            assertThat(path).isNotEmpty();
            assertThat(path.getFirst()).isEqualTo("A");
            assertThat(path.getLast()).isEqualTo("E");
        }
    }

    @Nested
    class ConnectedComponents {

        @Test
        void singleComponentWhenFullyConnected() {
            var components = traverser.connectedComponents();
            assertThat(components).hasSize(1);
            assertThat(components.getFirst()).hasSize(5);
        }

        @Test
        void multipleComponentsWithIsolatedNodes() {
            // Build a graph with two disconnected clusters
            var nodes = List.of(
                node("X", "cluster 1", 0.5f),
                node("Y", "cluster 1", 0.5f),
                node("Z", "cluster 2 isolated", 0.5f)
            );
            var links = List.of(
                new CompactedMemory.MemoryLink("X", "Y", 0.8f, "thematic")
                // Z has no links
            );
            var mem = new CompactedMemory(nodes, links, Map.of());
            var t = MemoryGraphTraverser.fromMemory(mem);

            var components = t.connectedComponents();
            assertThat(components).hasSize(2);
            // Largest first
            assertThat(components.get(0)).hasSize(2);
            assertThat(components.get(1)).hasSize(1);
        }
    }

    @Nested
    class Relevance {

        @Test
        void formativeNodesRankHigher() {
            // E is formative (importance=1.0, impressionDepth=1.0)
            // D is not (importance=0.4, impressionDepth=0.5)
            var results = traverser.expand(List.of("D"), 1);
            var eResult = results.stream().filter(r -> r.node().id().equals("E")).findFirst();
            var otherResults = results.stream().filter(r -> !r.node().id().equals("E")).toList();

            assertThat(eResult).isPresent();
            // E's relevance should be higher due to formative importance
            for (var other : otherResults) {
                assertThat(eResult.get().relevance()).isGreaterThan(other.relevance());
            }
        }

        @Test
        void resultsAreSortedByRelevance() {
            var results = traverser.expand(List.of("A"), 2);
            for (int i = 1; i < results.size(); i++) {
                assertThat(results.get(i - 1).relevance())
                    .isGreaterThanOrEqualTo(results.get(i).relevance());
            }
        }
    }
}
