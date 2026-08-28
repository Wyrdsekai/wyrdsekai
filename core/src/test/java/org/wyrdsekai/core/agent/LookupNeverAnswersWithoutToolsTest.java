package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.affordance.RequestRelevance;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A question of fact must never be answered by the tier that cannot check facts.
 *
 * <p>Live, 2026-08-07. "mia, can u look through my books/library and tell me what
 * significant thing did the librarian tell kestan about velsharas in glass tide?"
 * The TASK_PRESENT head read it <b>none@0.77</b> — confidently no-task — so the
 * turn routed to {@code cap:quick}, which ships no tools, and she replied:</p>
 *
 * <blockquote>"I don't have any record of a librarian speaking with kestan about
 * velsharas — I'm not making something up for you here."</blockquote>
 *
 * <p>The book was indexed. The passage was two seconds of BM25 away. A toolless
 * tier asked a question of fact does not fail quietly by doing nothing — it
 * fails by <b>asserting an absence it had no way to check</b>, and a confident
 * denial is much harder for the person to see through than silence.</p>
 *
 * <p>The head is documented as length-sensitive, so it will keep misreading long
 * politely-phrased lookups. The gate reuses the signal that already knows what a
 * library request looks like.</p>
 */
class LookupNeverAnswersWithoutToolsTest {

    /** The sentence that produced the false denial. */
    private static final String THE_UTTERANCE =
        "ari, can u look through my books/library and tell me what significant "
        + "thing did the librarian tell kestan about velsharas in glass tide?";

    /** True when the voice-route override fires — the request reads as a lookup. */
    private static boolean keepsTools(String request) {
        return RequestRelevance.score(request, "library_search", null) >= 1.0;
    }

    /** THE case: this request must keep its hands whatever the head says. */
    @Test
    void the_live_utterance_is_recognised_as_a_lookup() {
        assertThat(keepsTools(THE_UTTERANCE))
            .as("a confident no-task read must not strip tools from a lookup")
            .isTrue();
    }

    /** Ordinary ways of asking the same thing. */
    @Test
    void other_phrasings_of_a_lookup_also_keep_tools() {
        for (var q : new String[]{
                "what do my books say about sourdough starters",
                "check the library for anything on Sumerian myth",
                "is there a novel by Arden on the shelf",
                "anything in my reading about velsharas",
                "which volume covers chapter nine"}) {
            assertThat(keepsTools(q)).as(q).isTrue();
        }
    }

    /**
     * The override must stay narrow. Relational talk is exactly what the voice
     * tier is for, and widening this would undo the routing entirely.
     */
    @Test
    void ordinary_conversation_is_left_alone() {
        for (var q : new String[]{
                "how are you feeling today",
                "i had a rough day at work",
                "good morning",
                "tell me something you noticed",
                "do you ever get bored when i'm out"}) {
            assertThat(keepsTools(q))
                .as("must not drag every warm turn back to the tool tier: " + q)
                .isFalse();
        }
    }

    /** Other tools' requests are not this gate's business. */
    @Test
    void does_not_fire_for_unrelated_tasks() {
        assertThat(keepsTools("what is 17 times 3")).isFalse();
        assertThat(keepsTools("google the news about the outage")).isFalse();
        assertThat(keepsTools("what's the weather tomorrow")).isFalse();
    }

    /** The gate is wired into the routing decision, not merely available. */
    @Test
    void the_gate_is_actually_wired_into_the_voice_route() throws Exception {
        var src = Files.readString(sourceOf(
            "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));

        int decision = src.indexOf("boolean voiceOnly = \"none\".equals(label) && confident;");
        int override = src.indexOf("voiceOnly && RequestRelevance.score(");

        assertThat(decision).as("the routing decision must still exist").isGreaterThan(0);
        assertThat(override).as("the lookup override must exist").isGreaterThan(0);
        assertThat(override)
            .as("the override has to come AFTER the decision it overrides")
            .isGreaterThan(decision);
        assertThat(src.substring(override, Math.min(src.length(), override + 400)))
            .as("and it must actually restore tools")
            .contains("voiceOnly = false");
    }

    private static Path sourceOf(String repoRelative) {
        var fromCore = Paths.get("..", repoRelative);
        return Files.exists(fromCore)
            ? fromCore : Paths.get(repoRelative);
    }
}
