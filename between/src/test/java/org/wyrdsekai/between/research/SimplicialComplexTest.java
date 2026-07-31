package org.wyrdsekai.between.research;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SimplicialComplexTest {

    @Test void add_vertex() {
        var complex = new SimplicialComplex();
        complex.addVertex("A");
        assertThat(complex.vertexCount()).isEqualTo(1);
        assertThat(complex.size()).isEqualTo(1); // one 0-simplex
    }

    @Test void add_edge() {
        var complex = new SimplicialComplex();
        complex.addEdge("A", "B", 1.0);
        assertThat(complex.vertexCount()).isEqualTo(2);
        assertThat(complex.edgeCount()).isEqualTo(1);
    }

    @Test void add_triangle() {
        var complex = new SimplicialComplex();
        complex.addTriangle("A", "B", "C", 2.0);
        assertThat(complex.simplicesOfDimension(2)).hasSize(1);
    }

    @Test void euler_characteristic_single_triangle() {
        var complex = new SimplicialComplex();
        complex.addVertex("A");
        complex.addVertex("B");
        complex.addVertex("C");
        complex.addEdge("A", "B", 1.0);
        complex.addEdge("B", "C", 1.0);
        complex.addEdge("A", "C", 1.0);
        complex.addTriangle("A", "B", "C", 1.0);
        // V=3, E=3, F=1 → χ = 3 - 3 + 1 = 1
        assertThat(complex.eulerCharacteristic()).isEqualTo(1);
    }

    @Test void max_dimension() {
        var complex = new SimplicialComplex();
        complex.addVertex("A");
        complex.addEdge("A", "B", 1.0);
        assertThat(complex.maxDimension()).isEqualTo(1);
    }

    @Test void filtration_sorted_by_weight() {
        var complex = new SimplicialComplex();
        complex.addEdge("A", "B", 3.0);
        complex.addEdge("B", "C", 1.0);
        complex.addEdge("A", "C", 2.0);
        var filtration = complex.filtration();
        assertThat(filtration.getFirst().weight()).isLessThanOrEqualTo(filtration.getLast().weight());
    }

    @Test void vietoris_rips_construction() {
        var distances = Map.of(
            "A", Map.of("B", 1.0, "C", 2.0),
            "B", Map.of("A", 1.0, "C", 1.5),
            "C", Map.of("A", 2.0, "B", 1.5)
        );

        var complex = SimplicialComplex.vietorisRips(distances, 2.0);
        assertThat(complex.vertexCount()).isEqualTo(3);
        assertThat(complex.edgeCount()).isEqualTo(3); // all within threshold
    }

    @Test void vietoris_rips_filters_edges() {
        var distances = Map.of(
            "A", Map.of("B", 1.0, "C", 10.0),
            "B", Map.of("A", 1.0, "C", 10.0),
            "C", Map.of("A", 10.0, "B", 10.0)
        );

        var complex = SimplicialComplex.vietorisRips(distances, 2.0);
        assertThat(complex.edgeCount()).isEqualTo(1); // only A-B within threshold
    }

    @Test void vertices_set() {
        var complex = new SimplicialComplex();
        complex.addEdge("A", "B", 1.0);
        complex.addEdge("C", "D", 2.0);
        assertThat(complex.vertices()).containsExactlyInAnyOrder("A", "B", "C", "D");
    }

    @Test void arbitrary_simplex() {
        var complex = new SimplicialComplex();
        complex.addSimplex(Set.of("A", "B", "C", "D"), 5.0);
        assertThat(complex.maxDimension()).isEqualTo(3); // 4 vertices = 3-simplex
    }
}
