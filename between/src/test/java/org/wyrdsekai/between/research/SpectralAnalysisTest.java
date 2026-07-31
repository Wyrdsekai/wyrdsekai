package org.wyrdsekai.between.research;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpectralAnalysisTest {

    @Test void single_node_graph() {
        var adj = Map.<String, Map<String, Double>>of("A", Map.of());
        var result = SpectralAnalysis.computeLaplacian(adj);
        assertThat(result.nodeCount()).isEqualTo(1);
        assertThat(result.connectedComponents()).isEqualTo(1);
    }

    @Test void two_connected_nodes() {
        var adj = Map.of(
            "A", Map.of("B", 1.0),
            "B", Map.of("A", 1.0)
        );
        var result = SpectralAnalysis.computeLaplacian(adj);
        assertThat(result.connectedComponents()).isEqualTo(1);
        assertThat(result.laplacian()).isNotNull();
    }

    @Test void two_disconnected_components() {
        var adj = Map.of(
            "A", Map.of("B", 1.0),
            "B", Map.of("A", 1.0),
            "C", Map.of("D", 1.0),
            "D", Map.of("C", 1.0)
        );
        var result = SpectralAnalysis.computeLaplacian(adj);
        assertThat(result.connectedComponents()).isEqualTo(2);
    }

    @Test void connectivity_assessment_well_connected() {
        var adj = Map.of(
            "A", Map.of("B", 1.0, "C", 1.0),
            "B", Map.of("A", 1.0, "C", 1.0),
            "C", Map.of("A", 1.0, "B", 1.0)
        );
        var assessment = SpectralAnalysis.assessConnectivity(adj);
        assertThat(assessment.connectedComponents()).isEqualTo(1);
        assertThat(assessment.isolatedNodes()).isEmpty();
    }

    @Test void isolated_nodes_detected() {
        var adj = Map.of(
            "A", Map.of("B", 1.0),
            "B", Map.of("A", 1.0),
            "C", Map.<String, Double>of()
        );
        var assessment = SpectralAnalysis.assessConnectivity(adj);
        assertThat(assessment.isolatedNodes()).contains("C");
    }

    @Test void degree_centrality() {
        var adj = Map.of(
            "A", Map.of("B", 1.0, "C", 1.0),
            "B", Map.of("A", 1.0),
            "C", Map.of("A", 1.0)
        );
        var centrality = SpectralAnalysis.degreeCentrality(adj);
        assertThat(centrality.get("A")).isGreaterThan(centrality.get("B"));
    }

    @Test void empty_graph_centrality() {
        var centrality = SpectralAnalysis.degreeCentrality(Map.of());
        assertThat(centrality).isEmpty();
    }

    @Test void laplacian_matrix_dimensions() {
        var adj = Map.of(
            "A", Map.of("B", 1.0),
            "B", Map.of("A", 1.0),
            "C", Map.of("A", 1.0)
        );
        var result = SpectralAnalysis.computeLaplacian(adj);
        assertThat(result.laplacian()).hasNumberOfRows(3);
        assertThat(result.laplacian()[0]).hasSize(3);
    }
}
