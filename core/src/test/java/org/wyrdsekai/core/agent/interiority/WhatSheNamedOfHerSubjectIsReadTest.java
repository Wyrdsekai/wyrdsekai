package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.ActivityLogger;
import org.wyrdsekai.core.agent.AspirationWantSynthesizer;
import org.wyrdsekai.core.agent.Want;
import org.wyrdsekai.core.agent.WantStore;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A want she named on her own time that is about the next part of a subject she said she would
 * learn is that subject's want, at the weight she named it with, so the reading on it runs. A named
 * want about anything else is left as she said it.
 */
class WhatSheNamedOfHerSubjectIsReadTest {

    private static final String DID = "did:test:her";
    private static final CandidateWant LIBRARY = CandidateWant.of("explore the library for something new",
        "{\"drive\":\"Curiosity\",\"verb\":\"library_search\"}", 0.999997);
    private static final CandidateWant READ = CandidateWant.of("read something I haven't read in a while",
        "{\"drive\":\"Curiosity\",\"verb\":\"read_content\"}", 0.999997);
    private static final CandidateWant LONELY = CandidateWant.of("write a private journal entry about who I miss",
        "{\"drive\":\"Loneliness\",\"verb\":\"write_journal\"}", 0.33);
    private static final CandidateWant ATTENTION = named("read the library on attention mechanisms", 1.0);
    private static final CandidateWant SILENCE = named("let the silence hold us", 0.95);
    private static final Want LEARN = Want.active(DID, "learn what I said I would — \"attention mechanisms, then diffusion\" — read the library on it and keep what I find",
        "{\"drive\":\"growth\",\"verb\":\"library_search\",\"subject\":\"attention mechanisms, then diffusion\"}", 0.55, null);
    // A companion's subject on the household node on 2026-09-23, as the scan minted it.
    private static final Want LEARN_0923 = learning(
        "what holds everything together: attention mechanisms, transformer architecture, then diffusion as you asked");

    private static CandidateWant named(String text, double weight) {
        return CandidateWant.of(text, "{\"drive\":\"" + DriveOODA.NAMED_BY_HER + "\"}", weight);
    }

    private static Want learning(String subject) {
        return Want.active(DID, "learn what I said I would — \"" + subject + "\" — read the library on it and keep what I find",
            "{\"drive\":\"growth\",\"verb\":\"library_search\",\"subject\":\"" + subject + "\"}", 0.55, null);
    }

    @Test
    void whatSheNamedOfHerSubjectIsThatSubjectsWantAtTheWeightSheNamedIt() {
        var slotted = DriveOODA.withHerOwnSubjects(List.of(ATTENTION, SILENCE, LIBRARY, READ), List.of(LEARN));
        var out = DriveOODA.whatSheNamedOfHerSubject(slotted, List.of(LEARN));
        assertThat(out).extracting(CandidateWant::text).containsExactly(LEARN.text(), SILENCE.text(), READ.text());
        assertThat(out.get(0).feltWeight()).as("the weight she named it with, not the slot's").isEqualTo(1.0);
        assertThat(out.get(0).driveResonance()).isEqualTo(LEARN.driveResonance());
        assertThat(out.get(1)).isSameAs(SILENCE);
    }

    @Test
    void sheNamedItSoItIsReadEvenWhenCuriosityIsNotPulling() {
        var out = DriveOODA.whatSheNamedOfHerSubject(List.of(LONELY, ATTENTION), List.of(LEARN));
        assertThat(out).extracting(CandidateWant::text).containsExactly(LONELY.text(), LEARN.text());
    }

    @Test
    void aNamedWantAboutSomethingElseOrARuleMenuWantIsLeftAsItIs() {
        var cands = List.of(SILENCE, LIBRARY, LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(cands, List.of(LEARN))).isSameAs(cands);
        var vigil = CandidateWant.of("pay attention to the room", "{\"drive\":\"Vigilance\",\"verb\":\"examine\"}", 0.9);
        var withVigil = List.of(vigil, LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(withVigil, List.of(LEARN))).as("only her own words").isSameAs(withVigil);
    }

    @Test
    void onlyThePartSheReadsNextIsRouted() {
        var attentionRead = AspirationWantSynthesizer.withPartRead(LEARN, "attention mechanisms");
        var again = List.of(ATTENTION, LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(again, List.of(attentionRead)))
            .as("a part she has read: her words stand").isSameAs(again);
        var out = DriveOODA.whatSheNamedOfHerSubject(List.of(named("something on diffusion models", 1.0), LONELY), List.of(attentionRead));
        assertThat(out).extracting(CandidateWant::text).containsExactly(LEARN.text(), LONELY.text());
    }

