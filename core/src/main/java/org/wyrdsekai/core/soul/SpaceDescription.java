package org.wyrdsekai.core.soul;

import java.util.List;

/**
 * Describes the dimensionality and bounds of a search space.
 * Local copy compatible with CodeZaiku's SpaceDescription.
 *
 * @param name           Space name
 * @param dimensions     Parameter dimensions
 * @param fitnessMetric  Description of what fitness measures (higher = better)
 * @param description    Human-readable description of the space
 */
public record SpaceDescription(
    String name,
    List<Dimension> dimensions,
    String fitnessMetric,
    String description
) {

    /**
     * A single dimension in the search space.
     */
    public sealed interface Dimension {
        String name();
        String description();

        /** Continuous real-valued dimension with min/max bounds. */
        record Continuous(String name, String description,
                          double min, double max, double defaultValue) implements Dimension {}

        /** Discrete dimension with a fixed set of allowed values. */
        record Discrete(String name, String description,
                        List<String> values, String defaultValue) implements Dimension {}

        /** Integer-valued dimension with min/max bounds. */
        record IntRange(String name, String description,
                        int min, int max, int defaultValue) implements Dimension {}

        /** Boolean dimension. */
        record BooleanDim(String name, String description,
                          boolean defaultValue) implements Dimension {}
    }
}
