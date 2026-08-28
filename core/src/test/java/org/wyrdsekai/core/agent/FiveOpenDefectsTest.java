package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * The five defects the 08-09 live batteries left standing, fixed as a set.
 *
 * <p>Each one was observed, not hypothesised: ten live runs across two
 * questions produced a measured ~50% tool-invocation rate, two premature
 * absence claims, one wrong-scene answer from the right book, one raw action
 * object spoken aloud, one verbatim repeat, and "I can't find a way to get to
 * Study" said from inside the Study in a third of runs.</p>
 */
class FiveOpenDefectsTest {

    private static String src() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Paths.get("..", rel);
        return Files.readString(
            Files.exists(fromCore) ? fromCore : Paths.get(rel));
    }

    // ── 1. Library-first ────────────────────────────────────────────────

    /** A question of fact opens the book before it opens her mouth. */
    @Test
    void a_fact_question_about_books_arms_the_forced_first_lookup() {
        assertThat(CompanionActor.looksLikeFactQuestion(
            "ari, look in my books - what did lazarus long figure out?")).isTrue();
        assertThat(CompanionActor.looksLikeFactQuestion(
            "recite the poem finkle-mcgraw sent to hackworth word for word")).isTrue();
        assertThat(CompanionActor.looksLikeFactQuestion(
            "tell me about the librarian and the velsharas")).isTrue();
    }

    /** Statements about books must NOT force a lookup — she may just act. */
    @Test
    void statements_and_greetings_do_not() {
        assertThat(CompanionActor.looksLikeFactQuestion("put the book back on the shelf"))
            .isFalse();
        assertThat(CompanionActor.looksLikeFactQuestion("good morning")).isFalse();
        assertThat(CompanionActor.looksLikeFactQuestion("i love this novel")).isFalse();
        assertThat(CompanionActor.looksLikeFactQuestion(null)).isFalse();
    }

    /** The force uses the same narrow-and-require mechanism follow-through built. */
    @Test
    void the_first_action_is_narrowed_to_the_library() throws Exception {
        var s = src();

        assertThat(s).contains("if (libraryFirstPending) {");
        assertThat(s).contains("libraryFirstPending = false;");
        assertThat(s).contains("Library-first FORCE");
        assertThat(s)
            .as("an empty surface must skip the force, never ship an empty tool list")
            .contains("Library-first armed but no library tool on this surface — skipped");
    }

    // ── 2. Early absence claims ─────────────────────────────────────────

    /** The digests that were spoken at the person before a recast could run. */
    @Test
    void absence_digests_are_recognised() {
        assertThat(CompanionActor.looksLikeAbsenceFinding(
            "The provided sources do not contain information about Lazarus Long")).isTrue();
        assertThat(CompanionActor.looksLikeAbsenceFinding(
            "I cannot answer this question because the provided sources...")).isTrue();
        assertThat(CompanionActor.looksLikeAbsenceFinding(
            "No results found in the library for: x")).isTrue();
    }

    /** A real answer must never be mistaken for an absence and held. */
    @Test
    void real_findings_are_not_held() {
        assertThat(CompanionActor.looksLikeAbsenceFinding(
            "The Librarian told Kestan that the vel-shara of Adrun is both a story "
                + "and an incantation")).isFalse();
        assertThat(CompanionActor.looksLikeAbsenceFinding(null)).isFalse();
    }

    /** Held once per turn — a persistent absence still reaches the person. */
    @Test
    void the_hold_is_once_per_turn_and_stays_in_memory() throws Exception {
        var s = src();

        assertThat(s).contains("absenceHeldThisTurn = true;");
        assertThat(s).contains("absenceHeldThisTurn = false;   // each turn gets one held absence");
        assertThat(s)
            .as("held, not erased — the loop must still see it to recast")
            .contains("Held an absence finding");
    }

    // ── 4. Speech hygiene ───────────────────────────────────────────────

    /** The live leak, verbatim shape: prose ending in a raw action object. */
    @Test
    void a_leaking_action_object_is_cut_from_speech() {
        var leaked = "I have no real words to offer this moment and that in itself "
            + "feels honest enough: [\"action\": \"say\", \"text\": \"The floor has "
            + "held me long past any of my own claims\"]";

        var cleaned = CompanionActor.stripInternalMarkers(leaked);

        assertThat(cleaned).doesNotContain("\"action\"");
        assertThat(cleaned).contains("feels honest enough");
    }

    /** Prose that merely mentions the word action is not machinery. */
    @Test
    void talking_about_action_is_still_allowed() {
        var fine = "Taking action felt right today.";

        assertThat(CompanionActor.stripInternalMarkers(fine)).isEqualTo(fine);
    }

    /** The verbatim-repeat guard sits on the one seam all speech crosses. */
    @Test
    void the_repeat_guard_exists_on_speakDirect() throws Exception {
        var s = src();

        assertThat(s).contains("Suppressed a verbatim repeat within");
        assertThat(s).contains("lastSpokenLine = repeatKey;");
    }

    // ── 5. Navigation phantom ───────────────────────────────────────────

    /** The dispatch site consults the co-occurrence terms (scene precision, #3). */
    @Test
    void scene_precision_prefers_co_occurrence() throws Exception {
        // Scene precision lives with the rest of the merge in KnowledgeSearch,
        // shared by the companion and person paths (2026-08-25).
        var rel = "core/src/main/java/org/wyrdsekai/core/item/KnowledgeSearch.java";
        var fromCore = Paths.get("..", rel);
        var s = Files.readString(
            Files.exists(fromCore) ? fromCore : Paths.get(rel));

        assertThat(s).contains("WyrdLuceneStore.protectedQueryTerms(query)");
        assertThat(s)
            .as("only reorders when the person gave a real conjunction")
            .contains("personTerms.size() >= 2");
    }

    /** The self-move check is normalized containment, not exact equality. */
    @Test
    void the_room_she_is_in_is_recognised_by_any_of_its_names() throws Exception {
        var s = src();

        assertThat(s).contains("boolean isCurrentRoom(String target)");
        assertThat(s)
            .as("the phantom: exact-match self-check + model saying 'Study' for "
                + "'operator's Study' / 'study-3f2a…'")
            .contains("if (currentSnapshot != null && isCurrentRoom(target)) {");
        assertThat(s)
            .as("short fragments must never be swallowed as 'already here'")
            .contains("if (t.length() <= 2) return false;");
    }
}
