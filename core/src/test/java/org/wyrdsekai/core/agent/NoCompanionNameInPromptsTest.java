package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A companion's prompt must name HER, and no one else.
 *
 * <p>Every companion's system prompt was built by taking a literal that named the
 * default companion and running {@code replace(<that name>, hers)} over it, and the
 * voice-polish stage was told outright "you are the voice stage for &lt;that name&gt;"
 * regardless of who was speaking. The name leaked: a household companion's own-time
 * speech kept referring to a third party who was not in the room — "Wyrd was there
 * before I could name it", "Wyrd is waiting for us" (live 2026-08-17). Read as
 * confabulated memory for a week; it was an echo of a name we had put in her prompt.
 *
 * <p>The blanket substitution was also unsound on its own terms — it rewrites any word
 * CONTAINING the name, so a prompt mentioning the product would have been mangled into
 * nonsense. A placeholder cannot do either.
 */
class NoCompanionNameInPromptsTest {

    @Test
    void a_companion_prompt_names_only_that_companion() {
        var prompt = Companions.promptFor("Alder");
        assertThat(prompt).contains("You are Alder");
        assertThat(prompt).doesNotContain(Companions.DEFAULT_NAME);
        assertThat(prompt).doesNotContain("{name}");
    }

    @Test
    void the_default_companions_prompt_names_the_default_companion() {
        assertThat(Companions.NEXUS_COMPANION.systemPrompt())
            .contains("You are " + Companions.DEFAULT_NAME);
        assertThat(Companions.NEXUS_COMPANION.systemPrompt()).doesNotContain("{name}");
    }

    @Test
    void every_name_placeholder_is_filled() {
        // A missed placeholder would ship "You are {name}" to the model. Braces alone
        // are NOT the test — the prompt documents the scripting API and legitimately
        // contains `emit("narrate", {text: "..."})`.
        assertThat(Companions.promptFor("Bramble")).doesNotContain("{name}");
        assertThat(Companions.NEXUS_COMPANION.systemPrompt()).doesNotContain("{name}");
    }

    @Test
    void substitution_cannot_mangle_words_that_merely_contain_the_name() {
        // The old replace() would have turned an occurrence of the product name into
        // "<companion>sekai". Filling a placeholder leaves surrounding text alone.
        var prompt = Companions.promptFor("Alder");
        assertThat(prompt).doesNotContain("Aldersekai");
        assertThat(prompt.split("Alder", -1).length - 1)
            .as("the name appears only where the template put it")
            .isEqualTo(Companions.promptFor("Bramble").split("Bramble", -1).length - 1);
    }
}
