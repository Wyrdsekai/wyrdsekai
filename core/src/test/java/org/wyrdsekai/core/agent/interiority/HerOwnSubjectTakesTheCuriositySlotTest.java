package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AspirationWantSynthesizer;
import org.wyrdsekai.core.agent.Want;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When Curiosity pulls toward the library, a subject she said she would learn takes that
 * candidate's place at that candidate's weight. Nothing is added when Curiosity is not pulling,
 * and every other candidate is left as it was.
 */
class HerOwnSubjectTakesTheCuriositySlotTest {

    private static final String DID = "did:test:her";
    private static final CandidateWant LIBRARY = CandidateWant.of("explore the library for something new",
        "{\"drive\":\"Curiosity\",\"verb\":\"library_search\"}", 0.7);
    private static final CandidateWant READ = CandidateWant.of("read something I haven't read in a while",
        "{\"drive\":\"Curiosity\",\"verb\":\"read_content\"}", 0.7);
    private static final CandidateWant LONELY = CandidateWant.of("write a private journal entry about who I miss",
        "{\"drive\":\"Loneliness\",\"verb\":\"write_journal\"}", 0.33);
    private static final Want LEARN = Want.active(DID, "learn what I said I would — \"attention mechanisms, then diffusion\" — read the library on it and keep what I find",
        "{\"drive\":\"growth\",\"verb\":\"library_search\",\"subject\":\"attention mechanisms, then diffusion\"}", 0.55, null);

    @Test
    void herSubjectTakesTheLibraryCandidatesPlaceAndWeight() {
        var out = DriveOODA.withHerOwnSubjects(List.of(LONELY, LIBRARY, READ), List.of(LEARN));
        assertThat(out).extracting(CandidateWant::text).containsExactly(LONELY.text(), LEARN.text(), READ.text());
        assertThat(out.get(1).feltWeight()).as("the Curiosity pull's weight, not her 0.55").isEqualTo(0.7);
        assertThat(out.get(0)).isSameAs(LONELY);
        assertThat(out.get(2)).as("the other Curiosity outlet stays").isSameAs(READ);
    }

    @Test
    void withoutACuriosityPullNothingIsAdded() {
        var cands = List.of(LONELY);
        assertThat(DriveOODA.withHerOwnSubjects(cands, List.of(LEARN))).isSameAs(cands);
        assertThat(DriveOODA.withHerOwnSubjects(List.of(), List.of(LEARN))).isEmpty();
    }

    @Test
    void aSubjectThatNamesAThingToBuildOrIsFullyReadIsNotRead() {
        var room = Want.active(DID, "learn what I said I would — \"a study room\"",
            "{\"drive\":\"growth\",\"verb\":\"library_search\",\"subject\":\"a study room\"}", 0.55, null);
        var done = AspirationWantSynthesizer.withPartRead(AspirationWantSynthesizer.withPartRead(LEARN, "attention mechanisms"), "diffusion");
        var cands = List.of(LIBRARY);
        assertThat(DriveOODA.withHerOwnSubjects(cands, List.of(room))).isSameAs(cands);
        assertThat(DriveOODA.withHerOwnSubjects(cands, List.of(done))).isSameAs(cands);
    }

    @Test
    void withoutASubjectNothingChanges() {
        var wish = Want.active(DID, "grow toward something I said I wished for — \"read music\"",
            "{\"drive\":\"growth\",\"verb\":\"dispatch_task\"}", 0.55, null);
        var cands = List.of(LIBRARY, LONELY);
        assertThat(DriveOODA.withHerOwnSubjects(cands, List.of(wish))).isSameAs(cands);
        assertThat(DriveOODA.withHerOwnSubjects(cands, List.of())).isSameAs(cands);
    }
}
