package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.SubjectReading.Passage;
import org.wyrdsekai.core.agent.SubjectReading.Source;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What counts as reading on part of a subject she said she would learn: only what the library
 * found above its floor, or what the web returned when it is open to her. A search that found
 * nothing is not a reading (review of 2026-09-22).
 */
class SubjectReadingTest {

    private static Passage lib(String title, String text) {
        return new Passage(title, text, Source.LIBRARY);
    }

    private static Function<String, List<Passage>> returning(List<Passage> passages, List<String> asked) {
        return q -> {
            asked.add(q);
            return passages;
        };
    }

    @Test
    void the_library_answers_first_and_the_web_is_not_asked() {
        var webAsked = new ArrayList<String>();
        var r = SubjectReading.read("attention mechanisms", null,
            q -> List.of(lib("Attention Is All You Need", "The dominant sequence transduction models...")),
            returning(List.of(new Passage("x", "y", Source.WEB)), webAsked));
        assertThat(r.found()).isTrue();
        assertThat(r.source()).isEqualTo(Source.LIBRARY);
        assertThat(webAsked).isEmpty();
        assertThat(SubjectReading.note(r)).isEqualTo("Attention Is All You Need (in the library)");
    }

    @Test
    void nothing_in_the_library_and_no_web_is_nothing_read() {
        var r = SubjectReading.read("diffusion", null, q -> List.of(), null);
        assertThat(r.found()).isFalse();
        assertThat(r.why()).isEqualTo("the library had nothing on it");
        assertThat(SubjectReading.note(r)).isNull();
        assertThat(SubjectReading.memoryEntry("a subject", "diffusion", r)).isNull();
    }

    @Test
    void the_web_is_read_when_the_library_has_nothing_and_it_is_open() {
        var r = SubjectReading.read("diffusion", null, q -> List.of(),
            q -> List.of(new Passage("Denoising Diffusion Probabilistic Models", "We present high quality image synthesis", Source.WEB)));
        assertThat(r.source()).isEqualTo(Source.WEB);
        assertThat(SubjectReading.note(r)).isEqualTo("Denoising Diffusion Probabilistic Models (on the web)");
        assertThat(SubjectReading.memoryEntry("attention, diffusion", "diffusion", r))
            .startsWith("What I read on \"diffusion\" (for what I said I would learn: attention, diffusion), on the web:")
            .contains("1. Denoising Diffusion Probabilistic Models: We present high quality image synthesis");
    }

    @Test
    void an_empty_web_answer_or_a_failing_search_is_nothing_read() {
        assertThat(SubjectReading.read("diffusion", null, q -> List.of(), q -> List.of()).why())
            .isEqualTo("neither the library nor the web had anything on it");
        var failing = SubjectReading.read("diffusion", null, q -> { throw new IllegalStateException("down"); }, q -> null);
        assertThat(failing.found()).isFalse();
    }

    @Test
    void empty_passages_do_not_count_and_a_reading_keeps_at_most_four() {
        var many = new ArrayList<Passage>();
        many.add(lib("", " "));
        for (int i = 0; i < 6; i++) many.add(lib("Book " + i, "text " + i));
        var r = SubjectReading.read("optics", null, q -> many, null);
        assertThat(r.passages()).hasSize(SubjectReading.MAX_PASSAGES);
        assertThat(r.passages().get(0).title()).isEqualTo("Book 0");
        assertThat(SubjectReading.read("optics", null, q -> List.of(lib(null, "  ")), null).found()).isFalse();
    }

    /**
     * 2026-09-23, household node: the reading on "transformers" of "transformers, attention
     * mechanisms, diffusion models" searched that one word and kept textbooks on electricity as
     * what she read. Both legs are asked for the part with the rest of her subject beside it;
     * what she keeps is still filed under the part.
     */
    @Test
    void a_part_is_searched_with_the_rest_of_her_subject_on_both_legs() {
        var subject = "transformers, attention mechanisms, diffusion models";
        var libraryAsked = new ArrayList<String>();
        var webAsked = new ArrayList<String>();
        var r = SubjectReading.read("transformers", subject, returning(List.of(), libraryAsked),
            returning(List.of(new Passage("Attention Is All You Need", "The Transformer, based solely on attention mechanisms",
                Source.WEB)), webAsked));
        assertThat(libraryAsked).containsExactly("transformers (attention mechanisms, diffusion models)");
        assertThat(webAsked).containsExactly("transformers (attention mechanisms, diffusion models)");
        assertThat(r.source()).isEqualTo(Source.WEB);
        assertThat(SubjectReading.memoryEntry(subject, "transformers", r))
            .startsWith("What I read on \"transformers\" (for what I said I would learn: " + subject + "), on the web:");
    }

    @Test
    void the_query_names_the_part_first_and_leaves_out_how_she_framed_the_subject() {
        assertThat(SubjectReading.queryFor("transformer architecture",
                "what holds everything together: attention mechanisms, transformer architecture, then diffusion as you asked"))
            .isEqualTo("transformer architecture (attention mechanisms, diffusion)");
        assertThat(SubjectReading.queryFor("Diffusion", "attention, then diffusion")).isEqualTo("Diffusion (attention)");
        assertThat(SubjectReading.queryFor("optics", "optics")).as("a subject of one part").isEqualTo("optics");
        assertThat(SubjectReading.queryFor("optics", null)).isEqualTo("optics");
    }

    /**
     * The search carries the rest of her subject, so the same passages come back for every part:
     * one is filed under a part only when it is about it (review of 2026-09-23).
     */
    @Test
    void a_passage_about_another_part_is_not_filed_under_this_one() {
        var subject = "attention mechanisms, transformer architecture, diffusion";
        var attentionOnly = List.of(new SubjectReading.Passage("Attention Is All You Need",
            "The Transformer, based solely on attention mechanisms, dispensing with recurrence.",
            SubjectReading.Source.LIBRARY));
        var webAsked = new AtomicBoolean();
        var r = SubjectReading.read("diffusion", subject, q -> attentionOnly, q -> { webAsked.set(true); return List.of(); });
        assertThat(r.found()).isFalse();
        assertThat(webAsked).isTrue();

        var aboutDiffusion = List.of(new SubjectReading.Passage("Denoising Diffusion Probabilistic Models",
            "Diffusion models learn to reverse a gradual noising process.", SubjectReading.Source.LIBRARY));
        assertThat(SubjectReading.read("diffusion", subject, q -> aboutDiffusion, null).found()).isTrue();
        assertThat(SubjectReading.read("attention mechanisms", subject, q -> attentionOnly, null).found())
            .as("the kind word need not appear; the topic word does").isTrue();
    }
}
