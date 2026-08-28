package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import org.wyrdsekai.common.event.WorldEvent;

/** Speech-boundary hygiene (second-node 2026-07-10): internal bracketed status markers must never
 *  reach user-facing speech — the 9B parrots synthetic-trigger prefixes into replies. */
class SpeechHygieneTest {

    @Test
    void strips_the_verbatim_secondNode_leak() {
        var s = CompanionActor.stripInternalMarkers(
            "[Tool completed] [Tool error] missing_args — need a non-empty query string for "
            + "morning_briefing; calling with the San Francisco forecast window.");
        assertThat(s).doesNotContain("[Tool completed]").doesNotContain("[Tool error]");
        assertThat(s).startsWith("missing_args");
    }

    @Test
    void strips_all_marker_kinds() {
        assertThat(CompanionActor.stripInternalMarkers("[Tool failed] x")).isEqualTo("x");
        assertThat(CompanionActor.stripInternalMarkers("[Tool result] y")).isEqualTo("y");
        assertThat(CompanionActor.stripInternalMarkers("[Task completed] z")).isEqualTo("z");
        assertThat(CompanionActor.stripInternalMarkers("[PENDING REPLY] w")).isEqualTo("w");
    }

    @Test
    void strips_new_interoception_and_usage_markers() {
        assertThat(CompanionActor.stripInternalMarkers(
            "[Body-sense: steady — reserves full, nothing pressing on me.] I checked the forecast."))
            .isEqualTo("I checked the forecast.");
        assertThat(CompanionActor.stripInternalMarkers(
            "[Tool usage: morning_briefing requires a non-empty address] here it is"))
            .isEqualTo("here it is");
    }

    @Test
    void leaves_normal_speech_alone() {
        var s = "I found the forecast [for next week] — sunny, 65-72F.";
        assertThat(CompanionActor.stripInternalMarkers(s)).isEqualTo(s);
        assertThat(CompanionActor.stripInternalMarkers("no brackets at all")).isEqualTo("no brackets at all");
        assertThat(CompanionActor.stripInternalMarkers(null)).isNull();
    }

    // ── Rita re-verify 2026-07-11 (#29): drives prefix + system-prompt echo ──

    @Test
    void strips_drives_prompt_prefix() {
        assertThat(CompanionActor.stripInternalMarkers(
            "[drives: seeking=0.30 care=0.10 | energy=0.70 confidence=0.50] Good morning!"))
            .isEqualTo("Good morning!");
        // Bracket-less variant tolerance: the marker family is bracketed, but the
        // drives shape sometimes arrives truncated — leading form still bracketed.
        assertThat(CompanionActor.stripInternalMarkers(
            "[drives seeking=0.4] here's what I found"))
            .isEqualTo("here's what I found");
    }

    @Test
    void strips_system_prompt_fragment_lines() {
        var s = CompanionActor.stripInternalMarkers(
            "You are an agent that uses tools to complete tasks.\n"
            + "The garden is east of the nexus.");
        assertThat(s).isEqualTo("The garden is east of the nexus.");
        // Role declarations WITHOUT harness vocabulary are real speech — keep.
        var honest = "You are a companion to me too, you know.";
        assertThat(CompanionActor.stripInternalMarkers(honest)).isEqualTo(honest);
    }

    // ── Interoception push (2026-07-10): body-sense band mapping ──────────────

    @Test
    void body_sense_steady_when_tanks_healthy() {
        // mia's actual state during the weather-question incident: allostatic low,
        // equanimity at its 0.2 resting level — the retreat happened with full reserves.
        assertThat(CompanionActor.bodySenseBandFor(0.12, 0.20)).isEqualTo("steady");
        assertThat(CompanionActor.bodySenseBandFor(0.0, 0.2)).isEqualTo("steady");
    }

    @Test
    void body_sense_stretched_under_real_load() {
        assertThat(CompanionActor.bodySenseBandFor(0.55, 0.2)).isEqualTo("stretched");
    }

    @Test
    void body_sense_depleted_under_sustained_overload_or_eroded_equanimity() {
        assertThat(CompanionActor.bodySenseBandFor(0.85, 0.2)).isEqualTo("depleted");
        // §24.4 erosion path: equanimity worn below the §23 floor threshold reads
        // depleted even before allostatic crosses its own line.
        assertThat(CompanionActor.bodySenseBandFor(0.5, 0.05)).isEqualTo("depleted");
    }

