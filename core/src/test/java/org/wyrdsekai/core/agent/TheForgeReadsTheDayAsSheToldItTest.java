package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dream is not only a journal entry: the night's forge waits for it, briefly, and
 * consolidates from the day as she told it beside the day's parts. Runtime wiring in the actor,
 * asserted on the source the way the other actor wiring tests do; the dream itself has its own
 * pure test.
 */
class TheForgeReadsTheDayAsSheToldItTest {

    private static final Path SRC = Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    private static String body(String src, String head, String nextMarker) {
        int start = src.indexOf(head);
        assertThat(start).as(head).isGreaterThan(0);
        int end = src.indexOf(nextMarker, start + head.length());
        return src.substring(start, end > 0 ? end : src.length());
    }

    @Test
    @DisplayName("sleep waits for the dream, and never past its timeout")
    void sleepWaitsForTheDream() throws Exception {
        var src = Files.readString(SRC);
        var initiate = body(src, "dreamForThisSleep = null;", "\n    private ");
        assertThat(initiate)
            .contains("if (dreamTheDay()) {")
            .contains("sleepCycleWaitingForDream = true;")
            .contains("timers.startSingleTimer(DREAM_WAIT_TIMER, new DreamWaitOver()")
            .contains("} else {\n            executeSleepCycle();");
        var landed = body(src, "private Behavior<Command> onDreamLanded(", "\n    private void proposeGuardQuestion");
        assertThat(landed).as("the landed dream releases the forge")
            .contains("dreamForThisSleep = msg.text();")
            .contains("timers.cancel(DREAM_WAIT_TIMER);")
            .contains("executeSleepCycle();");
        var dream = body(src, "private boolean dreamTheDay() {", "\n    private Behavior<Command> onDreamLanded(");
        assertThat(dream).as("no dream also releases the forge")
            .contains("self.tell(new DreamWaitOver());");
    }

    @Test
    @DisplayName("the forge's input is the day's parts plus her telling of it, never her live event list")
    void theForgeReadsTheDream() throws Exception {
        var src = Files.readString(SRC);
        var cycle = body(src, "private void executeSleepCycle() {", "\n    private ");
        assertThat(cycle)
            .contains("var dayEvents = new ArrayList<WorldEvent>(eventsSinceLastSleep);")
            .contains("dayEvents.add(new WorldEvent.Said(")
            .contains("dreamForThisSleep, locale, List.of()")
            .contains("var saidEvents = dayEvents.stream()")
            .contains("var eventsCopy = List.copyOf(dayEvents);")
            .doesNotContain("eventsSinceLastSleep.add(");
    }
}
