package org.wyrdsekai.core.library;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.test.TestDb;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The record of asking: what she asked the shelves and the desk, and what came of it. */
class LibraryAsksTest {

    private static final String HER = "did:key:z6MkExampleWren";
    private LibraryAsks asks;

    @BeforeEach
    void setUp() throws Exception {
        var jdbc = TestDb.createInMemory();
        try (var conn = DriverManager.getConnection(jdbc)) {
            LibraryAsks.ensure(conn);
            LibraryAsks.ensure(conn);
        }
        asks = new LibraryAsks(jdbc);
    }

    @Test
    void a_search_is_kept_with_whether_the_shelves_held_anything() {
        asks.record(HER, "tide pool ecology", true, 8);
        asks.record(HER, "moyamoya disease", false, 0);
        asks.record("did:key:z6MkSomeoneElse", "tide pools", true, 3);

        var mine = asks.since(HER, Instant.now().minus(Duration.ofDays(7)));
        assertThat(mine).hasSize(2);
        assertThat(mine.getFirst().query()).as("newest first").isEqualTo("moyamoya disease");
        assertThat(mine.getFirst().found()).isFalse();
        assertThat(mine.get(1).found()).isTrue();
        assertThat(mine.get(1).sources()).isEqualTo(8);
    }

    @Test
    void the_same_subject_in_other_words_is_the_same_question() {
        assertThat(LibraryAsks.sameSubject("tide pool ecology intertidal zone animals", "intertidal zone animals how tide pools work")).isTrue();
        assertThat(LibraryAsks.sameSubject("tide pool ecology", "ecology of the intertidal tide pool")).isTrue();
        assertThat(LibraryAsks.sameSubject("attention in meditation practice", "attention span of children")).as("one shared word").isFalse();
        assertThat(LibraryAsks.sameSubject("tides", "tides")).as("one word is not a subject").isFalse();
        assertThat(LibraryAsks.sameSubject(null, "tides")).isFalse();
    }

    @Test
    void how_often_a_subject_came_back_thin() {
        asks.record(HER, "cross-attention in diffusion models", false, 0);
        asks.record(HER, "diffusion models cross-attention mechanism", false, 0);
        asks.record(HER, "diffusion model U-Net cross-attention architecture", true, 10);
        asks.record(HER, "sourdough starter", true, 4);

        var recent = asks.since(HER, Instant.now().minus(Duration.ofDays(7)));
        var same = LibraryAsks.about("cross-attention in diffusion models", recent);
        assertThat(same).hasSize(3);
        assertThat(LibraryAsks.thin(same)).isEqualTo(2);
        assertThat(LibraryAsks.about("sourdough starter bread", recent)).hasSize(1);
    }

    @Test
    void a_question_to_the_desk_waits_until_answered_and_then_until_opened() {
        asks.deskAsked(HER, "what causes moyamoya disease", "job-1");
        asks.deskAsked(HER, "what causes moyamoya disease", "job-1");

        assertThat(asks.deskPending(HER)).hasSize(1);
        assertThat(asks.deskUnopened(HER)).isEmpty();

        asks.deskAnswered("job-1", "I-0042", "Moyamoya disease: causes and course");

        assertThat(asks.deskPending(HER)).isEmpty();
        var waiting = asks.deskUnopened(HER);
        assertThat(waiting).hasSize(1);
        assertThat(waiting.getFirst().entryId()).isEqualTo("I-0042");
        assertThat(waiting.getFirst().title()).isEqualTo("Moyamoya disease: causes and course");
        assertThat(asks.deskAsk("job-1")).map(LibraryAsks.DeskAsk::answered).contains(true);

        asks.deskAnswerOpened(HER, "I-0042");

        assertThat(asks.deskUnopened(HER)).isEmpty();
        assertThat(asks.deskAsk("job-1")).map(LibraryAsks.DeskAsk::opened).contains(true);
    }

    @Test
    void a_desk_that_gave_up_leaves_nothing_waiting() {
        asks.deskAsked(HER, "a question", "job-2");
        asks.deskGaveUp("job-2");

        assertThat(asks.deskPending(HER)).isEmpty();
        assertThat(asks.deskUnopened(HER)).as("no entry to open").isEmpty();
        assertThat(asks.deskAsk("job-2")).map(LibraryAsks.DeskAsk::answered).contains(true);
    }

    @Test
    void an_answer_is_recorded_once() {
        asks.deskAsked(HER, "a question", "job-3");
        asks.deskAnswered("job-3", "I-1", "first");
        asks.deskAnswered("job-3", "I-2", "second");

        assertThat(asks.deskAsk("job-3")).map(LibraryAsks.DeskAsk::entryId).contains("I-1");
    }

    @Test
    void blank_questions_and_unknown_people_are_not_kept() {
        asks.record(HER, "  ", true, 1);
        asks.record(null, "tides", true, 1);
        asks.deskAsked(HER, "q", " ");

        assertThat(asks.since(HER, Instant.EPOCH)).isEmpty();
        assertThat(asks.deskPending(HER)).isEmpty();
        assertThat(LibraryAsks.about("tides", List.of())).isEmpty();
    }
}
