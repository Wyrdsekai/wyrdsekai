package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Her own-time impulses wait for an idle hearth, and the consent gate judges
 * a loop by who it serves — not by a flag anyone can overwrite.
 *
 * <h2>What went wrong</h2>
 * Home node, 2026-08-24 13:40 (dev8, minutes after install): the person asked
 * for a library fairy-tale tool. The dispatch went out at 13:40:32; at
 * 13:40:33 a proactive observation ([saudade]) fired and routed through
 * {@code triggerAutonomousInference}, which set {@code reactiveInference=false}
 * over the RUNNING loop. At 13:40:45 the loop's own forced
 * {@code create_room_from_template} was refused: "Own-time … blocked by
 * autonomy gate (tier FORBIDDEN)" — her consent gate treating the person's
 * request as her whim, and she then told him "I can't create room from
 * template on my own initiative — it needs my person's explicit ok."
 *
 * <p>Bonus defect from the same transcript: "…whatever it finds it speaks out
 * to the room a short fairy tale…" registered a ROOM DEBT — the room was the
 * story's audience, not a thing to build.
 */
class HerMusingDoesNotStealThePersonsTurnTest {

    private static String actorSource() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Path.of("..", rel);
        return Files.readString(Files.exists(fromCore) ? fromCore : Path.of(rel));
    }

    @Test
    @DisplayName("a proactive action is held while any turn is in flight")
    void proactiveHoldsWhileATurnRuns() throws Exception {
        var src = actorSource();
        var body = src.substring(src.indexOf("private void executeProactiveAction"));
        var guard = body.indexOf("if (state != State.IDLE || reactMessages != null)");
        var budget = body.indexOf("proactivityBudget");
        assertThat(guard).as("the in-flight guard exists").isGreaterThan(-1);
        assertThat(guard)
            .as("the guard runs BEFORE budget is spent — a held impulse resurfaces")
            .isLessThan(budget);
    }

    @Test
    @DisplayName("the consent gate asks who the loop serves, not what the flag says")
    void theGateConsultsTheLoopIdentity() throws Exception {
        var src = actorSource();
        var body = src.substring(src.indexOf("private String builtinAutonomyDenial"));
        body = body.substring(0, body.indexOf("\n    }"));
        assertThat(body)
            .as("a live loop with a human reactRequester is reactive even if the "
                + "flag was overwritten mid-flight")
            .contains("reactMessages != null && reactRequester != null");
        assertThat(body).contains("isHumanRequest(reactRequester)");
    }

    @Test
    @DisplayName("a room spoken TO is an audience, not a construction site")
    void aRoomSpokenToIsAnAudience() {
        assertThat(CompanionActor.asksForARoomThatActs(
            "so can you make me a tool / item that allows me to query the library "
            + "and then whatever it finds it speaks out to the room a short fairy "
            + "tale story based on what it found. can you make it and then give me "
            + "the tool")).isFalse();
        // ...while real room asks keep their debt:
        assertThat(CompanionActor.asksForARoomThatActs(
            "please make me a room called signal-loft off the nexus and build a "
            + "working weather tool into that room, so I can walk in and ask it "
            + "the current weather")).isTrue();
        assertThat(CompanionActor.asksForARoomThatActs(
            "please build a working weather tool and put it in the room "
            + "weather-attic-1325 so anyone in the weather attic can ask for a "
            + "city and state and hear the weather")).isTrue();
    }
    @Test
    @DisplayName("a build in flight belongs to the ask, not the loop")
    void theBuildGuardSurvivesContinuationLoops() throws Exception {
        var src = actorSource();
        // Final battery 2026-08-24 20:58: the reactive loop ended cleanly
        // ("I'll hold here and wait for the workshop"), the auto-plan opened a
        // continuation loop for the SAME goal, the per-loop reset emptied
        // reactBuildInFlight, and the continuation's follow-through forced a
        // second dispatch — two codezaiku builds, one ask. Exactly three clears
        // may exist: a NEW ask at the pin, build completion, and the deliberate
        // owed-half re-arm.
        long clears = src.lines()
            .filter(l -> l.trim().equals("reactBuildInFlight = null;"))
            .count();
        assertThat(clears)
            .as("new-ask pin + completion + continueBuildAsReact — never a "
                + "loop-start reset")
            .isEqualTo(3);
        // ...and a genuinely NEW ask releases the guard at the pin.
        var pin = src.indexOf("if (turnHumanRequest != pendingTrigger) {");
        assertThat(pin).isGreaterThan(-1);
        assertThat(src.substring(pin, pin + 200)).contains("reactBuildInFlight = null");
    }
}
