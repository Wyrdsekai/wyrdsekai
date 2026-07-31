package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ThematicProfile — composition evaluation via attribute overlap.
 */
class ThematicProfileTest {

    @Test
    void identicalProfilesFullOverlap() {
        var profile = new ThematicProfile(
            List.of("knowledge"), List.of("sight", "clarity"), List.of("search"), 0.5);
        assertEquals(1.0, profile.overlapWith(profile), 0.01);
    }

    @Test
    void disjointProfilesZeroOverlap() {
        var a = new ThematicProfile(
            List.of("knowledge"), List.of("sight", "clarity"), List.of("search"), 0.5);
        var b = new ThematicProfile(
            List.of("state"), List.of("fire", "courage"), List.of("enhance"), 0.5);
        assertEquals(0.0, a.overlapWith(b), 0.01);
    }

    @Test
    void partialOverlapReturnsMiddleScore() {
        var crystal = new ThematicProfile(
            List.of("observation"), List.of("sight", "clarity", "truth"), List.of("observe", "sense"), 0.1);
        var book = new ThematicProfile(
            List.of("knowledge"), List.of("memory", "wisdom", "record"), List.of("read", "search"), 0.1);
        // No overlap in any category
        assertEquals(0.0, crystal.overlapWith(book), 0.01);
        assertFalse(crystal.resonatesWith(book));

        // Crystal + Oracle Lens — shared observation domain and sight symbol
        var oracleLens = new ThematicProfile(
            List.of("observation", "knowledge"), List.of("foresight", "sight", "time"), List.of("predict", "observe"), 0.2);
        double overlap = crystal.overlapWith(oracleLens);
        assertTrue(overlap > 0.2, "Crystal and Oracle Lens should have meaningful overlap: " + overlap);
        assertTrue(crystal.resonatesWith(oracleLens));
    }

    @Test
    void symbolsWeightedMostHeavily() {
        // Same symbols, different everything else
        var a = new ThematicProfile(
            List.of("knowledge"), List.of("sight", "clarity"), List.of("search"), 0.0);
        var b = new ThematicProfile(
            List.of("state"), List.of("sight", "clarity"), List.of("enhance"), 0.0);
        double overlap = a.overlapWith(b);
        // Symbols weight is 0.5 — with full symbol overlap, score should be at least 0.5
        assertTrue(overlap >= 0.5, "Symbol-heavy overlap should score >= 0.5, got " + overlap);
    }

    @Test
    void emptyProfileZeroOverlap() {
        var profile = new ThematicProfile(
            List.of("knowledge"), List.of("sight"), List.of("search"), 0.5);
        assertEquals(0.0, profile.overlapWith(ThematicProfile.EMPTY), 0.01);
        assertEquals(0.0, ThematicProfile.EMPTY.overlapWith(profile), 0.01);
    }

    @Test
    void nullProfileZeroOverlap() {
        var profile = new ThematicProfile(
            List.of("knowledge"), List.of("sight"), List.of("search"), 0.5);
        assertEquals(0.0, profile.overlapWith(null), 0.01);
    }

    @Test
    void resonatesWithThresholdPoint2() {
        var a = new ThematicProfile(
            List.of("observation"), List.of("sight"), List.of("observe"), 0.0);
        var b = new ThematicProfile(
            List.of("creation"), List.of("sight"), List.of("build"), 0.0);
        // Share "sight" symbol — overlap should be > 0.2 due to symbol weight
        assertTrue(a.resonatesWith(b),
            "Items sharing a symbol should resonate");
    }

    @Test
    void compositionScenarioCrystalAndBook() {
        // The spec scenario: crystal (observation/sight) + book (knowledge/memory)
        // These DON'T share attributes — composition requires LLM evaluation
        var crystal = new ThematicProfile(
            List.of("observation"), List.of("sight", "clarity", "truth"),
            List.of("observe", "sense", "reveal"), 0.0);
        var book = new ThematicProfile(
            List.of("knowledge"), List.of("memory", "wisdom", "record"),
            List.of("read", "search", "store"), 0.0);

        assertFalse(crystal.resonatesWith(book),
            "Crystal + Book should NOT resonate on attributes alone — needs narrative evaluation");
    }

    @Test
    void compositionScenarioPortalAndAutomator() {
        // Portal (communication/portal) + Automator (communication/vigilance)
        // These SHARE communication domain — template composition possible
        var portal = new ThematicProfile(
            List.of("communication", "knowledge"), List.of("portal", "window", "sight"),
            List.of("fetch", "view"), 0.1);
        var automator = new ThematicProfile(
            List.of("communication", "observation"), List.of("vigilance", "signal", "alert"),
            List.of("watch", "alert", "filter"), 0.0);

        double overlap = portal.overlapWith(automator);
        assertTrue(overlap > 0.1,
            "Portal + Automator should have some overlap via communication domain: " + overlap);
    }
}
