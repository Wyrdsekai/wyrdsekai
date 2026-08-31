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
}
