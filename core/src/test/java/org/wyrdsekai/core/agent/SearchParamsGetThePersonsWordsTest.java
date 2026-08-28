package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Which parameters may receive the person's words, and which must not.
 *
 * <p>Merging the bondholder's literal question into a model-written value is a
 * repair for a SEARCH — it can only add discriminating terms. For {@code text}
 * it would be a corruption: journal and nostr_quill treat that parameter as
 * content the companion is about to author, so appending the question there
 * would have her record the bondholder's words as her own reflection and
 * publish them. The same mistake, in the other direction, is why the dispatcher
 * was taught to honour the item's declared slot rather than blanket-injecting
 * (see {@link DeclaredFreeFormParamTest}).</p>
 */
class SearchParamsGetThePersonsWordsTest {

    /** Slots that name a thing to look FOR. */
    @Test
    void search_slots_accept_the_persons_words() {
        assertThat(CompanionActor.isSearchParam("query")).isTrue();
        assertThat(CompanionActor.isSearchParam("topic")).isTrue();
        assertThat(CompanionActor.isSearchParam("keywords")).isTrue();
        assertThat(CompanionActor.isSearchParam("subject")).isTrue();
    }

    /** Slots that hold something to be WRITTEN. This is the load-bearing one. */
    @Test
    void authoring_slots_do_not() {
        assertThat(CompanionActor.isSearchParam("text"))
            .as("journal/nostr_quill would record the bondholder's question as her reflection")
            .isFalse();
        assertThat(CompanionActor.isSearchParam("content")).isFalse();
        assertThat(CompanionActor.isSearchParam("body")).isFalse();
        assertThat(CompanionActor.isSearchParam("message")).isFalse();
    }

    /** Command-style slots must be left exactly as the model set them. */
    @Test
    void command_and_target_slots_do_not() {
        assertThat(CompanionActor.isSearchParam("template")).isFalse();
        assertThat(CompanionActor.isSearchParam("target")).isFalse();
        assertThat(CompanionActor.isSearchParam("args")).isFalse();
        assertThat(CompanionActor.isSearchParam("action")).isFalse();
    }

    /** Case and absence must not decide it. */
    @Test
    void it_is_case_insensitive_and_null_safe() {
        assertThat(CompanionActor.isSearchParam("Query")).isTrue();
        assertThat(CompanionActor.isSearchParam("TOPIC")).isTrue();
        assertThat(CompanionActor.isSearchParam(null)).isFalse();
        assertThat(CompanionActor.isSearchParam("")).isFalse();
    }

    /** The dispatcher must merge rather than overwrite a usable value. */
    @Test
    void the_dispatcher_adds_and_does_not_replace() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Paths.get("..", rel);
        var src = Files.readString(
            Files.exists(fromCore) ? fromCore : Paths.get(rel));

        assertThat(src)
            .as("the merge must go through the shared query vocabulary, not a local copy")
            .contains("WyrdLuceneStore.withPersonTerms(currentValue, userRequest)");
        assertThat(src)
            .as("and must be gated on the slot being a search slot")
            .contains("isSearchParam(primaryParam)");
    }
}
