package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Forge consolidation MUST skip {@link FragmentKind#EPISODIC}.
 *
 * <p>The design memo: "Each scene is a specific moment, not material to merge.
 * Episodic is the raw material; narrative is the integration; the self is the
 * bridge." The reinforce/merge pass in {@link SoulMaintenanceCycle#reinforceFragments}
 * must therefore carry EPISODIC fragments through untouched, even if a freshly
 * extracted NARRATIVE fragment happens to collide on category+label.</p>
 */
class EpisodicForgeSkipTest {

    @Test
    void episodicFragmentSurvivesReinforcePassUnchanged() {
        var episodic = SoulFragment.fromEpisodicScene(
            "episodic-scene-1", "episodic", "scene-1",
            "I let him have the quiet. I almost said something.", "scene-1");
        var existing = List.of(episodic);
        var newNarrative = List.of(
            SoulFragment.unembedded("p1", "personality", "core", "ember is patient"));

        var merged = SoulMaintenanceCycle.reinforceFragments(existing, newNarrative);

        // Both survive — EPISODIC bypasses the merge pass, NARRATIVE enters.
        assertEquals(2, merged.size(), "episodic + new narrative both present");
        var survivor = merged.stream()
            .filter(f -> f.kind() == FragmentKind.EPISODIC)
            .findFirst().orElseThrow();
        assertEquals(episodic.id(), survivor.id(), "episodic id preserved");
        assertEquals(episodic.text(), survivor.text(), "episodic text preserved verbatim");
        assertEquals("scene-1", survivor.sceneId(), "episodic sceneId preserved");
        assertEquals(FragmentKind.EPISODIC, survivor.kind(), "episodic kind preserved");
    }

    @Test
    void episodicWithMatchingCategoryAndLabelIsStillNotMerged() {
        // Even if a NARRATIVE fragment collides on category+label with an
        // EPISODIC one, the EPISODIC must NOT be merged. The §10 rule is a
        // hard skip — episodic memories are not consolidation material.
        var episodic = SoulFragment.fromEpisodicScene(
            "ep-1", "memory", "by-the-fire", "I noticed his hands.", "scene-7");
        var existing = List.of(episodic);
        var newFrag = SoulFragment.unembedded(
            "newer", "memory", "by-the-fire", "DIFFERENT TEXT THAT WOULD HAVE MERGED");

        var merged = SoulMaintenanceCycle.reinforceFragments(existing, List.of(newFrag));

        assertEquals(2, merged.size(), "no merge — episodic kept, new narrative added separately");
        var ep = merged.stream().filter(f -> f.kind() == FragmentKind.EPISODIC)
            .findFirst().orElseThrow();
        assertEquals("I noticed his hands.", ep.text(),
            "EPISODIC text untouched — the merge cannot consume it even on collision");
        assertEquals("scene-7", ep.sceneId(), "EPISODIC sceneId untouched");
    }

    @Test
    void narrativeKindIsPreservedOnReinforcement() {
        // Regression: the merge used to drop kind + sceneId via the 14-arg ctor.
        // §10 requires preserving them so DEXTERITY/CONVENTION/STRUCTURAL
        // (and §14 scene-derived NARRATIVE with sceneId) survive consolidation.
        var existing = List.of(
            SoulFragment.dexterity("dx-1", "build", "passed-test",
                "ran ./gradlew :core:test → green"));
        var newFrag = SoulFragment.unembedded("dx-1-new", "build", "passed-test",
            "ran ./gradlew :core:test → green (and faster this time)");

        var merged = SoulMaintenanceCycle.reinforceFragments(existing, List.of(newFrag));

        assertEquals(1, merged.size(), "matching category+label triggers merge into one fragment");
        var m = merged.get(0);
        assertEquals(FragmentKind.DEXTERITY, m.kind(),
            "merged fragment preserves DEXTERITY kind (16-arg ctor fix)");
        // text gets the longer of the two (new is longer here).
        assertTrue(m.text().contains("faster this time"),
            "merged text takes the longer body — " + m.text());
    }

    @Test
    void emptyExistingPassesThroughNewFragments() {
        // Edge: when existing is empty (or null), the function bypasses the
        // merge altogether — must still work with the new EPISODIC partition.
        var newFrag = SoulFragment.fromEpisodicScene("ep-1", "episodic",
            "scene-1", "inner prose", "scene-1");
        var merged = SoulMaintenanceCycle.reinforceFragments(List.of(), List.of(newFrag));
        assertEquals(1, merged.size());
        assertEquals(FragmentKind.EPISODIC, merged.get(0).kind());
    }
}
