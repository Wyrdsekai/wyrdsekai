package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Entity;
import org.wyrdsekai.common.model.RoomSnapshot;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Findings she has already read aloud are not handed back to her to retell.
 *
 * <p>The judgment turn after a tool's result used to carry the findings again with "share the
 * substance in your own words". While that turn came back as an echo of her own lines the dup
 * guard hid the repeat; once it is answered (see TheLineSheAnswersIsNotHersToContinueTest) she
 * would say the answer twice. When the findings were spoken, the turn says so instead.</p>
 */
class FindingsReadAloudAreNotHandedBackTest {

    private static final String READ_ALOUD_TRIGGER = "[Tool completed]\n" + CompanionActor.ALREADY_READ_ALOUD
        + "you have said these findings aloud. Add only what is yours to add (what you make of them, "
        + "what you want to ask next), or nothing. Never repeat this bracketed status text aloud.]";

    private static String src() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var p = Files.exists(Paths.get("..", rel)) ? Paths.get("..", rel) : Paths.get(rel);
        return Files.readString(p);
    }

    @Test
    void the_turn_is_still_a_tool_results_turn() {
        var trigger = new WorldEvent.Said("hearth", Instant.now(), "companion-mia", "mia", READ_ALOUD_TRIGGER);
        assertThat(CompanionActor.isToolResultFollowUp(trigger))
            .as("the never-silent invariant and the tool-result routing key on this prefix")
            .isTrue();
    }

    @Test
    void the_instruction_line_never_reaches_the_room() {
        var parroted = CompanionActor.ALREADY_READ_ALOUD + "you have said these findings aloud. Add only "
            + "what is yours to add, or nothing.]\nThe straight paths are what make it fast.";
        assertThat(ActionParser.extractProse(parroted)).isEqualTo("The straight paths are what make it fast.");
        assertThat(ActionParser.extractProse(CompanionActor.ALREADY_READ_ALOUD + "you have said these "
            + "findings aloud. Add only what is yours to add\nThe straight paths are what make it fast."))
            .as("with its closing bracket dropped too")
            .isEqualTo("The straight paths are what make it fast.");
    }

    /** The text from {@code from} up to the next {@code to}. */
    private static String between(String s, String from, String to) {
        int i = s.indexOf(from);
        assertThat(i).as("anchor: " + from).isGreaterThanOrEqualTo(0);
        int j = s.indexOf(to, i);
        assertThat(j).as("anchor: " + to).isGreaterThan(i);
        return s.substring(i, j);
    }

    @Test
    void spoken_findings_are_not_put_back_in_the_trigger() throws Exception {
        var s = src();
        var branch = between(s, "} else if (findingsSpoken) {", "} else {");
        assertThat(branch).contains("message = \"[Tool completed]\\n\" + ALREADY_READ_ALOUD");
        assertThat(branch).as("the findings themselves are not handed back")
            .doesNotContain("truncated").doesNotContain("resultSummary");
        assertThat(s).contains("? \"\\n[Present these findings: done. Use goal_done to complete the goal.]\"");
    }

    /** "Heard" only when the person the findings are owed to is in the room to hear them. */
    @Test
    void read_aloud_means_the_one_who_asked_heard_them() throws Exception {
        var s = src();
        var rule = between(s, "boolean heardInTheRoom = ", ";");
        assertThat(rule)
            .as("owed to no one, it was said unless a hush held it").contains("? bondholderHush == HushLevel.NONE")
            .contains("isAmbientPeerSpeech() && anyHumanPresentInRoom()")
            .contains(": isHumanTrigger(owedFindings) && isHereToHear(owedFindings)")
            .as("a phone that delegated the question hears only her reply").contains("pendingDelegateReply == null")
            .as("so does a bridge ask").contains("!(pendingAskReply != null && owedFindings.entityId().equals(pendingAskSenderId))");
        assertThat(between(s, "speakDirect(p, ActivityLogger.AUTHORED_TOOL, owed)", "Recited verbatim findings"))
            .contains("findingsSpoken = heardInTheRoom;");
        assertThat(between(s, "speakFindings(spoken, forPerson);", "Spoke tool findings directly"))
            .contains("findingsSpoken = heardInTheRoom;");
        assertThat(between(s, "absenceHeldThisTurn = true;", "} else {"))
            .as("a held absence was never said").doesNotContain("findingsSpoken");
        assertThat(s.split("findingsSpoken = ", -1).length - 1).as("declared, and set in those two places only")
            .isEqualTo(3);
    }

    @Test
    void quiet_after_reading_them_aloud_is_honest_however_long_the_turn_took() throws Exception {
        var s = src();
        int fallback = s.indexOf("private void speakToolResultFallback(");
        int readAloud = s.indexOf("if (!failed && text.contains(ALREADY_READ_ALOUD)) {", fallback);
        int window = s.indexOf("TOOL_FINDINGS_DUP_WINDOW)))", fallback);
        assertThat(readAloud).isGreaterThan(fallback).isLessThan(window);
        assertThat(between(s.substring(readAloud), "if (!failed", "\n        }")).contains("return;");
    }

    // ── Who is here to hear ───────────────────────────────────────────────

    private static RoomSnapshot hearth(Entity... entities) {
        return new RoomSnapshot("hearth", "The Hearth", "A warm room.", "foundation",
            List.of(), List.of(entities), List.of(), List.of());
    }

    @Test
    void someone_who_just_walked_in_is_here_before_the_room_is_looked_at_again() {
        var registry = new EntityRegistry();
        registry.enter("person-sam", "sam", "player", "hearth");
        assertThat(CompanionActor.isHereToHear("person-sam", "hearth", registry, hearth())).isTrue();
    }

    @Test
    void someone_who_just_left_is_not_here_though_the_old_snapshot_lists_them() {
        var registry = new EntityRegistry();
        registry.enter("person-sam", "sam", "player", "study");
        var stale = hearth(new Entity("person-sam", "sam", "player", ""));
        assertThat(CompanionActor.isHereToHear("person-sam", "hearth", registry, stale)).isFalse();
    }

    @Test
    void the_session_that_asked_must_be_here_not_another_of_theirs() {
        var otherSession = hearth(new Entity("login-uuid-of-sam", "sam", "player", ""));
        assertThat(CompanionActor.isHereToHear("did:key:sam", "hearth", new EntityRegistry(), otherSession)).isFalse();
        var same = hearth(new Entity("did:key:sam", "sam", "player", ""));
        assertThat(CompanionActor.isHereToHear("did:key:sam", "hearth", new EntityRegistry(), same))
            .as("with no registry entry, the snapshot answers").isTrue();
    }

    @Test
    void a_companion_in_the_room_is_not_a_person_to_hear() {
        var withAgent = hearth(new Entity("companion-rose", "rose", "agent", ""));
        assertThat(CompanionActor.isHereToHear("companion-rose", "hearth", null, withAgent)).isFalse();
        assertThat(CompanionActor.isHereToHear(null, "hearth", null, withAgent)).isFalse();
        assertThat(CompanionActor.isHereToHear("person-sam", "hearth", null, null)).isFalse();
    }

    /** What she adds after the findings were read aloud continues the exchange in the night. */
    @Test
    void her_addition_is_not_paired_with_the_question_as_its_answer() throws Exception {
        assertThat(between(src(), "judgmentFor = forPerson != null ? forPerson : orNoOne(personThisReplyAnswers());",
                "ownTimeJudgment ="))
            .contains("if (findingsSpoken) lastTrailedTrigger = judgmentFor;");
    }

    /** A plan's loop on the judgment turn builds its own prompt: it gets the findings from working memory. */
    @Test
    void the_judgment_loop_can_see_what_was_found() throws Exception {
        var init = between(src(), "if (isToolResultFollowUp(pendingTrigger)) {",
            "reactMessages.add(new InferenceClient.ChatMessage(\"system\", reactSystemPrompt));");
        assertThat(init).contains("buildWorkingMemoryContext()");
    }
}
