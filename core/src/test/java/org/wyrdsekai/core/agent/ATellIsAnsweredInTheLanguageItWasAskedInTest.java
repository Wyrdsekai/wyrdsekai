package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build box, 2026-10-01, a fresh install on the two default models: a visitor wrote
 * "what do you usually do when nobody is here?" as a tell, in English, and the answer delivered to
 * their session was "Siento el peso de la esquina y dejo que se asiente sobre mí…". The reply to a
 * tell was voiced by the small model with no language pin, a repeat penalty measured to cause the
 * drift, no check on what came back, and no floor where it was delivered. Room speech had all four.
 */
class ATellIsAnsweredInTheLanguageItWasAskedInTest {

    @Test
    void a_line_in_the_wrong_language_is_seen_before_it_is_delivered() {
        assertThat(TellLanguage.readsWrong(
            "Siento el peso de la esquina y dejo que se asiente sobre mí sin intentar arreglarlo.", "en")).isTrue();
        assertThat(TellLanguage.readsWrong(
            "I feel the weight of the corner and let it settle on me without trying to fix it.", "en")).isFalse();
        assertThat(TellLanguage.readsWrong("Vale, gracias.", "en")).isTrue();
        assertThat(TellLanguage.readsWrong("Okay, thanks.", "en")).isFalse();
        assertThat(TellLanguage.readsWrong(
            "Siento el peso de la esquina y dejo que se asiente sobre mí sin intentar arreglarlo.", "es")).isFalse();
    }

    @Test
    void a_language_the_detector_cannot_read_is_left_to_the_pin() {
        assertThat(TellLanguage.readsWrong("Je sens le poids du coin et je le laisse se poser.", "fr")).isFalse();
        assertThat(TellLanguage.readsWrong(null, "en")).isFalse();
        assertThat(TellLanguage.readsWrong("  ", "en")).isFalse();
        assertThat(TellLanguage.readsWrong("Hello there.", null)).isFalse();
    }

    /** Same visit: the tell was delivered, and then "I tried tell_agent but couldn't complete that for you." */
    @Test
    void an_answer_that_went_out_by_tell_is_not_followed_by_a_sentence_saying_it_failed() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var from = Path.of("..", rel);
        var ca = Files.readString(Files.exists(from) ? from : Path.of(rel));
        int at = ca.indexOf("private void emitTerminalNarrationIfNeeded(String exitReason) {");
        var body = ca.substring(at, ca.indexOf("var productive = reactToolHistory.stream()", at));
        assertThat(body).contains("if (reactToolHistory.contains(\"tell_agent\") || reactToolHistory.contains(\"respond_agent\")) return;");
    }

    /** "I'm moving to the Test Nook now. I'll let you know once I'm there." was said to an empty room. */
    @Test
    void an_answer_in_plain_words_goes_to_the_person_who_asked_wherever_they_are() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var from = Path.of("..", rel);
        var ca = Files.readString(Files.exists(from) ? from : Path.of(rel));
        // "Elsewhere" has to be found: the house places the person in another room. Someone it cannot
        // place is answered in the room, as before.
        assertThat(ca).contains("final String askerRoom = privatelyTo == null && answering != null && isHumanTrigger(answering)\n"
            + "            ? roomWhereThePersonIs(answering.entityId()) : null;\n"
            + "        final boolean askerElsewhere = askerRoom != null && !askerRoom.equals(roomId);");
        assertThat(ca).contains("if (askerElsewhere) {\n            if (whisperOrSession(answering.entityId(), adapted)) {");
        assertThat(ca).contains("} else if (sendReplyByMail(answering.entityId(), answering.entityName(), adapted)) {");
    }

    @Test
    void the_tells_voice_pass_is_pinned_checked_and_floored() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var from = Path.of("..", rel);
        var ca = Files.readString(Files.exists(from) ? from : Path.of(rel));
        // The pin leads the prompt, in the turn's language.
        assertThat(ca).contains("\"You speak \" + pinLang + \". Every reply you write is in \" + pinLang + \".\\n\"\n"
            + "                + \"You are \" + profile.name() + \", speaking in your own voice.\"");
        assertThat(ca).doesNotContain("Reply in the SAME LANGUAGE as the text below");
        // The repeat penalty no longer pushes the small model out of the language.
        assertThat(ca).contains("getOrDefault(\"WYRD_VOICE_REPEAT_PENALTY\", \"1.0\")");
        // What comes back is checked against the turn's language.
        assertThat(ca).contains("var kept = chooseVoicedLine(original.message(), paraphrased, Set.of(), msg.expectedLang(), reason);");
        // And a tell to a person that still reads wrong is re-rendered before any of its three doors.
        assertThat(ca).contains("if (TellLanguage.readsWrong(action.message(), expected)) {");
        int floor = ca.indexOf("if (TellLanguage.readsWrong(action.message(), expected)) {");
        assertThat(floor).isLessThan(ca.indexOf("speakProduct(\"[to \" + action.targetName() + \"] \" + action.message(), null, tellOwedTo);"));
    }
}
