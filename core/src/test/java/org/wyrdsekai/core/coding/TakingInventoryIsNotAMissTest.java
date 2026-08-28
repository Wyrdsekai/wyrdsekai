package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.CredentialResolver;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asking "do we have a key?" must not tell anybody a key is missing.
 *
 * <h2>What this cost, the day it shipped</h2>
 * {@link CredentialResolver#resolve} notifies the steward when something needed a
 * credential that is not set — which is exactly right when a tool wanted it, and exactly
 * wrong when we are merely taking inventory.
 *
 * <p>{@link ItemApiSurface} decides which adapters to advertise by asking whether their
 * key is present, and it asked with {@code resolve}. So authoring ONE item asked for all
 * seventeen credentials and sent fourteen notifications in a single second — redfin,
 * wolfram, coinbase, todoist, matrix, discord, shopify, goodreads, youtube, vonage,
 * khanacademy, github, google.translate, anthropic — for services nobody had mentioned.
 *
 * <p>A regression introduced the same day as the feature, by the fix for a different
 * problem. "Do we have this?" and "something needed this and it was missing" are different
 * questions, and only the second is worth interrupting a person for.
 */
class TakingInventoryIsNotAMissTest {

    private final List<String> notified = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        CredentialResolver.get().resetForTests();
        CredentialResolver.get().setSafeReader(
            slot -> "openweathermap.api_key".equals(slot)
                ? Optional.of("a-real-key") : Optional.empty());
        CredentialResolver.get().setMailboxNotifier((steward, slot) -> notified.add(slot));
    }

    @AfterEach
    void tearDown() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void probing_a_missing_slot_tells_nobody() {
        assertThat(CredentialResolver.get().has("redfin.api_key")).isFalse();
        assertThat(CredentialResolver.get().has("wolfram.app_id")).isFalse();
        assertThat(notified)
            .as("taking inventory must never interrupt the steward")
            .isEmpty();
    }

    @Test
    void probing_a_present_slot_answers_yes() {
        assertThat(CredentialResolver.get().has("openweathermap.api_key")).isTrue();
        assertThat(notified).isEmpty();
    }

    /**
     * And the real thing still speaks up. Removing the notification entirely would have
     * traded a spam problem for a silence problem — a tool that genuinely needs a key
     * must still say so.
     */
    @Test
    void something_that_actually_needed_it_still_notifies() {
        assertThat(CredentialResolver.get().resolve("redfin.api_key")).isEmpty();
        assertThat(notified)
            .as("a real miss is worth interrupting a person for")
            .containsExactly("redfin.api_key");
    }

    @Test
    void a_blank_slot_is_simply_absent() {
        assertThat(CredentialResolver.get().has(null)).isFalse();
        assertThat(CredentialResolver.get().has("  ")).isFalse();
        assertThat(notified).isEmpty();
    }
}
