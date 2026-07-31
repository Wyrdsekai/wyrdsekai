package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link CompanionActor#cleanRecalledContent}.
 *
 * <p>Working memory is indexed in Lucene with a wrapper that helps the
 * companion's prompt assembly but must not leak into player-facing prose:
 * {@code "HH:MM "} timestamp + optional {@code "[Kind] sender: "} bracket
 * prefix + the actual content + optional {@code " [tags: …]"} suffix.
 * The {@code recall} ReAct tool surfaces matching entries to the player
 * via {@code speak()} — the test verifies the wrapper is stripped first.
 *
 * <p>Failing case discovered 2026-04-29 during dual-inference verification:
 * agent emitted {@code "Looking back, I find: 04:54 [user fact] anonymous:
 * i work at a company called mercari in san francisco [tags: occupation
 * job career work emplo…"} — every layer of the wrapper visible.
 */
class CleanRecalledContentTest {

    @Test
    void strips_full_user_fact_wrapper() {
        var raw = "04:54 [User fact] anonymous: I work at a company called Mercari in San Francisco [tags: occupation job career work employment]";
        assertEquals(
            "I work at a company called Mercari in San Francisco",
            CompanionActor.cleanRecalledContent(raw));
    }

    @Test
    void strips_pending_reply_wrapper() {
        var raw = "12:30 [PENDING REPLY] Report to alice via: {\"action\": \"tell_agent\", \"target\": \"alice\", \"message\": \"<findings>\"}";
        var cleaned = CompanionActor.cleanRecalledContent(raw);
        // The inner content after the bracket should remain
        assertEquals(
            "Report to alice via: {\"action\": \"tell_agent\", \"target\": \"alice\", \"message\": \"<findings>\"}",
            cleaned);
    }

    @Test
    void strips_timestamp_only_when_no_bracket() {
        var raw = "09:15 some plain note";
        assertEquals("some plain note", CompanionActor.cleanRecalledContent(raw));
    }

    @Test
    void strips_bracket_and_sender_when_no_timestamp() {
        var raw = "[User fact] bob: I love early grey tea";
        assertEquals("I love early grey tea", CompanionActor.cleanRecalledContent(raw));
    }

    @Test
    void strips_tags_suffix() {
        var raw = "I'm allergic to cashews [tags: food allergy dietary restriction]";
        assertEquals("I'm allergic to cashews", CompanionActor.cleanRecalledContent(raw));
    }

    @Test
    void preserves_clean_input() {
        var raw = "The user's current job is Mercari.";
        assertEquals("The user's current job is Mercari.",
            CompanionActor.cleanRecalledContent(raw));
    }

    @Test
    void handles_null_and_blank() {
        assertEquals("", CompanionActor.cleanRecalledContent(null));
        assertEquals("", CompanionActor.cleanRecalledContent(""));
        assertEquals("", CompanionActor.cleanRecalledContent("   "));
    }

    @Test
    void does_not_eat_colons_in_middle_of_prose() {
        // No bracket prefix, so the ": " in the middle should stay
        var raw = "Note: this should not be truncated";
        assertEquals("Note: this should not be truncated",
            CompanionActor.cleanRecalledContent(raw));
    }

    @Test
    void does_not_eat_long_apparent_sender() {
        // After bracket, if the "name: " portion is unreasonably long, leave it alone
        var raw = "[Memory] this is a really long sentence that looks like it might have a colon eventually: and continues here";
        var cleaned = CompanionActor.cleanRecalledContent(raw);
        // The 40-char name cap should prevent eating the long prefix
        assertEquals(
            "this is a really long sentence that looks like it might have a colon eventually: and continues here",
            cleaned);
    }

    @Test
    void timestamp_lookalike_in_middle_is_preserved() {
        // Only strips at the START of the string
        var raw = "We met at 15:30 yesterday";
        assertEquals("We met at 15:30 yesterday",
            CompanionActor.cleanRecalledContent(raw));
    }
}
