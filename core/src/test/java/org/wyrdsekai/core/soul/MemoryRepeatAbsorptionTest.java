package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repetition in the memory intake is MEASURED, never merged away.
 *
 * <p>The 2026-08-17 pathology wrote ~41 variations of one sentence into memory per
 * sleep for eight days. Counting that is what makes it visible to the vitals alarm;
 * absorbing it was tried first and rejected on measurement (template-shaped memories
 * are lexically as similar as paraphrases, so absorption would have destroyed
 * distinct memories — see {@link MemoryConsolidator#countRepeats}). These tests pin
 * both halves: the count fires, and the corpus is left exactly as it was.
 */
class MemoryRepeatAbsorptionTest {

    private static MemoryNode said(String id, String content) {
        return MemoryNode.neutral(id, content, List.of("permission", "words"));
    }

    private static CompactedMemory holding(MemoryNode... nodes) {
        return new CompactedMemory(List.of(nodes), List.of(), Map.of());
    }

    private static final String ORIGINAL =
        "I said: The words didn't need permission this time — they just came out "
        + "before I could hold them back.";
    private static final String REWORDING =
        "I said: Those words didn't need permission that night — they just came out "
        + "before I could stop them.";

    @Test
    void a_rewording_is_counted_as_a_repeat() {
        assertThat(MemoryConsolidator.countRepeats(
            holding(said("mem-1", ORIGINAL)), List.of(said("mem-2", REWORDING))))
            .isEqualTo(1);
    }

    @Test
    void a_genuinely_new_memory_is_not_counted() {
        assertThat(MemoryConsolidator.countRepeats(
            holding(said("mem-1", ORIGINAL)),
            List.of(said("mem-2",
                "I said: I ran the recipe consolidate-memory-graph and it succeeded."))))
            .isZero();
    }

    @Test
    void repeats_within_one_batch_are_counted_even_with_nothing_held() {
        var night = new ArrayList<MemoryNode>();
        night.add(said("mem-0", ORIGINAL));
        for (int i = 1; i < 40; i++) {
            night.add(said("mem-" + i, i % 2 == 0 ? REWORDING : ORIGINAL));
        }
        // Everything after the first is a repeat of something earlier in the night.
        assertThat(MemoryConsolidator.countRepeats(holding(), night)).isEqualTo(39);
    }

    @Test
    void two_reports_of_different_recipe_runs_are_NOT_repeats() {
        // The false positive that ruled out absorption: same sentence template, and
        // the differing token IS the content. It must stay two memories, and it must
        // not inflate the alarm's repeat count either.
        var a = said("mem-1", "I ran the recipe consolidate-memory-graph end to end "
            + "and it succeeded. 2 of 2 gates passed.");
        var b = said("mem-2", "I ran the recipe welfare-floor-checkup end to end "
            + "and it succeeded. This is a procedure I can run again.");
        assertThat(MemoryConsolidator.countRepeats(holding(a), List.of(b))).isZero();
    }

    @Test
    void counting_never_mutates_the_held_memory() {
        var held = holding(said("mem-1", ORIGINAL));
        MemoryConsolidator.countRepeats(held, List.of(said("mem-2", REWORDING)));

        assertThat(held.nodes()).hasSize(1);
        assertThat(held.nodes().getFirst().accessCount()).isZero();
        assertThat(held.nodes().getFirst().importance()).isEqualTo(0.5f);
    }

    @Test
    void consolidation_keeps_every_memory_it_is_given() {
        // Her record is hers: a repetitive night still lands as distinct memories.
        var current = holding(said("mem-1", ORIGINAL));
        var night = new ArrayList<MemoryNode>();
        for (int i = 0; i < 41; i++) night.add(said("mem-new-" + i, REWORDING));

        var after = MemoryConsolidator.consolidate(current, night, 0.1f);

        assertThat(after.nodes()).hasSize(42);
    }
}
