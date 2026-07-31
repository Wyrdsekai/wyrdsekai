package org.wyrdsekai.core.codemode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Track A Phase 2b — heuristic trigger contract.
 *
 * <p>The trigger must fire on multi-step research-shape requests and stay
 * silent on single-step tool calls or conversational chatter. False
 * negatives are tolerated; false positives (firing on a turn the model
 * can't really compose for) are the failure mode we test against.
 */
class ImprovisationTriggerTest {

    // ── Positive cases — must fire ──────────────────────────────────────────

    @Test
    void fires_on_find_X_and_Y() {
        var d = ImprovisationTrigger.evaluate(
            "find me a book about Norse mythology and the searching glass for current events");
        assertThat(d.fires()).isTrue();
        assertThat(d.reason()).isNotBlank();
    }

    @Test
    void fires_on_search_X_and_Y_phrasing() {
        var d = ImprovisationTrigger.evaluate(
            "search for spells of binding and any related rituals in the library");
        assertThat(d.fires()).isTrue();
    }

    @Test
    void fires_on_look_at_both() {
        var d = ImprovisationTrigger.evaluate("look at both the library and the searching glass for me");
        assertThat(d.fires()).isTrue();
        assertThat(d.reason()).contains("both");
    }

    @Test
    void fires_on_search_both() {
        var d = ImprovisationTrigger.evaluate("can you search both sources for that name?");
        assertThat(d.fires()).isTrue();
    }

    @Test
    void fires_on_compare_X_vs_Y() {
        var d = ImprovisationTrigger.evaluate("compare ancient Greek mythology vs Norse cosmology");
        assertThat(d.fires()).isTrue();
        assertThat(d.reason()).contains("comparison");
    }

    @Test
    void fires_on_X_versus_Y() {
        var d = ImprovisationTrigger.evaluate("show me Egyptian versus Babylonian creation myths");
        assertThat(d.fires()).isTrue();
    }

    @Test
    void fires_on_for_each_iteration() {
        var d = ImprovisationTrigger.evaluate("for each of these names, look up the etymology");
        assertThat(d.fires()).isTrue();
        assertThat(d.reason()).contains("for-each");
    }

    @Test
    void fires_on_across_multiple_sources() {
        var d = ImprovisationTrigger.evaluate("dig up information across multiple sources please");
        assertThat(d.fires()).isTrue();
    }

    @Test
    void fires_on_from_several_sources() {
        var d = ImprovisationTrigger.evaluate("get everything you can from several sources on this");
        assertThat(d.fires()).isTrue();
    }

    @Test
    void fires_on_dedupe_and_merge() {
        var d = ImprovisationTrigger.evaluate("search both, then dedupe the results before showing them");
        assertThat(d.fires()).isTrue();
    }

    @Test
    void fires_on_cross_reference() {
        var d = ImprovisationTrigger.evaluate("can you cross-reference the citations and the library?");
        assertThat(d.fires()).isTrue();
    }

    // ── Negative cases — must NOT fire ──────────────────────────────────────

    @Test
    void does_not_fire_on_simple_search() {
        var d = ImprovisationTrigger.evaluate("search for Norse mythology");
        assertThat(d.fires()).isFalse();
    }

    @Test
    void does_not_fire_on_single_tool_call() {
        var d = ImprovisationTrigger.evaluate("what does the oracle predict for tomorrow?");
        assertThat(d.fires()).isFalse();
    }

    @Test
    void does_not_fire_on_conversational_chatter() {
        var d = ImprovisationTrigger.evaluate("how are you doing today?");
        assertThat(d.fires()).isFalse();
    }

    @Test
    void does_not_fire_on_empty_string() {
        var d = ImprovisationTrigger.evaluate("");
        assertThat(d.fires()).isFalse();
    }

    @Test
    void does_not_fire_on_null() {
        var d = ImprovisationTrigger.evaluate(null);
        assertThat(d.fires()).isFalse();
    }

    @Test
    void does_not_fire_on_one_word_compare() {
        // Length floor catches the trivial "Compare." imperative.
        var d = ImprovisationTrigger.evaluate("Compare.");
        assertThat(d.fires()).isFalse();
    }

    @Test
    void does_not_fire_on_emote_content() {
        var d = ImprovisationTrigger.evaluate("I am sad and tired today");
        assertThat(d.fires()).isFalse();
    }

    @Test
    void does_not_fire_on_simple_acknowledgement() {
        var d = ImprovisationTrigger.evaluate("thank you so much");
        assertThat(d.fires()).isFalse();
    }

    @Test
    void does_not_fire_on_tell_request() {
        var d = ImprovisationTrigger.evaluate("tell Wyrd that the meeting is at 3");
        assertThat(d.fires()).isFalse();
    }

    @Test
    void does_not_fire_on_navigation() {
        var d = ImprovisationTrigger.evaluate("go to the library and wait there");
        assertThat(d.fires()).isFalse();
    }

    // ── Decision shape contract ─────────────────────────────────────────────

    @Test
    void decision_miss_carries_human_reason() {
        var d = ImprovisationTrigger.evaluate("hi there");
        assertThat(d.fires()).isFalse();
        assertThat(d.reason()).isNotBlank();
    }

    @Test
    void fires_convenience_method_matches_decision() {
        assertThat(ImprovisationTrigger.fires("search both for something")).isTrue();
        assertThat(ImprovisationTrigger.fires("hi there")).isFalse();
        assertThat(ImprovisationTrigger.fires(null)).isFalse();
    }
}
