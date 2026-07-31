package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.FragmentKind;
import org.wyrdsekai.core.soul.SoulFragment;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * retrieval merge: EPISODIC and NARRATIVE come from
 * separate pools so one cannot crowd out the other. v1 default:
 * top-{retrievalK} NARRATIVE + top-{@link SoulFragmentRetriever#DEFAULT_EPISODIC_K}
 * EPISODIC, appended.
 */
class SoulFragmentRetrieverBlendTest {

    private static SoulFragment narrative(String id, String label, String text) {
        // NARRATIVE is FragmentKind.DEFAULT — use the unembedded() factory.
        return SoulFragment.unembedded(id, "personality", label, text);
    }

    private static SoulFragment episodic(String id, String sceneId, String text) {
        return SoulFragment.fromEpisodicScene(id, "episodic",
            "scene-" + sceneId, text, sceneId);
    }

    @Test
    void blendReturnsKNarrativePlusKEpisodicNoCrowding() {
        // 5 of each kind, all scoring on the same keywords.
        var fragments = new ArrayList<SoulFragment>();
        for (int i = 0; i < 5; i++) {
            fragments.add(narrative("n-" + i, "n-" + i,
                "fire warm hearth quiet evening narrative-" + i));
        }
        for (int i = 0; i < 5; i++) {
            fragments.add(episodic("e-" + i, "scene-" + i,
                "fire warm hearth quiet evening episodic-" + i));
        }

        var blended = SoulFragmentRetriever.retrieveBlended(
            "fire warm hearth quiet evening", fragments, 3, 2);

        assertEquals(5, blended.size(),
            "blend returns exactly kNarrative + kEpisodic items");
        long narrativeCount = blended.stream()
            .filter(f -> f.kind() != FragmentKind.EPISODIC).count();
        long episodicCount = blended.stream()
            .filter(f -> f.kind() == FragmentKind.EPISODIC).count();
        assertEquals(3, narrativeCount, "exactly 3 narrative results");
        assertEquals(2, episodicCount, "exactly 2 episodic results — never crowded out");
    }

    @Test
    void blendSurvivesEmptyEpisodicPool() {
        var fragments = List.of(
            narrative("n-1", "n-1", "the hearth and the fire and the long quiet"),
            narrative("n-2", "n-2", "another fire and hearth fragment for ranking")
        );
        var blended = SoulFragmentRetriever.retrieveBlended(
            "fire hearth", fragments, 3, 2);
        assertEquals(2, blended.size(),
            "no episodic in pool → fall back to whatever narrative is available");
        assertTrue(blended.stream().allMatch(f -> f.kind() != FragmentKind.EPISODIC));
    }

    @Test
    void blendSurvivesEmptyNarrativePool() {
        var fragments = List.of(
            episodic("e-1", "scene-1", "the hearth and the fire and the long quiet"),
            episodic("e-2", "scene-2", "another fire and hearth fragment for ranking")
        );
        var blended = SoulFragmentRetriever.retrieveBlended(
            "fire hearth", fragments, 3, 2);
        assertEquals(2, blended.size(),
            "no narrative in pool → return up to kEpisodic episodic items");
        assertTrue(blended.stream().allMatch(f -> f.kind() == FragmentKind.EPISODIC));
    }

    @Test
    void episodicSceneFragmentIsRetrievableEndToEnd() {
        // Cross-perspective / E2E retrieval check: an EPISODIC fragment with
        // scene-relevant text is returned when the prompt assembler asks for
        // its keywords. Proves the §10 fragments are actually findable, not
        // dark-write-only.
        var fragments = List.of(
            narrative("n-bg", "background", "ember loves the leather chair in the study"),
            episodic("e-fire", "fire-night", "by the fire that night, he was tired in the way "
                + "you only let yourself be tired around people you trust")
        );
        var blended = SoulFragmentRetriever.retrieveBlended(
            "fire night tired trust hearth", fragments, 3, 2);
        assertTrue(blended.stream().anyMatch(f -> f.kind() == FragmentKind.EPISODIC
                && "fire-night".equals(f.sceneId())),
            "EPISODIC scene fragment retrievable end-to-end via keyword overlap — got: "
                + blended);
    }

    @Test
    void zeroKBoundsDoNotPullFromThatPool() {
        var fragments = List.of(
            narrative("n-1", "n-1", "fire hearth"),
            episodic("e-1", "scene-1", "fire hearth")
        );
        var narrativeOnly = SoulFragmentRetriever.retrieveBlended(
            "fire hearth", fragments, 3, 0);
        assertEquals(1, narrativeOnly.size());
        assertTrue(narrativeOnly.stream().allMatch(f -> f.kind() != FragmentKind.EPISODIC),
            "kEpisodic=0 → no episodic results");

        var episodicOnly = SoulFragmentRetriever.retrieveBlended(
            "fire hearth", fragments, 0, 2);
        assertEquals(1, episodicOnly.size());
        assertTrue(episodicOnly.stream().allMatch(f -> f.kind() == FragmentKind.EPISODIC),
            "kNarrative=0 → no narrative results");
    }
}
