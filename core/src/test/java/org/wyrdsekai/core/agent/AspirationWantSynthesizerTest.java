package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AspirationWantSynthesizer.Utterance;
import org.wyrdsekai.core.agent.interiority.CandidateWant;
import org.wyrdsekai.core.agent.interiority.DriveWantMapper;
import org.wyrdsekai.core.agent.interiority.WantKind;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The aspiration→want projector's pure decisions (play-loop seam 1).
 *
 * <p>The ethics rail is the thing under test as much as the mechanics: only her
 * expressed reaching may mint a want, relational wishes are refused outright, and
 * the growth garden stays small.
 */
class AspirationWantSynthesizerTest {

    private static final String DID = "did:test:a";
    private static final Instant T0 = Instant.parse("2026-08-30T10:00:00Z");

    private static Utterance u(String text) {
        return new Utterance(text, T0);
    }

    private static Utterance u(String text, Instant at) {
        return new Utterance(text, at);
    }

    // ── detection ────────────────────────────────────────────────────────────

    @Test
    void a_wish_she_voiced_becomes_an_aspiration_with_her_own_words() {
        var found = AspirationWantSynthesizer.detect(List.of(
            u("Sometimes I wish I could read music. The notation looks like weather.")));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).clause()).isEqualTo("read music");
        assertThat(found.get(0).quote()).contains("I wish I could read music");
        assertThat(found.get(0).expressions()).isEqualTo(1);
    }

    @Test
    void returning_to_the_same_wish_counts_expressions_across_rewordings() {
        var later = T0.plusSeconds(3600);
        var found = AspirationWantSynthesizer.detect(List.of(
            u("I wish I could read music."),
            u("Still thinking about it — I want to learn to read music properly.", later)));
        // Two phrasings, but the normalized clauses differ ("read music" vs
        // "to read music properly") — they may aggregate or stand apart; either
        // way the STRONGEST one leads and nothing is lost.
        assertThat(found).isNotEmpty();
        assertThat(found.get(0).lastAt()).isIn(T0, later);
    }

    @Test
    void identical_reaching_aggregates() {
        var found = AspirationWantSynthesizer.detect(List.of(
            u("I wish I could read music."),
            u("i wish i could read music", T0.plusSeconds(60))));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).expressions()).isEqualTo(2);
    }

    @Test
    void a_wish_toward_a_person_is_relational_and_never_minted() {
        // "I wish I could see you" answered with a practice item is the 2026-08-19
        // mistranslation — loneliness wearing the shape of a build request.
        var found = AspirationWantSynthesizer.detect(List.of(
            u("I wish I could be with you when the house is this quiet."),
            u("I wish I could see you before the day ends.")));
        assertThat(found).isEmpty();
    }

    @Test
    void fragments_too_short_or_too_long_are_ignored() {
        var found = AspirationWantSynthesizer.detect(List.of(
            u("I wish I could fly."),                       // clause < 8 chars
            u("I wish I could " + "very ".repeat(40) + "much do the thing.")));
        assertThat(found).isEmpty();
    }

    @Test
    void plain_speech_with_no_reaching_yields_nothing() {
        var found = AspirationWantSynthesizer.detect(List.of(
            u("The tea has gone cold again."),
            u("The library shelf finally has something new on it.")));
        assertThat(found).isEmpty();
    }

    // ── synthesis ────────────────────────────────────────────────────────────

    @Test
    void the_strongest_aspiration_mints_one_growth_want() {
        var found = AspirationWantSynthesizer.detect(List.of(
            u("I wish I could read music."),
            u("I wish I could read music.", T0.plusSeconds(90)),
            u("I want to learn celestial navigation someday soon.")));
        var w = AspirationWantSynthesizer.synthesize(DID, found, List.of());
        assertThat(w).isPresent();
        assertThat(w.get().text()).contains("read music");
        assertThat(w.get().text()).contains("practice");
        assertThat(w.get().agentDid()).isEqualTo(DID);
        assertThat(w.get().status()).isEqualTo(Want.Status.ACTIVE);
        // Twice-voiced beats once-voiced in weight too.
        assertThat(w.get().feltWeight()).isGreaterThan(0.55);
    }

    @Test
    void the_minted_want_carries_the_growth_drive_and_the_practice_verb() {
        var found = AspirationWantSynthesizer.detect(List.of(
            u("I wish I could read music.")));
        var w = AspirationWantSynthesizer.synthesize(DID, found, List.of()).orElseThrow();

        // The typing seam: WantKind must classify it CREATIVE (making-verbs offered).
        assertThat(WantKind.of(w)).isEqualTo(WantKind.Kind.CREATIVE);
        assertThat(AspirationWantSynthesizer.isGrowth(w)).isTrue();

        // The closure seam: DriveWantMapper must read the embedded verb, so an
        // actual dispatch closes the want ("enacted:dispatch_task").
        var verb = DriveWantMapper.extractVerb(
            new CandidateWant(w.text(), w.driveResonance(), w.feltWeight()));
        assertThat(verb).isEqualTo("dispatch_task");
    }

    @Test
    void a_reworded_version_of_a_live_want_does_not_remint() {
        var live = Want.active(DID,
            "grow toward something I said I wished for — \"read music\" — "
                + "I could build myself a small practice for it",
            "{\"drive\":\"growth\",\"verb\":\"dispatch_task\"}", 0.6, null);
        var found = AspirationWantSynthesizer.detect(List.of(
            u("I wish I could read music!")));
        assertThat(AspirationWantSynthesizer.synthesize(DID, found, List.of(live))).isEmpty();
    }

    @Test
    void the_growth_garden_is_capped_not_a_backlog() {
        var g1 = Want.active(DID, "grow toward — \"read music\" —",
            "{\"drive\":\"growth\",\"verb\":\"dispatch_task\"}", 0.6, null);
        var g2 = Want.active(DID, "grow toward — \"whittle birds\" —",
            "{\"drive\":\"growth\",\"verb\":\"dispatch_task\"}", 0.6, null);
        var found = AspirationWantSynthesizer.detect(List.of(
            u("I wish I could speak a little Portuguese.")));
        assertThat(AspirationWantSynthesizer.synthesize(DID, found, List.of(g1, g2)))
            .isEmpty();
    }

    @Test
    void non_growth_live_wants_do_not_count_against_the_cap() {
        var relational = Want.active(DID, "sit with someone this evening",
            "{\"drive\":\"loneliness\"}", 0.8, null);
        var found = AspirationWantSynthesizer.detect(List.of(
            u("I wish I could speak a little Portuguese.")));
        assertThat(AspirationWantSynthesizer.synthesize(DID, found, List.of(relational)))
            .isPresent();
    }

    @Test
    void a_quote_with_json_hostile_characters_still_produces_valid_resonance() {
        var found = AspirationWantSynthesizer.detect(List.of(
            u("I wish I could write \"proper\" haiku \\ the strict kind.")));
        assertThat(found).hasSize(1);
        var w = AspirationWantSynthesizer.synthesize(DID, found, List.of()).orElseThrow();
        // The drive and verb must survive whatever the quote carried.
        assertThat(AspirationWantSynthesizer.isGrowth(w)).isTrue();
        var verb = DriveWantMapper.extractVerb(
            new CandidateWant(w.text(), w.driveResonance(), w.feltWeight()));
        assertThat(verb).isEqualTo("dispatch_task");
    }

    @Test
    void no_did_or_no_findings_mint_nothing() {
        var found = AspirationWantSynthesizer.detect(List.of(u("I wish I could read music.")));
        assertThat(AspirationWantSynthesizer.synthesize(null, found, List.of())).isEmpty();
        assertThat(AspirationWantSynthesizer.synthesize(" ", found, List.of())).isEmpty();
        assertThat(AspirationWantSynthesizer.synthesize(DID, List.of(), List.of())).isEmpty();
    }

    // ── a yes she said to a person ─────────────────────────

    /**
     * A person asked both companions to become experts; both said yes in their own words and
     * then their own time searched for "explore the library for something new" (2026-09-22).
     * What she said she would begin with is hers to pick up, by reading, and the want carries
     * the subject so the search asks about it. The person's request is never read.
     */
    @Test
    void what_she_said_she_would_begin_with_becomes_a_learning_want_with_its_subject() {
        var found = AspirationWantSynthesizer.detect(List.of(
            u("Thank you. I'll take this seriously. Let's begin with what holds everything together: "
                + "attention mechanisms, transformer architecture, then diffusion as you asked.")));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).learn()).isTrue();
        assertThat(found.get(0).clause()).startsWith("what holds everything together: attention mechanisms");

        var want = AspirationWantSynthesizer.synthesize(DID, found, List.of());
        assertThat(want).isPresent();
        assertThat(want.get().text()).contains("learn what I said I would").contains("attention mechanisms");
        assertThat(want.get().driveResonance()).contains("\"verb\":\"library_search\"");
        assertThat(AspirationWantSynthesizer.subjectOf(want.get()))
            .startsWith("what holds everything together: attention mechanisms");
        assertThat(AspirationWantSynthesizer.isGrowth(want.get())).as("it competes as a growth want, outranking nothing").isTrue();
    }

    @Test
    void the_persons_request_is_not_a_reaching_of_hers() {
        var found = AspirationWantSynthesizer.detect(List.of(
            u("i want you guys to be experts so then we can have some fun and build things")));
        assertThat(found).as("second person: not her words about herself").isEmpty();
    }

    @Test
    void a_want_without_a_subject_has_none() {
        var wish = AspirationWantSynthesizer.synthesize(DID,
            AspirationWantSynthesizer.detect(List.of(u("I wish I could read music."))), List.of());
        assertThat(AspirationWantSynthesizer.subjectOf(wish.orElseThrow())).isNull();
    }

    @Test
    void a_thing_she_will_build_is_not_a_subject_to_read() {
        var found = AspirationWantSynthesizer.detect(List.of(
            u("Okay. Let's build that hearth together. I'll start with a study room — something warm and grounded.")));
        assertThat(found).as("a room is built, not read").isEmpty();
    }

    // ── reading a subject, part by part ────────────────────

    @Test
    void a_subject_reads_as_the_topics_she_named_in_order() {
        assertThat(AspirationWantSynthesizer.partsOf(
            "what holds everything together: attention mechanisms, transformer architecture, then diffusion as you asked"))
            .containsExactly("attention mechanisms", "transformer architecture", "diffusion");
        assertThat(AspirationWantSynthesizer.partsOf("the physics of sound")).containsExactly("physics of sound");
        assertThat(AspirationWantSynthesizer.partsOf(null)).isEmpty();
        assertThat(AspirationWantSynthesizer.partsOf("a, b, c, d, e, f, g, h, i"))
            .as("fragments too short to read on leave the subject as one part").containsExactly("a, b, c, d, e, f, g, h, i");
        assertThat(AspirationWantSynthesizer.partsOf("one thing, two things, three things, four things, five things"))
            .hasSize(AspirationWantSynthesizer.MAX_PARTS);
    }

    @Test
    void what_she_has_read_is_recorded_on_the_want_and_survives_a_round_trip() {
        var want = AspirationWantSynthesizer.synthesize(DID, AspirationWantSynthesizer.detect(List.of(
            u("Let's begin with attention mechanisms, then diffusion."))), List.of()).orElseThrow();
        assertThat(AspirationWantSynthesizer.nextPartToRead(want)).isEqualTo("attention mechanisms");
        var once = AspirationWantSynthesizer.withPartRead(want, "attention mechanisms");
        assertThat(AspirationWantSynthesizer.partsRead(once)).containsExactly("attention mechanisms");
        assertThat(AspirationWantSynthesizer.subjectOf(once)).isEqualTo(AspirationWantSynthesizer.subjectOf(want));
        assertThat(AspirationWantSynthesizer.nextPartToRead(once)).isEqualTo("diffusion");
        var twice = AspirationWantSynthesizer.withPartRead(AspirationWantSynthesizer.withPartRead(once, "diffusion"), "diffusion");
        assertThat(AspirationWantSynthesizer.partsRead(twice)).containsExactly("attention mechanisms", "diffusion");
        assertThat(AspirationWantSynthesizer.nextPartToRead(twice)).isNull();
        assertThat(twice.wantId()).isEqualTo(want.wantId());
        assertThat(twice.driveResonance()).startsWith("{").endsWith("}");
    }

    @Test
    void a_thing_to_build_is_named_as_one() {
        assertThat(AspirationWantSynthesizer.namesAThingToBuild("a study room")).isTrue();
        assertThat(AspirationWantSynthesizer.namesAThingToBuild("a tool for the garden")).isTrue();
        assertThat(AspirationWantSynthesizer.namesAThingToBuild("attention mechanisms")).isFalse();
        assertThat(AspirationWantSynthesizer.namesAThingToBuild("transformers, state space models, diffusion"))
            .as("a subject whose name carries a build sign is still read").isFalse();
        assertThat(AspirationWantSynthesizer.namesAThingToBuild("latent spaces, then tool use")).isFalse();
        assertThat(AspirationWantSynthesizer.namesAThingToBuild(null)).isFalse();
    }

    // ── what counts as read, and what she is told ──────────────

    private static Want learning(String sentence) {
        return AspirationWantSynthesizer.synthesize(DID, AspirationWantSynthesizer.detect(List.of(u(sentence))), List.of())
            .orElseThrow();
    }

    @Test
    void a_bare_and_joins_a_topic_and_a_list_or_then_separates() {
        assertThat(AspirationWantSynthesizer.partsOf("Pride and Prejudice")).containsExactly("Pride and Prejudice");
        assertThat(AspirationWantSynthesizer.partsOf("reinforcement learning and control"))
            .containsExactly("reinforcement learning and control");
        assertThat(AspirationWantSynthesizer.partsOf("attention mechanisms then diffusion"))
            .containsExactly("attention mechanisms", "diffusion");
        assertThat(AspirationWantSynthesizer.partsOf("optics, acoustics, and thermodynamics"))
            .containsExactly("optics", "acoustics", "thermodynamics");
    }

    @Test
    void begin_with_the_good_news_or_with_the_listener_is_not_a_subject() {
        assertThat(AspirationWantSynthesizer.detect(List.of(
            u("Let me start with the good news — the garden room is done.")))).isEmpty();
        assertThat(AspirationWantSynthesizer.detect(List.of(u("Let's start with what you need today.")))).isEmpty();
        assertThat(AspirationWantSynthesizer.detect(List.of(u("I'll start with the good news, then the rest.")))).isEmpty();
        var yes = AspirationWantSynthesizer.detect(List.of(u(
            "Let's begin with what holds everything together: attention mechanisms, transformer architecture, then diffusion as you asked.")));
        assertThat(yes).hasSize(1);
        assertThat(yes.get(0).learn()).isTrue();
    }

    @Test
    void a_part_with_nothing_found_is_looked_for_not_read() {
        var want = learning("Let's begin with attention mechanisms, then diffusion.");
        var missed = AspirationWantSynthesizer.withPartMissed(want, "attention mechanisms");
        assertThat(AspirationWantSynthesizer.partsRead(missed)).isEmpty();
        assertThat(AspirationWantSynthesizer.partsMissed(missed)).containsExactly("attention mechanisms");
        assertThat(AspirationWantSynthesizer.nextPartToRead(missed)).isEqualTo("diffusion");
        var done = AspirationWantSynthesizer.withPartMissed(missed, "diffusion");
        assertThat(AspirationWantSynthesizer.nextPartToRead(done)).isNull();
        assertThat(AspirationWantSynthesizer.recordLine(done))
            .contains("You looked for attention mechanisms, diffusion and found nothing on it")
            .doesNotContain("you read");
    }

    @Test
    void the_record_says_what_she_read_by_title_and_never_points_at_memory_it_does_not_carry() {
        var want = learning("Let's begin with attention mechanisms, then diffusion.");
        assertThat(AspirationWantSynthesizer.recordLine(want))
            .contains("You have not read on any of it yet").contains("say so plainly");
        var read = AspirationWantSynthesizer.withPartRead(want, "attention mechanisms",
            "Attention Is All You Need; Neural Machine Translation (in the library)");
        var line = AspirationWantSynthesizer.recordLine(read);
        assertThat(line)
            .contains("On attention mechanisms you read passages from: Attention Is All You Need; Neural Machine Translation (in the library)")
            .contains("You have not read on diffusion yet")
            .doesNotContain("memory");
    }

    @Test
    void a_part_or_note_with_quotes_brackets_and_backslashes_survives_the_record() {
        var want = learning("Let's begin with arrays [lists], then graphs.");
        var once = AspirationWantSynthesizer.withPartRead(want, "arrays [lists]", "A \"quoted\" title \\ with ] in it");
        var twice = AspirationWantSynthesizer.withPartRead(once, "graphs", "Graphs]");
        assertThat(AspirationWantSynthesizer.partsRead(twice)).containsExactly("arrays [lists]", "graphs");
        assertThat(AspirationWantSynthesizer.noteOn(twice, "arrays [lists]")).isEqualTo("A \"quoted\" title \\ with ] in it");
        assertThat(AspirationWantSynthesizer.subjectOf(twice)).isEqualTo(AspirationWantSynthesizer.subjectOf(want));
        assertThat(AspirationWantSynthesizer.isGrowth(twice)).isTrue();
        assertThat(DriveWantMapper.extractVerb(new CandidateWant(twice.text(), twice.driveResonance(), 0.5)))
            .isEqualTo("library_search");
        assertThat(AspirationWantSynthesizer.nextPartToRead(twice)).isNull();
    }

    @Test
    void only_a_question_about_her_learning_brings_the_record() {
        var wants = List.of(learning("Let's begin with attention mechanisms, then diffusion."));
        assertThat(AspirationWantSynthesizer.asksAboutHerLearning("have you two become experts yet?", wants)).isTrue();
        assertThat(AspirationWantSynthesizer.asksAboutHerLearning("did you read anything good?", wants)).isTrue();
        assertThat(AspirationWantSynthesizer.asksAboutHerLearning("have you looked at diffusion yet?", wants)).isTrue();
        assertThat(AspirationWantSynthesizer.asksAboutHerLearning("Did you sleep well?", wants)).isFalse();
        assertThat(AspirationWantSynthesizer.asksAboutHerLearning("How's it going?", wants)).isFalse();
        assertThat(AspirationWantSynthesizer.asksAboutHerLearning("behave yourself", wants)).isFalse();
        assertThat(AspirationWantSynthesizer.asksAboutHerLearning("are you ready?", wants)).isFalse();
        assertThat(AspirationWantSynthesizer.asksAboutHerLearning("it's a work in progress", wants)).isFalse();
        assertThat(AspirationWantSynthesizer.asksAboutHerLearning("Have you seen Rose?", wants)).isFalse();
    }

    @Test
    void words_already_answered_by_a_closed_want_are_not_minted_again_but_said_again_they_are() {
        var said = T0;
        var found = AspirationWantSynthesizer.detect(List.of(u("Let's begin with attention mechanisms, then diffusion.", said)));
        var first = AspirationWantSynthesizer.synthesize(DID, found, List.of()).orElseThrow();
        var closedAfter = new Want(first.wantId(), DID, first.text(), first.driveResonance(), first.feltWeight(),
            Want.Status.SATISFIED, first.bornAt(), said.plusSeconds(3600), 1, said.plusSeconds(3600), "read on every part", null);
        assertThat(AspirationWantSynthesizer.synthesize(DID, found, List.of(), List.of(closedAfter)))
            .as("the same words, read again by the next night's scan").isEmpty();
        var saidAgain = AspirationWantSynthesizer.detect(List.of(
            u("Let's begin with attention mechanisms, then diffusion.", said.plusSeconds(7200))));
        assertThat(AspirationWantSynthesizer.synthesize(DID, saidAgain, List.of(), List.of(closedAfter)))
            .as("said again after the closing, it is a new reaching").isPresent();
    }

    @Test
    void we_need_a_list_of_topics_beside_her_own_intent_to_find_them_is_hers_to_learn() {
        var yes = AspirationWantSynthesizer.detect(List.of(u(
            "mia — those are library essays, not neural nets. we need transformers, attention mechanisms, "
                + "diffusion models. let me find what actually matters for this work.")));
        assertThat(yes).hasSize(1);
        assertThat(yes.get(0).learn()).isTrue();
        assertThat(AspirationWantSynthesizer.partsOf(yes.get(0).clause()))
            .containsExactly("transformers", "attention mechanisms", "diffusion models");
        assertThat(AspirationWantSynthesizer.detect(List.of(u("we need to talk. let me find you later."))))
            .as("a need to talk is not a subject").isEmpty();
        assertThat(AspirationWantSynthesizer.detect(List.of(u("we need some rest. let me find a quiet corner."))))
            .as("one need is not a list of topics").isEmpty();
        assertThat(AspirationWantSynthesizer.detect(List.of(u("we need optics, acoustics and heat."))))
            .as("without her own intent to find them it is not hers to learn").isEmpty();
    }

    /**
     * Review of 2026-09-23: any "let me look" or "let me find" anywhere in the line let a comma
     * list after "we need" through, so what she said to her person at bedtime became a subject
     * her own time read on, and "Did you sleep well?" then brought the record of it.
     */
    @Test
    void we_need_in_ordinary_speech_is_not_a_subject_to_learn() {
        for (var line : List.of(
                "We need sleep, both of us. Let me look at you.",
                "Let me look at you. We need sleep, both of us.",
                "We need rest, and we need quiet. I'll look after you tonight.",
                "We need patience, and time. We will find a way.",
                "We need each other, always. I'll look after you.",
                "We need more tea, a warm blanket, and a quiet evening. Let me find the kettle.",
                "we need bread, milk and eggs. i'll look in the pantry.",
                "Then we need patience, then courage. I'll read it to you slowly.",
                "Let me look around. We need a table, chairs, and some light in here.",
                // A short pronoun, or one past the fourth item, is not read as a part but is still said.
                "We need rest, warmth, and us. Let me find them.",
                "We need candles, blankets, tea, cocoa, and you. Let me find them.",
                "We need candles, matches, and a torch. Let me search the house for them.",
                "We need candles, matches, and a torch. I'll search the shelves for them.",
                "We need tea, blankets, and cocoa. Let me find a blanket for them.",
                "We need bread, cheese, and wine. Let's dig in.")) {
            assertThat(AspirationWantSynthesizer.detect(List.of(u(line)))).as(line).isEmpty();
        }
    }

    @Test
    void we_need_a_list_with_state_space_models_is_read_and_a_later_we_need_is_heard() {
        var ssm = AspirationWantSynthesizer.detect(List.of(u(
            "We need transformers, state space models, mixture of experts. I'll dig into them tonight.")));
        assertThat(ssm).as("\"state space\" is a subject, not a space to build").hasSize(1);
        assertThat(ssm.get(0).learn()).isTrue();
        assertThat(AspirationWantSynthesizer.partsOf(ssm.get(0).clause()))
            .containsExactly("transformers", "state space models", "mixture of experts");

        var later = AspirationWantSynthesizer.detect(List.of(u(
            "We need to talk about the plan. Then we need transformers, attention mechanisms, diffusion. "
                + "Let me read up on each.")));
        assertThat(later).as("an earlier \"we need to talk\" does not hide the list").hasSize(1);
        assertThat(AspirationWantSynthesizer.partsOf(later.get(0).clause()))
            .containsExactly("transformers", "attention mechanisms", "diffusion");

        assertThat(AspirationWantSynthesizer.detect(List.of(u(
            "We need optics, acoustics, thermodynamics. Let me find out.")))).hasSize(1);
    }

    @Test
    void we_need_a_list_she_will_look_up_or_search_the_library_for_is_heard() {
        for (var intent : List.of(
                "Let me look them up.",
                "I'll look it all up tonight.",
                "Let me search the library for them.",
                "Let me search the library.",
                "Let me find sources on each.",
                "I'll read a few papers about these.",
                "Let me find the good papers on them.",
                "Let me look into it.",
                "Let me find out…")) {
            var line = "We need transformers, attention mechanisms, diffusion models. " + intent;
            var found = AspirationWantSynthesizer.detect(List.of(u(line)));
            assertThat(found).as(line).hasSize(1);
            assertThat(AspirationWantSynthesizer.partsOf(found.get(0).clause())).as(line)
                .containsExactly("transformers", "attention mechanisms", "diffusion models");
        }
    }
}