    // ── Fetch-promise gate (second-node 2026-07-10 run 4): promise + no tool = promote ──

    @Test
    void run4_verbatim_fetch_promise_is_detected() {
        var q = "hey mia - what will the weather in san francisco be like next week? 7/14 and 7/15";
        var reply = "I'll get that forecast for San Francisco, operator. "
            + "Let me reach into my tools — I'm going to call";
        assertThat(CompanionActor.companionMadeFetchPromise(q, reply)).isTrue();
    }

    @Test
    void fetch_promise_requires_a_question_shaped_request() {
        var reply = "I'll get that forecast for you.";
        assertThat(CompanionActor.companionMadeFetchPromise("thanks for earlier", reply)).isFalse();
        assertThat(CompanionActor.companionMadeFetchPromise(null, reply)).isFalse();
    }

    @Test
    void ordinary_empathy_is_not_a_fetch_promise() {
        var q = "how are you feeling today?";
        assertThat(CompanionActor.companionMadeFetchPromise(q,
            "Let me check in with myself for a moment — I feel steady.")).isFalse();
        assertThat(CompanionActor.companionMadeFetchPromise(q,
            "I'm here with you. That sounds heavy.")).isFalse();
    }

    @Test
    void delivered_answer_without_commitment_is_not_flagged() {
        var q = "what will the weather be?";
        assertThat(CompanionActor.companionMadeFetchPromise(q,
            "The forecast shows sunny skies, 65-72F both days.")).isFalse();
    }

    // ── #31 item 6: [S1]-style RAG citation markers must never be voiced ──

    @Test
    void strips_bare_citation_markers_from_voiced_summaries() {
        assertThat(CompanionActor.stripInternalMarkers(
            "The relay is reachable again [S1][S3] and the zone map is current [S2]."))
            .isEqualTo("The relay is reachable again and the zone map is current .");
    }

    @Test
    void strips_source_block_headers_and_closers() {
        assertThat(CompanionActor.stripInternalMarkers(
            "[S1 | Norse Myths | mythology-pack] Odin rode Sleipnir. [/S1]"))
            .isEqualTo("Odin rode Sleipnir.");
    }

    @Test
    void citation_strip_leaves_lookalike_brackets_alone() {
        var schedule = "we meet [Saturday] at noon";
        assertThat(CompanionActor.stripInternalMarkers(schedule)).isEqualTo(schedule);
        var sizes = "sizes [S, M, L] available";
        assertThat(CompanionActor.stripInternalMarkers(sizes)).isEqualTo(sizes);
    }

    // ── #31 item 6: tool-call param scaffold hygiene (any </...> tag) ──

    @Test
    void param_scaffold_cuts_at_any_closing_tag() {
        assertThat(CompanionActor.stripToolParamScaffold(
            "what did operator say about relays</query>")).isEqualTo("what did operator say about relays");
        assertThat(CompanionActor.stripToolParamScaffold(
            "weather in tokyo</parameter> And another sentence.")).isEqualTo("weather in tokyo");
        assertThat(CompanionActor.stripToolParamScaffold(
            "find the ledger</function>")).isEqualTo("find the ledger");
        assertThat(CompanionActor.stripToolParamScaffold(
            "look this up<tool_call>{\"action\":\"x\"}")).isEqualTo("look this up");
    }

    @Test
    void param_scaffold_leaves_clean_values_and_legit_angles_alone() {
        assertThat(CompanionActor.stripToolParamScaffold("plain query")).isEqualTo("plain query");
        assertThat(CompanionActor.stripToolParamScaffold("is 3 < 5 and x <y?"))
            .isEqualTo("is 3 < 5 and x <y?");
        assertThat(CompanionActor.stripToolParamScaffold("i <3 you")).isEqualTo("i <3 you");
        assertThat(CompanionActor.stripToolParamScaffold(null)).isNull();
    }

    // ── #32 item 2: parroted tool-result instruction sentences ─────────────

