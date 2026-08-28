package org.wyrdsekai.core.soul;

import java.util.Map;
import java.util.Random;

/**
 * Generic search space interface for evolutionary exploration.
 * Local copy compatible with CodeZaiku's org.codezaiku.core.evolution.SearchSpace.
 *
 * Wyrdsekai and CodeZaiku are sibling projects that cannot directly depend
 * on each other. This local interface mirrors CodeZaiku's contract so that
 * SoulSearchSpace can be used with CodeZaiku's SearchRunner when both
 * projects are present. The type signatures are identical.
 *
 * @param <P> Parameter type (the "genome" -- what gets evolved)
 * @param <R> Result type (the "phenotype" -- what gets measured)
 * @see SoulSearchSpace
 */
public interface SearchSpace<P, R> {

    /** Human-readable name for this search space. */
    String name();

    /** Describe the dimensionality and bounds of this space. */
    SpaceDescription describe();

    /** Generate a random point in the space (for initial population). */
    P randomPoint(Random rng);

    /** Crossover two parents to produce a child. */
    P crossover(P parent1, P parent2, Random rng);

    /** Mutate a point (small perturbation within bounds). */
    P mutate(P point, double mutationRate, Random rng);

    /**
     * Evaluate a point. Returns a result with a fitness score.
     * This is the expensive operation -- may involve inference, testing, etc.
     */
    R evaluate(P point);

    /** Extract fitness from a result (higher = better). */
    double fitness(R result);

    /** Serialize a point for persistence/logging. */
    Map<String, Object> serialize(P point);

    /** Deserialize a point from stored form. */
    P deserialize(Map<String, Object> data);
}
