package org.wyrdsekai.between.research;

import java.util.*;

/**
 * Reservoir computing — use network dynamics as a computational reservoir (§74 research).
 * Inspired by Drosophila connectome models.
 *
 * The Between network itself becomes a computing substrate:
 * - Input signals are injected into network nodes
 * - The network's intrinsic dynamics transform the signals
 * - A simple readout layer extracts the computation
 *
 * This is valuable because the household network has rich, complex dynamics
 * (latency variations, jitter, node activity patterns) that can be harnessed
 * for lightweight inference without dedicated GPU compute.
 */
public class ReservoirComputing {

    /** Reservoir state — activation levels of each node. */
    public record ReservoirState(Map<String, Double> activations, int timestep) {
        public double get(String nodeId) {
            return activations.getOrDefault(nodeId, 0.0);
        }

        public int nodeCount() {
            return activations.size();
        }
    }

    /** A weighted connection between reservoir nodes. */
    public record Connection(String from, String to, double weight) {}

    /** Readout result from the reservoir. */
    public record ReadoutResult(double[] output, int timestep, double confidence) {}

    private final Map<String, Double> activations = new LinkedHashMap<>();
    private final List<Connection> connections = new ArrayList<>();
    private final double leakRate;
    private final double spectralRadius;
    private int timestep = 0;

    /**
     * Create a reservoir with configurable dynamics.
     * @param leakRate how quickly old state decays (0 = full memory, 1 = no memory)
     * @param spectralRadius controls the "echo" of the reservoir (< 1 for stability)
     */
    public ReservoirComputing(double leakRate, double spectralRadius) {
        this.leakRate = Math.max(0, Math.min(1, leakRate));
        this.spectralRadius = spectralRadius;
    }

    /** Add a node to the reservoir. */
    public void addNode(String nodeId) {
        activations.putIfAbsent(nodeId, 0.0);
    }

    /** Add a weighted connection. */
    public void addConnection(String from, String to, double weight) {
        connections.add(new Connection(from, to, weight * spectralRadius));
    }

    /**
     * Inject input signal into specific reservoir nodes.
     * @param inputs map of nodeId → input value
     */
    public void inject(Map<String, Double> inputs) {
        for (var entry : inputs.entrySet()) {
            var current = activations.getOrDefault(entry.getKey(), 0.0);
            activations.put(entry.getKey(), current + entry.getValue());
        }
    }

    /**
     * Advance the reservoir by one timestep.
     * Applies leaky integration: x(t+1) = (1-α)x(t) + α·tanh(Wx(t) + input)
     */
    public ReservoirState step() {
        var newActivations = new LinkedHashMap<String, Double>();

        // Compute weighted sum of inputs for each node
        var incoming = new HashMap<String, Double>();
        for (var conn : connections) {
            var fromActivation = activations.getOrDefault(conn.from(), 0.0);
            incoming.merge(conn.to(), fromActivation * conn.weight(), Double::sum);
        }

        // Leaky integration with tanh nonlinearity
        for (var entry : activations.entrySet()) {
            var nodeId = entry.getKey();
            var current = entry.getValue();
            var input = incoming.getOrDefault(nodeId, 0.0);
            var newVal = (1 - leakRate) * current + leakRate * Math.tanh(input);
            newActivations.put(nodeId, newVal);
        }

        activations.putAll(newActivations);
        timestep++;

        return new ReservoirState(Map.copyOf(activations), timestep);
    }

    /**
     * Simple linear readout: weighted sum of all activations.
     * @param weights readout weights per node
     */
    public ReadoutResult readout(Map<String, Double> weights) {
        double sum = 0;
        for (var entry : weights.entrySet()) {
            sum += activations.getOrDefault(entry.getKey(), 0.0) * entry.getValue();
        }
        return new ReadoutResult(new double[]{sum}, timestep, 1.0);
    }

    /** Get current reservoir state. */
    public ReservoirState state() {
        return new ReservoirState(Map.copyOf(activations), timestep);
    }

    /** Number of nodes. */
    public int nodeCount() {
        return activations.size();
    }

    /** Number of connections. */
    public int connectionCount() {
        return connections.size();
    }

    /** Current timestep. */
    public int timestep() {
        return timestep;
    }

    /** Reset all activations to zero. */
    public void reset() {
        activations.replaceAll((k, v) -> 0.0);
        timestep = 0;
    }
}