    @Test
    void strips_share_the_substance_instruction_closed_and_unclosed() {
        var closed = CompanionActor.stripInternalMarkers(
            "Overcast, 63F in San Francisco.\n[Share the substance with the user in "
            + "your own words — never repeat this bracketed status text aloud.]");
        assertThat(closed).isEqualTo("Overcast, 63F in San Francisco.");
        // The 9B frequently drops the closing bracket (closing-verify 8d3a172b:
        // the instruction rendered to the player WITHOUT its terminal "]").
        var unclosed = CompanionActor.stripInternalMarkers(
            "Overcast, 63F in San Francisco.\n[Share the substance with the user in "
            + "your own words — never repeat this bracketed status text aloud.");
        assertThat(unclosed).isEqualTo("Overcast, 63F in San Francisco.");
    }

    @Test
    void strips_retry_and_present_findings_instructions() {
        assertThat(CompanionActor.stripInternalMarkers(
            "[Retry the tool with ALL required parameters filled, or tell the user "
            + "plainly what you could not do. Never repeat this bracketed status text "
            + "aloud, and do not retreat over a tool error — it is a mechanical "
            + "failure, not harm.] I couldn't reach the forecast service."))
            .isEqualTo("I couldn't reach the forecast service.");
        assertThat(CompanionActor.stripInternalMarkers(
            "Here's the outlook.\n[Present these findings to the user, then use "
            + "goal_done to complete the goal.]"))
            .isEqualTo("Here's the outlook.");
    }

    @Test
    void strips_unclosed_tool_usage_marker() {
        assertThat(CompanionActor.stripInternalMarkers(
            "[Tool usage: morning_briefing requires a non-empty address\nhere it is"))
            .isEqualTo("here it is");
    }

    @Test
    void extract_prose_strips_instruction_sentences_too() {
        var prose = ActionParser.extractProse(
            "The forecast is overcast tomorrow.\n[Share the substance with the user "
            + "in your own words — never repeat this bracketed status text aloud.");
        assertThat(prose).isEqualTo("The forecast is overcast tomorrow.");
    }

    @Test
    void instruction_strip_leaves_similar_real_speech_alone() {
        var share = "I want to [share something] with you about the harbor.";
        assertThat(CompanionActor.stripInternalMarkers(share)).isEqualTo(share);
        var noBracket = "Present these findings at the council if you like.";
        assertThat(CompanionActor.stripInternalMarkers(noBracket)).isEqualTo(noBracket);
    }

    // ── #32 item 1: tool-result follow-up detection (never-silent guard) ──

    @Test
    void tool_result_triggers_are_recognized() {
        var completed = new WorldEvent.Said(
            "room-1", Instant.now(), "agent-mia", "mia",
            "[Tool completed] Morning briefing for SF: overcast.\n[Share the substance "
            + "with the user in your own words — never repeat this bracketed status text aloud.]");
        assertThat(CompanionActor.isToolResultFollowUp(completed)).isTrue();
        var failed = new WorldEvent.Said(
            "room-1", Instant.now(), "agent-mia", "mia",
            "[Tool failed] morning_briefing: address is required");
        assertThat(CompanionActor.isToolResultFollowUp(failed)).isTrue();
    }

    @Test
    void ordinary_triggers_are_not_tool_result_follow_ups() {
        var say = new WorldEvent.Said(
            "room-1", Instant.now(), "player-1", "operator",
            "mia - what's the weather tomorrow?");
        assertThat(CompanionActor.isToolResultFollowUp(say)).isFalse();
        assertThat(CompanionActor.isToolResultFollowUp(null)).isFalse();
    }

    // ── #34 item 1: parroted suggest_hints serialization + [bracketed] literal ──

    @Test
    void strips_truncated_hints_scaffold() {
        // The observed leak was a bare, TRUNCATED "[hints: [" with no closing bracket.
        assertThat(CompanionActor.stripInternalMarkers(
            "Welcome to the garden. What would you like to do here? [hints: [Explore the fount"))
            .isEqualTo("Welcome to the garden. What would you like to do here?");
        // Fully-formed serialization (single-level) is also stripped.
        assertThat(CompanionActor.stripInternalMarkers(
            "What do you feel like? [hints: Explore, Rest, Look around]"))
            .isEqualTo("What do you feel like?");
    }

