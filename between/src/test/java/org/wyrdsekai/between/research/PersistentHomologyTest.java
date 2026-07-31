package org.wyrdsekai.between.research;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PersistentHomologyTest {

    @Test void single_component_graph() {
        var complex = new SimplicialComplex();
        complex.addVertex("A");
        complex.addVertex("B");
        complex.addEdge("A", "B", 1.0);

        var diagram = PersistentHomology.computeH0(complex);
        assertThat(diagram.infiniteFeatures()).isEqualTo(1); // one surviving component
    }

    @Test void two_disconnected_components() {
        var complex = new SimplicialComplex();
        complex.addVertex("A");
        complex.addVertex("B");
        // no edges — two components

        var diagram = PersistentHomology.computeH0(complex);
        assertThat(diagram.infiniteFeatures()).isEqualTo(2); // two surviving components
    }

    @Test void merge_creates_finite_interval() {
        var complex = new SimplicialComplex();
        complex.addVertex("A");
        complex.addVertex("B");
        complex.addVertex("C");
        complex.addEdge("A", "B", 1.0);
        complex.addEdge("B", "C", 2.0);

        var diagram = PersistentHomology.computeH0(complex);
        // Two merges → two finite intervals + one infinite
        var h0 = diagram.forDimension(0);
        assertThat(h0).isNotEmpty();
        assertThat(diagram.infiniteFeatures()).isEqualTo(1);
    }

    @Test void feature_counts() {
        var complex = new SimplicialComplex();
        complex.addVertex("A");
        complex.addVertex("B");
        complex.addEdge("A", "B", 1.0);

        var diagram = PersistentHomology.computeH0(complex);
        var counts = diagram.featureCounts();
        assertThat(counts).containsKey(0);
    }

    @Test void most_persistent_features() {
        var complex = new SimplicialComplex();
        complex.addVertex("A");
        complex.addVertex("B");
        complex.addVertex("C");
        complex.addEdge("A", "B", 1.0);
        complex.addEdge("B", "C", 5.0); // merges at higher weight

        var diagram = PersistentHomology.computeH0(complex);
        var persistent = diagram.mostPersistent(1);
        // The finite interval with longest persistence should be returned
        assertThat(persistent).isNotEmpty();
    }

    @Test void betti_numbers() {
        var complex = new SimplicialComplex();
        complex.addVertex("A");
        complex.addVertex("B");
        complex.addVertex("C");
        complex.addEdge("A", "B", 1.0);
        complex.addEdge("B", "C", 3.0);

        var diagram = PersistentHomology.computeH0(complex);
        // At threshold 0.5: all three separate → betti_0 = 3
        var betti0 = PersistentHomology.bettiNumbers(diagram, 0.5);
        assertThat(betti0.getOrDefault(0, 0)).isEqualTo(3);

        // At threshold 2.0: A-B merged → betti_0 = 2
        var betti2 = PersistentHomology.bettiNumbers(diagram, 2.0);
        assertThat(betti2.getOrDefault(0, 0)).isEqualTo(2);
    }

    @Test void persistence_interval_properties() {
        var interval = new PersistentHomology.BarInterval(0, 1.0, 5.0, "test");
        assertThat(interval.persistence()).isEqualTo(4.0);
        assertThat(interval.isInfinite()).isFalse();
    }

    @Test void infinite_interval() {
        var interval = new PersistentHomology.BarInterval(0, 0.0, Double.POSITIVE_INFINITY, "surviving");
        assertThat(interval.isInfinite()).isTrue();
        assertThat(interval.persistence()).isEqualTo(Double.POSITIVE_INFINITY);
    }
}
