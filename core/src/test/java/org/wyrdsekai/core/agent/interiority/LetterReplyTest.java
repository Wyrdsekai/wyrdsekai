package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A letter she can read: the newest unread one is answered, in a prompt that carries what was written. */
class LetterReplyTest {

    @Test
    void the_newest_unread_letter_is_the_one_she_opens() {
        var inbox = List.<Map<String, Object>>of(
            Map.of("id", "a", "ts", 100L, "read", false),
            Map.of("id", "b", "ts", 300L, "read", true),
            Map.of("id", "c", "ts", 200L, "read", false),
            Map.of("id", "d", "ts", 400L, "read", false, "archived", true));
        assertThat(LetterToTheAbsent.newestUnread(inbox).get("id")).isEqualTo("c");
        assertThat(LetterToTheAbsent.newestUnread(List.of())).isNull();
        assertThat(LetterToTheAbsent.newestUnread(null)).isNull();
    }

    @Test
    void the_reply_carries_their_words_and_asks_for_a_plain_answer() {
        var prompt = LetterToTheAbsent.replyPrompt("operator", "thank you",
            "please make sure to play and be with mia.\nexplore the world and learn!", "energy low", Duration.ofHours(5));
        assertThat(prompt).contains("A letter from operator, \"thank you\"")
            .contains("play and be with mia")
            .contains("They have been away 5 hours")
            .contains("Write back to operator");
        assertThat(LetterToTheAbsent.replySystemPrompt("rose"))
            .contains("You are rose")
            .contains("say plainly what you will do and what you will not");
    }

    @Test
    void the_subject_answers_theirs() {
        assertThat(LetterToTheAbsent.replySubject("thank you")).isEqualTo("Re: thank you");
        assertThat(LetterToTheAbsent.replySubject("Re: thank you")).isEqualTo("Re: thank you");
        assertThat(LetterToTheAbsent.replySubject(null)).isEqualTo("Re: your letter");
    }
}