    @Test
    void strips_bracketed_literal_placeholder() {
        // Interiority line: the literal placeholder from the prompt instruction,
        // parroted verbatim mid-sentence.
        assertThat(CompanionActor.stripInternalMarkers(
            "no performance mode just yet.[bracketed]"))
            .isEqualTo("no performance mode just yet.");
        // Via the prose path too (shared stripSystemPromptFragments seam).
        assertThat(ActionParser.extractProse(
            "I'm just here, quietly. [bracketed]"))
            .isEqualTo("I'm just here, quietly.");
    }

    @Test
    void scaffold_strip_leaves_lookalike_brackets_alone() {
        var legit = "I found the forecast [for next week] — sunny, 65-72F.";
        assertThat(CompanionActor.stripInternalMarkers(legit)).isEqualTo(legit);
        // A word "hints" in ordinary prose (no colon/bracket opener) survives.
        var honest = "She gave me a few hints about the harbor.";
        assertThat(CompanionActor.stripInternalMarkers(honest)).isEqualTo(honest);
    }

    // ── #34 item 3: tell-to-requester double-answer suppression seam ──

    @Test
    void tell_to_requester_is_recognized_via_each_source() {
        // Matches the last tell sender.
        assertThat(CompanionActor.tellTargetIsRequester("operator", "operator", null, null)).isTrue();
        // Matches the active plan's requester.
        assertThat(CompanionActor.tellTargetIsRequester("Operator", null, "operator", null)).isTrue();
        // Matches the trigger's entity name — case-insensitive.
        assertThat(CompanionActor.tellTargetIsRequester("OPERATOR", null, null, "operator")).isTrue();
    }

    @Test
    void tell_to_peer_or_blank_is_not_the_requester() {
        // A peer target (not the requester) keeps its room-facing prose.
        assertThat(CompanionActor.tellTargetIsRequester("lulu", "operator", "operator", "operator")).isFalse();
        assertThat(CompanionActor.tellTargetIsRequester(null, "operator", "operator", "operator")).isFalse();
        assertThat(CompanionActor.tellTargetIsRequester("  ", "operator", "operator", "operator")).isFalse();
        assertThat(CompanionActor.tellTargetIsRequester("operator", null, null, null)).isFalse();
    }

    // ── #31 item 4: truncated-tool-call detection (silent-turn guard) ──

    @Test
    void detects_json_tool_call_cut_mid_string() {
        assertThat(CompanionActor.looksLikeTruncatedToolCall(
            "{\"action\":\"tell_agent\",\"target\":\"mia\",\"message\":\"two plus two is fo"))
            .isTrue();
    }

    @Test
    void detects_fenced_json_block_never_closed() {
        assertThat(CompanionActor.looksLikeTruncatedToolCall(
            "```json\n{\"action\":\"create_room\",\"name\":\"observat")).isTrue();
    }

    @Test
    void complete_tool_call_is_not_truncated() {
        assertThat(CompanionActor.looksLikeTruncatedToolCall(
            "{\"action\":\"tell_agent\",\"target\":\"mia\",\"message\":\"four\"}")).isFalse();
    }

    @Test
    void plain_prose_and_blank_are_not_truncated_calls() {
        assertThat(CompanionActor.looksLikeTruncatedToolCall(
            "I checked and the answer is four.")).isFalse();
        assertThat(CompanionActor.looksLikeTruncatedToolCall("")).isFalse();
        assertThat(CompanionActor.looksLikeTruncatedToolCall(null)).isFalse();
        // Prose that mentions braces but carries no tool-call shape.
        assertThat(CompanionActor.looksLikeTruncatedToolCall(
            "the config uses { curly braces }")).isFalse();
    }

    @Test
    void escaped_quotes_inside_strings_do_not_confuse_the_scan() {
        assertThat(CompanionActor.looksLikeTruncatedToolCall(
            "{\"action\":\"speak\",\"text\":\"she said \\\"hi\\\" and left\"}")).isFalse();
        assertThat(CompanionActor.looksLikeTruncatedToolCall(
            "{\"action\":\"speak\",\"text\":\"she said \\\"hi")).isTrue();
    }
}
