package org.wyrdsekai.between.research;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReservoirComputingTest {

    private ReservoirComputing reservoir;

    @BeforeEach
    void setUp() {
        reservoir = new ReservoirComputing(0.3, 0.9);
        reservoir.addNode("A");
        reservoir.addNode("B");
        reservoir.addNode("C");
        reservoir.addConnection("A", "B", 0.5);
        reservoir.addConnection("B", "C", 0.3);
        reservoir.addConnection("C", "A", 0.2);
    }

    @Test void initial_state() {
        assertThat(reservoir.nodeCount()).isEqualTo(3);
        assertThat(reservoir.connectionCount()).isEqualTo(3);
        assertThat(reservoir.timestep()).isEqualTo(0);
    }

    @Test void inject_signal() {
        reservoir.inject(Map.of("A", 1.0));
        var state = reservoir.state();
        assertThat(state.get("A")).isEqualTo(1.0);
    }

    @Test void step_advances_timestep() {
        var state = reservoir.step();
        assertThat(state.timestep()).isEqualTo(1);
        assertThat(reservoir.timestep()).isEqualTo(1);
    }

    @Test void step_propagates_activation() {
        reservoir.inject(Map.of("A", 1.0));
        reservoir.step();
        // After one step, B should have non-zero activation (from A→B connection)
        var state = reservoir.state();
        assertThat(state.get("B")).isNotEqualTo(0.0);
    }

    @Test void multiple_steps() {
        reservoir.inject(Map.of("A", 1.0));
        for (int i = 0; i < 10; i++) reservoir.step();
        assertThat(reservoir.timestep()).isEqualTo(10);
    }

    @Test void readout() {
        reservoir.inject(Map.of("A", 1.0, "B", 0.5));
        reservoir.step();
        var result = reservoir.readout(Map.of("A", 1.0, "B", 1.0, "C", 1.0));
        assertThat(result.output()).hasSize(1);
        assertThat(result.timestep()).isEqualTo(1);
    }

    @Test void reset_clears_activations() {
        reservoir.inject(Map.of("A", 1.0));
        reservoir.step();
        reservoir.reset();
        assertThat(reservoir.timestep()).isEqualTo(0);
        assertThat(reservoir.state().get("A")).isEqualTo(0.0);
    }

    @Test void leaky_integration() {
        // With leak rate 0.3, old activation decays
        reservoir.inject(Map.of("A", 1.0));
        reservoir.step();
        double afterStep1 = reservoir.state().get("A");
        reservoir.step();
        double afterStep2 = reservoir.state().get("A");
        // Without new input, activation should decay
        assertThat(Math.abs(afterStep2)).isLessThanOrEqualTo(Math.abs(afterStep1) + 0.01);
    }
}