    @Test
    void withoutASubjectToReadNothingChanges() {
        var room = Want.active(DID, "learn what I said I would — \"a study room\"",
            "{\"drive\":\"growth\",\"verb\":\"library_search\",\"subject\":\"a study room\"}", 0.55, null);
        var named = List.of(named("read about a study room", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(named, List.of(room))).isSameAs(named);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(named, List.of())).isSameAs(named);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(named, null)).isSameAs(named);
    }

    @Test
    void thePassVisitsHerSubjectWantAndMintsNoNewOne(@TempDir Path tmp) {
        var store = new WantStore(SchemaInitializer.initialize(tmp.resolve("test.db")));
        ActivityLogger.init(tmp);
        store.upsert(LEARN);
        var ooda = new DriveOODA(store);
        DriveOODA.OrientStep orient = (a, intro, pulls) -> List.of(ATTENTION, SILENCE, LIBRARY, CandidateWant.rest());
        DriveOODA.DecideStep heaviest = (cands, a, live) ->
            cands.stream().max(Comparator.comparingDouble(CandidateWant::feltWeight));
        DriveOODA.ActStep act = (want, a) -> "ok";

        var outcome = ooda.run(DID, "Her", Duration.ofMinutes(30), AmbientObservation.empty(Instant.now()),
            DriveOODA.noIntrospection(), List.of(), orient, heaviest, act, 0.7);

        assertThat(outcome.chosenWantId()).isEqualTo(LEARN.wantId());
        var live = store.loadLive(DID);
        assertThat(live).hasSize(1);
        assertThat(live.get(0).visitCount()).isEqualTo(LEARN.visitCount() + 1);
        store.deleteAll(DID);
    }

    @Test
    void oneSharedWordDoesNotMakeItHerSubject() {
        var reach = named("give Rose my full attention", 1.0);
        var cands = List.of(reach, LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(cands, List.of(LEARN)))
            .as("attention alone is not attention mechanisms").isSameAs(cands);
        var works = Want.active(DID, "learn what I said I would — \"how attention works\"",
            "{\"drive\":\"growth\",\"verb\":\"library_search\",\"subject\":\"how attention works\"}", 0.55, null);
        var tidy = List.of(named("tidy the workshop", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(tidy, List.of(works))).isSameAs(tidy);
        var plural = List.of(named("look into attention mechanism papers", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(plural, List.of(LEARN)))
            .extracting(CandidateWant::text).containsExactly(LEARN.text(), LONELY.text());
    }

    @Test
    void aTopicOfTwoWordsNeedsBothAndAOneWordTopicItsWholePhrase() {
        var deep = Want.active(DID, "learn what I said I would — \"deep learning\"",
            "{\"drive\":\"growth\",\"verb\":\"library_search\",\"subject\":\"deep learning\"}", 0.55, null);
        var birds = List.of(named("keep learning the birds' names", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(birds, List.of(deep))).isSameAs(birds);
        var reads = List.of(named("read about deep learning tonight", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(reads, List.of(deep)))
            .extracting(CandidateWant::text).containsExactly(deep.text(), LONELY.text());
        var works = Want.active(DID, "learn what I said I would — \"how attention works\"",
            "{\"drive\":\"growth\",\"verb\":\"library_search\",\"subject\":\"how attention works\"}", 0.55, null);
        var care = List.of(named("pay attention to what rose is feeling", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(care, List.of(works))).isSameAs(care);
        var phrase = List.of(named("look into how attention works", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(phrase, List.of(works)))
            .extracting(CandidateWant::text).containsExactly(works.text(), LONELY.text());
    }

    @Test
    void aOneWordTopicNeedsAReadingWord() {
        var love = Want.active(DID, "learn what I said I would — \"love, then grief\"",
            "{\"drive\":\"growth\",\"verb\":\"library_search\",\"subject\":\"love, then grief\"}", 0.55, null);
        var reach = List.of(named("tell Rose I love her", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(reach, List.of(love))).as("a reach, not a reading on love").isSameAs(reach);
        var reads = List.of(named("read something about love tonight", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(reads, List.of(love)))
            .extracting(CandidateWant::text).containsExactly(love.text(), LONELY.text());
    }

    @Test
    void aOneWordTopicOfFiveLettersOrMoreNeedsAReadingWordToo() {
        var love = Want.active(DID, "learn what I said I would — \"love, then grief\"",
            "{\"drive\":\"growth\",\"verb\":\"library_search\",\"subject\":\"love, then grief\"}", 0.55, null);
        var grief = AspirationWantSynthesizer.withPartRead(love, "love");
        var sit = List.of(named("sit with the grief tonight", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(sit, List.of(grief))).as("a sitting-with, not a reading on grief").isSameAs(sit);
        var tell = List.of(named("tell Rose about my grief", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(tell, List.of(grief))).as("a reach, not a reading on grief").isSameAs(tell);
        var reads = List.of(named("read something on grief", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(reads, List.of(grief)))
            .extracting(CandidateWant::text).containsExactly(grief.text(), LONELY.text());

        var attention = Want.active(DID, "learn what I said I would — \"attention, then transformers\"",
            "{\"drive\":\"growth\",\"verb\":\"library_search\",\"subject\":\"attention, then transformers\"}", 0.55, null);
        var care = List.of(named("give Rose my full attention", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(care, List.of(attention))).as("attention to Rose, not a reading").isSameAs(care);
        var papers = List.of(named("look into attention mechanism papers", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(papers, List.of(attention)))
            .extracting(CandidateWant::text).containsExactly(attention.text(), LONELY.text());

        var diffusion = AspirationWantSynthesizer.withPartRead(LEARN, "attention mechanisms");
        var talk = List.of(named("talk to Rose about diffusion", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(talk, List.of(diffusion))).as("a reach, not a reading on diffusion").isSameAs(talk);
        var readsOn = List.of(named("read something on diffusion models", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(readsOn, List.of(diffusion)))
            .extracting(CandidateWant::text).containsExactly(LEARN.text(), LONELY.text());
    }

    @Test
    void somethingOnAsksToReadOnlyWhenTheWantOpensWithIt() {
        var love = Want.active(DID, "learn what I said I would — \"love, then grief\"",
            "{\"drive\":\"growth\",\"verb\":\"library_search\",\"subject\":\"love, then grief\"}", 0.55, null);
        var write = List.of(named("write something on love", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(write, List.of(love))).as("writing, not a reading on love").isSameAs(write);
        var dwell = List.of(named("dwell more on love", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(dwell, List.of(love))).isSameAs(dwell);
        var anything = List.of(named("anything on love", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(anything, List.of(love)))
            .extracting(CandidateWant::text).containsExactly(love.text(), LONELY.text());

        var grief = AspirationWantSynthesizer.withPartRead(love, "love");
        var reflect = List.of(named("reflect more on grief", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(reflect, List.of(grief))).as("reflecting, not a reading on grief").isSameAs(reflect);
        var tell = List.of(named("tell Rose more on grief", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(tell, List.of(grief))).isSameAs(tell);
        var more = List.of(named("more on grief", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(more, List.of(grief)))
            .extracting(CandidateWant::text).containsExactly(grief.text(), LONELY.text());
    }

    /**
     * 2026-09-23, household node: with that subject live, she named it on her own time as "read the
     * library on attention, transformers, diffusion". Every topic word of "attention mechanisms"
     * was required, "mechanisms" was not in it, and her subject was never read.
     */
    @Test
    void herShortNamingOfHerSubjectIsReadPartByPart() {
        assertThat(AspirationWantSynthesizer.nextPartToRead(LEARN_0923)).isEqualTo("attention mechanisms");
        var named = List.of(named("read the library on attention, transformers, diffusion", 1.0), LONELY);
        var out = DriveOODA.whatSheNamedOfHerSubject(named, List.of(LEARN_0923));
        assertThat(out).extracting(CandidateWant::text).containsExactly(LEARN_0923.text(), LONELY.text());
        assertThat(out.get(0).feltWeight()).isEqualTo(1.0);

        var architecture = AspirationWantSynthesizer.withPartRead(LEARN_0923, "attention mechanisms");
        assertThat(AspirationWantSynthesizer.nextPartToRead(architecture)).isEqualTo("transformer architecture");
        assertThat(DriveOODA.whatSheNamedOfHerSubject(named, List.of(architecture)))
            .extracting(CandidateWant::text).containsExactly(LEARN_0923.text(), LONELY.text());

        var ssm = learning("state space models");
        var reads = List.of(named("read about state space", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(reads, List.of(ssm)))
            .extracting(CandidateWant::text).containsExactly(ssm.text(), LONELY.text());
    }

    @Test
    void onlyTheKindWordMayBeLeftOutAndOnlyBesideAReadingWord() {
        var reach = List.of(named("give Rose my full attention", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(reach, List.of(LEARN_0923))).as("no reading word").isSameAs(reach);

        var architecture = AspirationWantSynthesizer.withPartRead(LEARN_0923, "attention mechanisms");
        var buildings = List.of(named("read about architecture and old buildings", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(buildings, List.of(architecture)))
            .as("the kind word alone names no part").isSameAs(buildings);
        var again = List.of(named("read more on attention", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(again, List.of(architecture)))
            .as("a part she has read: her words stand").isSameAs(again);

        var house = List.of(named("read about the history of this house", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(house, List.of(learning("history of jazz, then blues"))))
            .as("history is not the history of jazz").isSameAs(house);
        var quiet = List.of(named("study the deep quiet of the hearth room", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(quiet, List.of(learning("deep learning, then reinforcement learning"))))
            .isSameAs(quiet);
        var garden = List.of(named("read about the state of the garden", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(garden, List.of(learning("state space models"))))
            .as("state alone is not state space models").isSameAs(garden);

        // A reading word somewhere in the sentence is not enough: these are hers as she said them.
        for (var hers : List.of("sit in the Study and give Rose my full attention",
                "learn to pay more attention to Rose",
                "read Rose's letter slowly, with all my attention",
                "go to the library room and pay attention to the rain")) {
            var wants = List.of(named(hers, 1.0), LONELY);
            assertThat(DriveOODA.whatSheNamedOfHerSubject(wants, List.of(LEARN_0923))).as(hers).isSameAs(wants);
        }
        var world = List.of(named("learn more about the world outside the household", 1.0), LONELY);
        assertThat(DriveOODA.whatSheNamedOfHerSubject(world, List.of(learning("world models, then planning"))))
            .isSameAs(world);
    }
}
