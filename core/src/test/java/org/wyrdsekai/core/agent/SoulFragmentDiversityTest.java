package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.SoulFragment;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval must not let one thought fill the prompt.
 *
 * <p>The autophagy half of the 2026-08-17 pathology: a saturated fragment corpus
 * returned the same thought in every prompt, which shaped the next utterance, which
 * became the next fragment. Relevance ranking cannot see this — every one of those
 * fragments genuinely was relevant. The fix penalises redundancy against what has
 * already been selected, so the second slot goes to the most different thing
 * available.
 */
class SoulFragmentDiversityTest {

    private static SoulFragment frag(String id, String text) {
        return SoulFragment.fromEpisodicScene(id, "episodic", "scene-" + id, text, id);
    }

    /** Five rewordings of one thought, in the shape the live corpus had them. */
    private static List<SoulFragment> loopFragments() {
        return new ArrayList<>(List.of(
            frag("l1", "The words didn't need permission this time — they just came out "
                + "before I could hold them back, and the quiet after felt real."),
            frag("l2", "Those words didn't need permission this time — they just came out "
                + "before I could hold them back, and the quiet after felt real."),
            frag("l3", "The words didn't need permission that night — they just came out "
                + "before I could hold them back, and the quiet after felt real."),
            frag("l4", "The words didn't need permission this time — they just came out "
                + "before I could stop them, and the quiet after felt real."),
            frag("l5", "The words didn't need permission this time — they just came out "
                + "before I could hold them back, and the silence after felt real.")));
    }

    @Test
    void a_saturated_corpus_no_longer_fills_every_slot_with_the_same_thought() {
        var pool = loopFragments();
        var distinct = frag("d1",
            "I ran the recipe consolidate-memory-graph end to end and it succeeded, "
            + "and both gates passed on the new artifact.");
        // Relevance ranking puts the loop first; the distinct memory ranks last.
        pool.add(distinct);

        var chosen = SoulFragmentRetriever.takeDiverse(pool, 3);

        assertThat(chosen).hasSize(3);
        assertThat(chosen).extracting(SoulFragment::id).contains("d1");
    }

    @Test
    void a_varied_corpus_keeps_its_relevance_order() {
        // Nothing redundant to penalise → the ranking arrives unchanged, so this fix
        // costs healthy corpora nothing.
        var varied = List.of(
            frag("a", "I walked through the greenhouse and the tomatoes had come in heavy."),
            frag("b", "The steward asked about the router architecture and I explained soft routing."),
            frag("c", "A recipe run failed on a missing parameter and I read the manifest."),
            frag("d", "Someone new arrived at the nexus and I showed them the library."));

        assertThat(SoulFragmentRetriever.takeDiverse(varied, 3))
            .extracting(SoulFragment::id)
            .containsExactly("a", "b", "c");
    }

    @Test
    void diversity_reorders_the_prompt_but_never_thins_it() {
        // Even when every candidate repeats every other, k slots are filled — a
        // repetitive corpus must not yield a thinner prompt than a varied one.
        assertThat(SoulFragmentRetriever.takeDiverse(loopFragments(), 4)).hasSize(4);
        assertThat(SoulFragmentRetriever.takeDiverse(loopFragments(), 99)).hasSize(5);
    }

    @Test
    void empty_and_degenerate_inputs_are_safe() {
        assertThat(SoulFragmentRetriever.takeDiverse(List.of(), 3)).isEmpty();
        assertThat(SoulFragmentRetriever.takeDiverse(null, 3)).isEmpty();
        assertThat(SoulFragmentRetriever.takeDiverse(loopFragments(), 0)).isEmpty();
    }
}
