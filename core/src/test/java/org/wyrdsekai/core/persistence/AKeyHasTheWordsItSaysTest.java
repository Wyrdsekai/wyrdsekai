package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.test.TestDb;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A recovery key is eight words joined with hyphens, and an invite passphrase six words
 * joined with spaces. One entry of the shared word list was itself hyphenated, so about one
 * key in thirty-five came out as nine words: a person counting them would doubt the key, and
 * the founder test failed on the release chain whenever the draw hit it (2026-10-24). The
 * list may hold no separator, and the guard is here rather than in the generator so the list
 * stays usable for both.
 */
class AKeyHasTheWordsItSaysTest {

    @Test
    void no_word_in_the_list_carries_a_separator() {
        for (var w : InviteService.getWordList()) {
            assertThat(w).as("word list entry").matches("[a-z]+");
        }
        assertThat(InviteService.getWordList().length).isGreaterThanOrEqualTo(200);
    }

    @Test
    void a_recovery_key_is_always_eight_words_and_a_passphrase_six() {
        var auth = new AuthService(TestDb.createInMemory());
        for (int i = 0; i < 12; i++) {
            assertThat(auth.generateRecoveryKey().split("-")).hasSize(8);
        }
        for (int i = 0; i < 200; i++) {
            assertThat(InviteService.generatePassphrase().split(" ")).hasSize(6);
        }
    }
}
