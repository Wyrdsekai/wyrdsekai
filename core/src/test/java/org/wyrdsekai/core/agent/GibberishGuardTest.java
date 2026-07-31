package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Conservative gibberish detector (second-node 2026-07-08) — must catch keyboard-mash without
 *  stonewalling real short/unusual messages. */
class GibberishGuardTest {

    @Test
    void catches_obvious_keyboard_mash() {
        assertThat(CompanionActor.looksLikeGibberish("zxcvbnm sdfghjkl qwrtplk")).isTrue();
        assertThat(CompanionActor.looksLikeGibberish("hjkl bcdfg mnpqr")).isTrue();
    }

    @Test
    void lets_real_messages_through() {
        assertThat(CompanionActor.looksLikeGibberish("I had a long day, how are you?")).isFalse();
        assertThat(CompanionActor.looksLikeGibberish("build me a web search tool")).isFalse();
        assertThat(CompanionActor.looksLikeGibberish("lol ok thanks")).isFalse();
        assertThat(CompanionActor.looksLikeGibberish("what is my favorite tea")).isFalse();
    }

    @Test
    void short_or_empty_is_not_flagged() {
        assertThat(CompanionActor.looksLikeGibberish("brb")).isFalse();
        assertThat(CompanionActor.looksLikeGibberish("hi")).isFalse();
        assertThat(CompanionActor.looksLikeGibberish("")).isFalse();
        assertThat(CompanionActor.looksLikeGibberish(null)).isFalse();
    }
}
