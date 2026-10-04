package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A person asking a companion what she has learned, read, made or been doing is asking about her
 * record, and she keeps her memory and her reading to answer with.
 *
 * <p>A warm check-in was read as a call for presence, and presence withholds the library, the web
 * and her own memory search. "so what did you guys learn of late?" took 45 tools from her: 172
 * turns in a week on the household node (2026-10-03). The lines below are the steward's own.
 */
class AWarmQuestionAboutHerDaysKeepsHerRecordTest {

    @Test
    void his_questions_about_their_days_are_questions_about_their_record() {
        for (var line : List.of(
                "so what did you guys learn of late?",
                "so how you guys been exploring?",
                "so what do u guys remember learning",
                "what have you been reading lately?",
                "mia, what did you make this week?",
                "tell me, what have you two been working on?",
                "anything you found in the library today?")) {
            assertThat(ActionTriage.asksAboutHerDays(line)).as(line).isTrue();
        }
    }

    @Test
    void a_greeting_a_goodbye_and_a_feeling_are_not() {
        for (var line : List.of(
                "just dropping in and check u guys",
                "ok both of u i need to go.  see you later",
                "i would like that mia",
                "please go about your day. I need to work",
                "sounds good. i need to do. see u",
                "I had a hard day and I do not want to talk about what I learned",
                "how are you feeling?",
                "I miss my father today.")) {
            assertThat(ActionTriage.asksAboutHerDays(line)).as(line).isFalse();
        }
    }

    @Test
    void the_turn_is_put_back_to_an_ordinary_one_and_a_confession_is_not() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var from = Path.of("..", rel);
        var ca = Files.readString(Files.exists(from) ? from : Path.of(rel));
        assertThat(ca).contains("if (register == ActionTriage.InteractionRegister.PRESENCE\n"
            + "                && ActionTriage.asksAboutHerDays(stripActorWrappers(triggerText))\n"
            + "                && !ActionTriage.isFirstPersonHarmConfession(stripActorWrappers(triggerText))) {");
        assertThat(ca).contains("Question about her days: register PRESENCE -> NEUTRAL");
    }
}
