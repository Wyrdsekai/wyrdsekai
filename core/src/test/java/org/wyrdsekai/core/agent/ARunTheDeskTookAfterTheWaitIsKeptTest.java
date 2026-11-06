package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.library.LibraryAsks;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A hand-off that waited 30 seconds for the desk was told "did not take", and the desk took it anyway:
 * the run went on with no one to hand its answer to (household node, 2026-11-05). A run that was
 * stopped, to put it under the household's time limit, is asked again once, not lost.
 */
class ARunTheDeskTookAfterTheWaitIsKeptTest {

    private static final String LISTED = """
        {"paused":false,"active":[
          {"job_id":"J-0062","kind":"research","state":"running","queued_at":"2026-11-05T19:50:20Z"},
          {"job_id":"J-0063","kind":"research","state":"queued","queued_at":"2026-11-05T21:37:22Z"}],
         "finished":[{"job_id":"J-0060","kind":"research","state":"done","queued_at":"2026-11-05T16:08:57Z"}]}
        """;

    @Test
    void the_run_queued_since_the_hand_off_and_held_by_no_question_is_found() {
        var sent = Instant.parse("2026-11-05T19:50:00Z");
        assertThat(CompanionActor.runQueuedBetween(LISTED, sent.minusSeconds(5), sent.plusSeconds(120), id -> false)).isEqualTo("J-0062");
        assertThat(CompanionActor.runQueuedBetween(LISTED, sent.minusSeconds(5), sent.plusSeconds(120), Set.of("J-0062")::contains))
            .as("a run a question already holds is not taken").isNull();
        assertThat(CompanionActor.runQueuedBetween(LISTED, Instant.parse("2026-11-05T22:00:00Z"), Instant.parse("2026-11-05T22:05:00Z"), id -> false))
            .isNull();
        assertThat(CompanionActor.runQueuedBetween("not json", sent, sent, id -> false)).isNull();
    }

    @Test
    void a_stopped_run_is_read_as_stopped_and_asked_again_once(@TempDir Path dir) throws Exception {
        var answer = CompanionActor.deskAnswerOf("J-0063", "{\"job\":{\"job_id\":\"J-0063\",\"state\":\"stopped\"}}");
        assertThat(answer).isNotNull();
        assertThat(answer.stopped()).isTrue();
        assertThat(answer.failed()).isFalse();

        var url = "jdbc:sqlite:" + dir.resolve("world.db");
        try (var conn = DriverManager.getConnection(url)) { LibraryAsks.ensure(conn); }
        var asks = new LibraryAsks(url);
        asks.deskAsked("did:key:mia", "architecture history", "J-0063");
        assertThat(asks.deskAsk("J-0063").orElseThrow().answered())
            .as("a question not answered reads back as not answered — it read as answered at 0, and every answer was dropped").isFalse();
        assertThat(asks.deskPending("did:key:mia")).singleElement().satisfies(a -> assertThat(a.answered()).isFalse());
        assertThat(asks.deskAskedAgain("J-0063")).isFalse();
        asks.deskAskedAgain("did:key:mia", "architecture history", "J-0070", "J-0063");
        assertThat(asks.deskAskedAgain("J-0070")).as("a second stop ends it").isTrue();
        assertThat(asks.deskAsk("J-0063").orElseThrow().answered()).as("the stopped run is closed").isTrue();
        assertThat(asks.deskAsk("J-0070").orElseThrow().question()).isEqualTo("architecture history");
    }
}
