package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Human recently active" is held by a person's line, not by anything that happens in her room.
 *
 * <h2>What went wrong</h2>
 * Household node, 2026-09-23: the proactivity judgment held her impulses as "human recently
 * active" 19 times in the day, 12 of them right after her own line. The vitality tick handed the
 * judgment {@code lastEventTime} in the person slot. That clock is stamped for every room event
 * before the own-speech filter, and the room delivers every event to every subscriber, the
 * speaker included, so her own speech, the other companion's, the clock's ambient changes and a
 * room being made all read as a person who had just spoken. The person clock
 * ({@code lastPersonSpokeToMeAt}) was already there.
 */
class HerOwnLineIsNotAPersonSpeakingTest {

    private static String actorSource() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Path.of("..", rel);
        return Files.readString(Files.exists(fromCore) ? fromCore : Path.of(rel));
    }

    /** Affiliation is not vigilance, so a person's recent line holds it. */
    private static ProactivityJudgment.Context pressed(Instant lastPersonSpoke) {
        return new ProactivityJudgment.Context(
            DriveState.initial().spikeAffiliation(0.8), VitalityState.initial(),
            DecisionCapacity.newAgent(), null, 3.0,
            null, lastPersonSpoke, "companion-mia", 2);
    }

    @Test
    @DisplayName("the judgment is handed the person clock, not the room's any-event clock")
    void theHoldReadsThePersonClock() throws Exception {
        var src = actorSource();
        var at = src.indexOf("new ProactivityJudgment.Context(");
        assertThat(at).as("the vitality tick builds the judgment's context").isGreaterThan(-1);
        var call = src.substring(at, src.indexOf(");", at));
        assertThat(call).contains("lastProactiveAction, laterOf(lastPersonSpokeToMeAt, lastAnsweredPersonAt),");
        assertThat(call).doesNotContain("lastEventTime");
    }

    /**
     * Her answer holds her too: the person's turn comes after it, however long the answer took.
     * Without this, an answer that landed more than 10 s after the person spoke left nothing to
     * hold the next own-time impulse (review of 2026-09-23).
     */
    @Test
    @DisplayName("after she answers a person, her own impulse waits for their turn")
    void herAnswerToAPersonHoldsHerToo() throws Exception {
        var personSpoke = Instant.now().minusSeconds(20);
        var sheAnswered = Instant.now().minusSeconds(1);
        assertThat(ProactivityJudgment.evaluate(pressed(CompanionActor.laterOf(personSpoke, sheAnswered))))
            .isInstanceOfSatisfying(ProactivityJudgment.JudgmentResult.Hold.class,
                hold -> assertThat(hold.reason()).isEqualTo("human recently active"));
        assertThat(CompanionActor.laterOf(null, sheAnswered)).isEqualTo(sheAnswered);
        assertThat(CompanionActor.laterOf(personSpoke, null)).isEqualTo(personSpoke);

        var src = actorSource();
        var stamp = src.indexOf("if (answering != null && isHumanTrigger(answering)) lastAnsweredPersonAt = Instant.now();");
        assertThat(stamp).as("stamped where a line that answers a person goes out").isGreaterThan(-1);
        assertThat(src.indexOf("roomRef.tell(new RoomCommand.SayInRoom(", stamp) - stamp).isLessThan(200);
        assertThat(src).contains("if (answersAPerson()) lastAnsweredPersonAt = Instant.now();");
    }

    @Test
    @DisplayName("only a person's line moves the person clock")
    void onlyAPersonMovesThePersonClock() throws Exception {
        var src = actorSource();
        long writers = src.lines()
            .filter(l -> l.trim().startsWith("lastPersonSpokeToMeAt ="))
            .count();
        assertThat(writers).as("one writer").isEqualTo(1);
        var at = src.indexOf("lastPersonSpokeToMeAt = Instant.now();");
        assertThat(src.substring(Math.max(0, at - 400), at))
            .as("stamped inside the person check, which her own id, another companion and "
                + "the system never pass")
            .contains("if (isHumanTrigger(said)) {");
    }

    @Test
    @DisplayName("no person has spoken: nothing is held as 'human recently active'")
    void noPersonNoHold() {
        assertThat(ProactivityJudgment.evaluate(pressed(null)))
            .isInstanceOf(ProactivityJudgment.JudgmentResult.Act.class);
    }

    @Test
    @DisplayName("a person who spoke a moment ago still holds her impulse")
    void aPersonWhoJustSpokeStillHolds() {
        assertThat(ProactivityJudgment.evaluate(pressed(Instant.now().minusSeconds(3))))
            .isInstanceOfSatisfying(ProactivityJudgment.JudgmentResult.Hold.class,
                hold -> assertThat(hold.reason()).isEqualTo("human recently active"));
    }

    @Test
    @DisplayName("a held line still waits for a quiet room, her own reply included")
    void aHeldLineWaitsForAQuietRoom() throws Exception {
        // The surface reads the room clock on purpose: after she answers a person, the held
        // line waits for them to have their turn instead of following her answer at once.
        var src = actorSource();
        var body = src.substring(src.indexOf("private DeferredAction surfaceDeferredAction"));
        body = body.substring(0, body.indexOf("\n    }"));
        assertThat(body).contains("Duration.between(lastEventTime, Instant.now())");
    }
}
