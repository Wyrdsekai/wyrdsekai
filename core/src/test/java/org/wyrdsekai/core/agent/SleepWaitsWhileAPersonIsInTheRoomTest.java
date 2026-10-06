package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * On the household node a companion's sleep backlog reached its target mid-conversation, and the
 * 30 s grace took her home while he was reading her answer (2026-10-04). Sleep from pressure now
 * waits while a person is in her room, until they leave or ten quiet minutes pass. Exhaustion is
 * the exception.
 */
class SleepWaitsWhileAPersonIsInTheRoomTest {

    private static final Duration GRACE = Duration.ofSeconds(30);
    private static final Duration WAIT = Duration.ofMinutes(10);
    private static final Duration S = Duration.ofSeconds(1);

    @Test
    void pressure_waits_while_a_person_is_present_and_goes_when_they_leave_or_fall_quiet() {
        // Pressured, idle, 40 s since anything happened: the old rule would sleep now.
        assertThat(CompanionActor.sleepMayStart(false, true, true, S.multipliedBy(40), GRACE, true, S.multipliedBy(40), WAIT))
            .as("a person is here and spoke 40 s ago: she stays").isFalse();
        assertThat(CompanionActor.sleepMayStart(false, true, true, S.multipliedBy(40), GRACE, false, S.multipliedBy(40), WAIT))
            .as("the person left: the grace alone decides").isTrue();
        assertThat(CompanionActor.sleepMayStart(false, true, true, Duration.ofMinutes(11), GRACE, true, Duration.ofMinutes(10), WAIT))
            .as("the person has been quiet ten minutes: she may sleep with them there").isTrue();
        assertThat(CompanionActor.sleepMayStart(false, true, true, S.multipliedBy(10), GRACE, false, Duration.ofHours(1), WAIT))
            .as("the empty-room grace still holds").isFalse();
    }

    @Test
    void exhaustion_waits_only_for_the_grace_and_nothing_sleeps_without_a_reason_or_mid_thought() {
        assertThat(CompanionActor.sleepMayStart(true, false, true, S.multipliedBy(31), GRACE, true, S.multipliedBy(5), WAIT))
            .as("exhausted: she sleeps after the grace even with a person there").isTrue();
        assertThat(CompanionActor.sleepMayStart(true, false, true, S.multipliedBy(10), GRACE, true, S.multipliedBy(5), WAIT))
            .as("exhausted but inside the grace").isFalse();
        assertThat(CompanionActor.sleepMayStart(false, false, true, Duration.ofHours(1), GRACE, false, Duration.ofHours(1), WAIT))
            .as("neither exhausted nor pressured").isFalse();
        assertThat(CompanionActor.sleepMayStart(false, true, false, Duration.ofHours(1), GRACE, false, Duration.ofHours(1), WAIT))
            .as("mid-thought").isFalse();
    }

    @Test
    void the_tick_uses_the_gate_and_a_person_s_events_are_what_quiet_is_measured_from() throws Exception {
        var base = Files.exists(Path.of("..", "core")) ? Path.of("..") : Path.of(".");
        var src = Files.readString(base.resolve("core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));
        int check = src.indexOf("boolean exhausted = vitality.energy() < SLEEP_ENERGY_THRESHOLD;");
        int gate = src.indexOf("sleepMayStart(exhausted, pressured,", check);
        int start = src.indexOf("initiateSleep();", check);
        assertThat(gate).isGreaterThan(check).isLessThan(start);
        assertThat(src).contains("private static final Duration SLEEP_WAIT_WITH_PERSON = Duration.ofMinutes(10);");
        int person = src.indexOf("if (actor != null && isPersonEntityId(actor)) {");
        assertThat(person).isPositive();
        assertThat(src.substring(person, person + 400)).contains("lastPersonEventTime = lastEventTime;")
            .contains("personsHere.remove(actor); else personsHere.add(actor);");
        // The room's narrator speaks when someone arrives or leaves; it is not a person in the room
        // (the live test found the narrator's "X leaves" line keeping her up after X had gone).
        assertThat(src).contains("|| id.equals(\"system\") || id.equals(\"narrator\")) return false;");
        // The household's night already leaves a companion in company awake.
        assertThat(src).contains("stays up: someone is with her, so the household's night goes without her");
    }
}
