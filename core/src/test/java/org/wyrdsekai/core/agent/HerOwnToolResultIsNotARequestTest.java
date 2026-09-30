package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The forced build loop opens only on a turn that answers a line a person said, and its
 * prompt never says that someone asked.
 *
 * <h2>What went wrong</h2>
 * Home node, 2026-09-22, nobody connected. On her own time mia used a room item (home,
 * later study_room). The judgment turn after each tool result is a Said under her OWN id
 * ({@code "[Tool completed] ..."}) and runs with {@code reactiveInference} set, so the
 * first-turn build-promise rule read her unaddressed offer ("What would you like me to
 * build? ... I'll build it.") as a promise to a person. It opened a forced-tool loop whose
 * standing prompt said "The bondholder asked you to build something"; she built rooms
 * and told the empty household she was "ready to build what you asked for".
 */
class HerOwnToolResultIsNotARequestTest {

    private static final String SELF = "agent-mia";

    /** {@code CompanionActor.isHumanTrigger}'s rule for a room with no co-present peer:
     *  not null, not self, not a remote or system sender. */
    private static final Predicate<WorldEvent.Said> SPOKEN_BY_PERSON = said -> {
        if (said == null || said.entityId() == null) return false;
        var id = said.entityId();
        if (id.equals(SELF)) return false;
        return !(id.startsWith("agent-") || id.startsWith("companion-") || id.equals("system"));
    };

    private static WorldEvent.Said said(String id, String name, String text) {
        return new WorldEvent.Said("nexus", Instant.now(), id, name, text);
    }

    /** The shape handleScriptedToolResult gives the judgment turn. */
    private static final WorldEvent.Said OWN_TOOL_RESULT = said(SELF, "mia",
        "[Tool completed] You are home.\n[Share the substance with the user in your own "
        + "words — never repeat this bracketed status text aloud.]");
    private static final WorldEvent.Said OWN_TOOL_FAILED = said(SELF, "mia",
        "[Tool failed] study_room: no results\n[Retry the tool with ALL required parameters "
        + "filled, or tell the user plainly what you could not do.]");
    private static final WorldEvent.Said PERSON_ASKS = said("player-1", "operator",
        "mia, build me a weather tool and put it in the nexus");

    private static String actorSource() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Path.of("..", rel);
        return Files.readString(Files.exists(fromCore) ? fromCore : Path.of(rel));
    }

    @Test
    @DisplayName("her own tool result on her own time answers nobody")
    void ownToolResultOnOwnTimeAnswersNobody() {
        assertThat(CompanionActor.servesAPersonsLine(OWN_TOOL_RESULT, null, SPOKEN_BY_PERSON)).isFalse();
        assertThat(CompanionActor.servesAPersonsLine(OWN_TOOL_FAILED, null, SPOKEN_BY_PERSON)).isFalse();
    }

    @Test
    @DisplayName("the own-time prompt and the greeting are not requests")
    void systemTriggersAnswerNobody() {
        var ownTime = said("system", "system", "(own time) A seeking pull moves in you right now.");
        var greeting = said("system", "operator", "[operator enters the room]");
        assertThat(CompanionActor.servesAPersonsLine(ownTime, null, SPOKEN_BY_PERSON)).isFalse();
        assertThat(CompanionActor.servesAPersonsLine(greeting, null, SPOKEN_BY_PERSON)).isFalse();
        assertThat(CompanionActor.servesAPersonsLine(null, null, SPOKEN_BY_PERSON)).isFalse();
    }

    @Test
    @DisplayName("a person's line is answered")
    void aPersonsLineIsAnswered() {
        assertThat(CompanionActor.servesAPersonsLine(PERSON_ASKS, PERSON_ASKS, SPOKEN_BY_PERSON)).isTrue();
    }

    @Test
    @DisplayName("a tool result inside a person's turn still answers that person")
    void toolResultInAPersonsTurnAnswersThePerson() {
        assertThat(CompanionActor.servesAPersonsLine(OWN_TOOL_RESULT, PERSON_ASKS, SPOKEN_BY_PERSON)).isTrue();
        assertThat(CompanionActor.servesAPersonsLine(OWN_TOOL_FAILED, PERSON_ASKS, SPOKEN_BY_PERSON)).isTrue();
    }

    @Test
    @DisplayName("a peer's line is not a person's, directly or as the pinned request")
    void aPeersLineIsNotAPersons() {
        var rose = said("agent-rose", "rose", "mia, could you build a listening room?");
        assertThat(CompanionActor.servesAPersonsLine(rose, rose, SPOKEN_BY_PERSON)).isFalse();
        assertThat(CompanionActor.servesAPersonsLine(OWN_TOOL_RESULT, rose, SPOKEN_BY_PERSON)).isFalse();
        var remote = said("companion-lulu", "lulu", "build me a tool");
        assertThat(CompanionActor.servesAPersonsLine(remote, null, SPOKEN_BY_PERSON)).isFalse();
    }

    @Test
    @DisplayName("only a tool result borrows the pinned request, not her own musing")
    void herMusingDoesNotBorrowThePin() {
        var musing = said(SELF, "mia", "I think I'll build a quiet place.");
        assertThat(CompanionActor.servesAPersonsLine(musing, PERSON_ASKS, SPOKEN_BY_PERSON)).isFalse();
    }

    @Test
    @DisplayName("the promise is still read against the trigger, not the person's earlier question")
    void thePromiseIsReadAgainstTheTrigger() {
        // Why the gate borrows the pin only to decide WHO the turn answers: read against the
        // person's question, an ordinary follow-up after a successful lookup reads as a fetch
        // promise, and the loop would tell her "you have NOT called any tool yet" when she had.
        var reply = "It's 63 and cloudy in Boston right now. Let me check tomorrow's forecast too.";
        assertThat(CompanionActor.companionMadeFetchPromise("mia, what's the weather in Boston?", reply))
            .isTrue();
        assertThat(CompanionActor.companionMadeFetchPromise(
            "[Tool completed] Boston: 63F, cloudy.\n[Share the substance with the user in your own "
                + "words — never repeat this bracketed status text aloud.]", reply))
            .isFalse();
    }

    @Test
    @DisplayName("the build loop's prompt claims no request")
    void theLoopClaimsNoRequest() throws Exception {
        assertThat(CompanionActor.BUILD_LOOP_MISSION)
            .doesNotContainIgnoringCase("asked")
            .doesNotContainIgnoringCase("bondholder");
        var src = actorSource();
        var body = src.substring(src.indexOf("private void continueBuildAsReact(String toolResult, WorldEvent.Said requester)"));
        body = body.substring(0, body.indexOf("\n    }\n"));
        assertThat(body).contains("BUILD_LOOP_MISSION").doesNotContainIgnoringCase("asked");
    }

    @Test
    @DisplayName("the build-promise promotion asks who the turn answers, with isHumanTrigger")
    void thePromotionAsksWhoSpoke() throws Exception {
        var src = actorSource();
        var log = src.indexOf("First-turn build-promise with no tool call");
        assertThat(log).isPositive();
        var gate = src.lastIndexOf("boolean promotedBuildPromise = false;", log);
        assertThat(gate).isPositive();
        var block = src.substring(gate, log);
        assertThat(block)
            .contains("&& servesAPersonsLine(wasTrigger,")
            .contains("wasTrigger == judgmentTrigger ? judgmentFor : pinnedTurnRequest(),")
            .contains("this::isHumanTrigger)")
            .as("any trigger at all is not a request")
            .doesNotContain("reactMessages == null && wasTrigger != null")
            .as("person turns keep reading the promise against the trigger, as before")
            .contains("companionMadeActionPromise(wasTrigger.text(), content)");
    }
}
