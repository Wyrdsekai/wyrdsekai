package org.wyrdsekai.core.agent.research;

import java.util.*;

/**
 * Lewis signaling games framework for zone-private language encoding (research §9E).
 * Two agents (sender + receiver) co-train to develop a shared vocabulary
 * through referential games. The emergent encoding is opaque to outsiders
 * and injection-resistant by design.
 */
public class ArgotTrainer {

    /** A training episode result. */
    public record Episode(
        int round,
        String concept,
        String senderSignal,
        boolean receiverCorrect,
        double reward
    ) {}

    /** Training statistics. */
    public record TrainingStats(
        int totalEpisodes,
        int correctEpisodes,
        double accuracy,
        int vocabularySize,
        int convergenceRound
    ) {
        public boolean hasConverged() {
            return convergenceRound > 0;
        }
    }

    /** A sender-receiver pair playing the Lewis game. */
    private final Map<String, Map<String, Double>> senderPolicy = new LinkedHashMap<>();  // concept → signal → prob
    private final Map<String, Map<String, Double>> receiverPolicy = new LinkedHashMap<>(); // signal → concept → prob
    private final List<Episode> history = new ArrayList<>();
    private final List<String> concepts;
    private final List<String> signals;
    private final Random random;
    private final double learningRate;

    /**
     * Create a trainer for a set of concepts.
     * @param concepts the concepts agents need to communicate
     * @param signalSpace number of distinct signals available
     * @param learningRate how fast policies update (0 to 1)
     */
    public ArgotTrainer(List<String> concepts, int signalSpace, double learningRate) {
        this.concepts = List.copyOf(concepts);
        this.signals = new ArrayList<>();
        for (int i = 0; i < signalSpace; i++) signals.add("s" + i);
        this.learningRate = learningRate;
        this.random = new Random(42); // deterministic for testing

        // Initialize uniform policies
        for (var concept : concepts) {
            var probs = new LinkedHashMap<String, Double>();
            for (var signal : signals) probs.put(signal, 1.0 / signalSpace);
            senderPolicy.put(concept, probs);
        }
        for (var signal : signals) {
            var probs = new LinkedHashMap<String, Double>();
            for (var concept : concepts) probs.put(concept, 1.0 / concepts.size());
            receiverPolicy.put(signal, probs);
        }
    }

    /**
     * Run one training episode.
     * @return the episode result
     */
    public Episode trainEpisode() {
        // Pick a random concept
        var concept = concepts.get(random.nextInt(concepts.size()));

        // Sender picks a signal based on policy
        var signal = sample(senderPolicy.get(concept));

        // Receiver guesses the concept based on signal
        var guess = sample(receiverPolicy.get(signal));

        boolean correct = concept.equals(guess);
        double reward = correct ? 1.0 : 0.0;

        // Update policies (REINFORCE-style)
        if (correct) {
            // Reinforce the correct sender signal
            reinforce(senderPolicy.get(concept), signal);
            // Reinforce the correct receiver interpretation
            reinforce(receiverPolicy.get(signal), concept);
        }

        var episode = new Episode(history.size() + 1, concept, signal, correct, reward);
        history.add(episode);
        return episode;
    }

    /**
     * Run multiple training episodes.
     * @return training statistics
     */
    public TrainingStats train(int episodes) {
        for (int i = 0; i < episodes; i++) {
            trainEpisode();
        }
        return stats();
    }

    /** Get training statistics. */
    public TrainingStats stats() {
        int correct = (int) history.stream().filter(Episode::receiverCorrect).count();
        double accuracy = history.isEmpty() ? 0.0 : (double) correct / history.size();

        // Check for convergence (last 100 episodes > 90% accuracy)
        int convergenceRound = -1;
        int windowSize = Math.min(100, history.size());
        if (history.size() >= windowSize) {
            long recentCorrect = history.subList(history.size() - windowSize, history.size())
                .stream().filter(Episode::receiverCorrect).count();
            if ((double) recentCorrect / windowSize > 0.9) {
                convergenceRound = history.size() - windowSize;
            }
        }

        return new TrainingStats(history.size(), correct, accuracy,
            concepts.size(), convergenceRound);
    }

    /**
     * Extract the learned codebook (most likely signal per concept).
     */
    public Map<String, String> learnedMapping() {
        var mapping = new LinkedHashMap<String, String>();
        for (var concept : concepts) {
            var probs = senderPolicy.get(concept);
            var bestSignal = probs.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("s0");
            mapping.put(concept, bestSignal);
        }
        return mapping;
    }

    /** Get episode history. */
    public List<Episode> history() {
        return List.copyOf(history);
    }

    /** Concept count. */
    public int conceptCount() {
        return concepts.size();
    }

    private String sample(Map<String, Double> distribution) {
        double r = random.nextDouble();
        double cumulative = 0;
        for (var entry : distribution.entrySet()) {
            cumulative += entry.getValue();
            if (r <= cumulative) return entry.getKey();
        }
        // Fallback to last entry
        return distribution.keySet().stream().reduce((a, b) -> b).orElse("");
    }

    private void reinforce(Map<String, Double> distribution, String action) {
        for (var entry : distribution.entrySet()) {
            if (entry.getKey().equals(action)) {
                entry.setValue(entry.getValue() + learningRate * (1 - entry.getValue()));
            } else {
                entry.setValue(entry.getValue() * (1 - learningRate));
            }
        }
        // Normalize
        double sum = distribution.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum > 0) {
            distribution.replaceAll((k, v) -> v / sum);
        }
    }
}
