package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-unit tests for the static routing heuristics in CompanionActor that
 * decide whether a player tell should be auto-delegated to a prose-only
 * bunshin or stay in Wyrd's ReAct loop where tools are available.
 *
 * <p>These are fast pure-function tests — no pekko, no inference, no IO.</p>
 */
class CompanionActorRoutingHeuristicsTest {

    @Test
    void requiresToolExecution_catches_ember_task4_pattern() {
        assertThat(CompanionActor.requiresToolExecution(
                "[from anonymous] research quantum computing using both the library and web"))
                .isTrue();
    }

    @Test
    void requiresToolExecution_catches_explicit_library_and_web() {
        assertThat(CompanionActor.requiresToolExecution(
                "find me a book about mythology in the library")).isTrue();
        assertThat(CompanionActor.requiresToolExecution(
                "search the web for the latest AI research papers")).isTrue();
        assertThat(CompanionActor.requiresToolExecution(
                "look up the news about Japan")).isTrue();
        assertThat(CompanionActor.requiresToolExecution(
                "ask the oracle about patterns")).isTrue();
    }

    @Test
    void requiresToolExecution_skips_conversational_requests() {
        // "find peace" uses 'find' metaphorically — no external surface
        assertThat(CompanionActor.requiresToolExecution(
                "help me find peace with this")).isFalse();
        // "look" without "up" or external surface
        assertThat(CompanionActor.requiresToolExecution(
                "look at me when you speak")).isFalse();
        // Pure emotional / relational
        assertThat(CompanionActor.requiresToolExecution(
                "I'm feeling overwhelmed today")).isFalse();
        assertThat(CompanionActor.requiresToolExecution(
                "tell me how you're doing")).isFalse();
        assertThat(CompanionActor.requiresToolExecution(
                "what do you think about patience")).isFalse();
    }

    @Test
    void requiresToolExecution_handles_null_and_blank() {
        assertThat(CompanionActor.requiresToolExecution(null)).isFalse();
        assertThat(CompanionActor.requiresToolExecution("")).isFalse();
        assertThat(CompanionActor.requiresToolExecution("   ")).isFalse();
    }

    @Test
    void requiresToolExecution_strips_relayed_prefix() {
        // Both "from" prefix and actual content should be evaluated
        assertThat(CompanionActor.requiresToolExecution(
                "[from operator] research quantum computing")).isTrue();
        assertThat(CompanionActor.requiresToolExecution(
                "[from operator] how are you feeling")).isFalse();
    }

    @Test
    void requiresToolExecution_triggers_on_research_verb() {
        assertThat(CompanionActor.requiresToolExecution(
                "research the history of MUDs")).isTrue();
    }

    @Test
    void requiresToolExecution_triggers_on_find_me() {
        assertThat(CompanionActor.requiresToolExecution(
                "find me articles about Claude Code")).isTrue();
    }

    // --- Fix B: prose-follows-outcome (2026-07-06) ---
    // The long-term fix: prose never asserts action status; the resolved outcome
    // is voiced through the 4B layer. narratesOwnOutcome gates which actions have
    // their speculative pre-action prose suppressed; resolveVoicedOutcome is the
    // MUST-contain safety-net that keeps the voiced line honest.

    @Test
    void narratesOwnOutcome_gates_create_room() {
        assertThat(CompanionActor.narratesOwnOutcome(
                new ActionParser.AgentAction.CreateRoom(
                        "Book Nook", "cozy", java.util.List.of(), null, null)))
                .as("create_room voices its real outcome => prose suppressed").isTrue();
    }

    @Test
    void narratesOwnOutcome_leaves_non_outcome_actions_alone() {
        // TellAgent / GoToRoom speak conversationally; their prose is NOT suppressed
        assertThat(CompanionActor.narratesOwnOutcome(
                new ActionParser.AgentAction.TellAgent("Chief", "hello"))).isFalse();
        assertThat(CompanionActor.narratesOwnOutcome(
                new ActionParser.AgentAction.GoToRoom("workshop", "curious"))).isFalse();
    }

    @Test
    void resolveVoicedOutcome_keeps_voiced_line_when_pinned_value_survives() {
        assertThat(CompanionActor.resolveVoicedOutcome(
                "  All set — the Book Nook is ready with a way home.  ",
                "You just created the room \"Book Nook\".", "Book Nook"))
                .isEqualTo("All set — the Book Nook is ready with a way home.");
    }

    @Test
    void resolveVoicedOutcome_falls_back_to_truth_when_pinned_value_dropped() {
        // 4B paraphrased away the room name → speak the deterministic fact instead
        assertThat(CompanionActor.resolveVoicedOutcome(
                "All set — your new space is ready!",
                "You just created the room \"Book Nook\".", "Book Nook"))
                .isEqualTo("You just created the room \"Book Nook\".");
    }

    @Test
    void resolveVoicedOutcome_falls_back_when_inference_empty() {
        assertThat(CompanionActor.resolveVoicedOutcome(
                null, "You just created the room \"Book Nook\".", "Book Nook"))
                .isEqualTo("You just created the room \"Book Nook\".");
        assertThat(CompanionActor.resolveVoicedOutcome(
                "   ", "raw fact", "Book Nook")).isEqualTo("raw fact");
    }

    @Test
    void resolveVoicedOutcome_no_guard_when_required_value_null() {
        // failure lines pass requiredValue=null → the voiced line is used as-is
        assertThat(CompanionActor.resolveVoicedOutcome(
                "That didn't work — the name was already taken.",
                "You tried to create a room but it didn't work: name taken", null))
                .isEqualTo("That didn't work — the name was already taken.");
    }

    // --- isShortFormCreative — keeps short creative tasks inline ---

    @Test
    void isShortFormCreative_recognizes_short_poem() {
        assertThat(CompanionActor.isShortFormCreative(
                "write me a short poem about the stars")).isTrue();
    }

    @Test
    void isShortFormCreative_recognizes_haiku() {
        assertThat(CompanionActor.isShortFormCreative(
                "write me a haiku about rain")).isTrue();
    }

    @Test
    void isShortFormCreative_recognizes_unqualified_poem() {
        // "poem" alone defaults to inline — poems are short by default
        assertThat(CompanionActor.isShortFormCreative(
                "can you write a poem about morning light")).isTrue();
    }

    @Test
    void isShortFormCreative_recognizes_quick_song() {
        assertThat(CompanionActor.isShortFormCreative(
                "write me a quick song lyric")).isTrue();
    }

    @Test
    void isShortFormCreative_long_qualifier_overrides() {
        // "long poem" → bunshin path, not inline
        assertThat(CompanionActor.isShortFormCreative(
                "write me a long epic poem about war")).isFalse();
        assertThat(CompanionActor.isShortFormCreative(
                "write me a detailed reflective letter")).isFalse();
    }

    @Test
    void isShortFormCreative_skips_long_form_letter() {
        // The canonical bunshin-target — long reflective letter
        assertThat(CompanionActor.isShortFormCreative(
                "take your time writing me a long reflective letter, I'll wait"))
                .isFalse();
    }

    @Test
    void isShortFormCreative_skips_non_creative_request() {
        // No creative-form word + no short qualifier → not short-form creative
        assertThat(CompanionActor.isShortFormCreative(
                "write up the meeting notes from yesterday")).isFalse();
    }

    @Test
    void isShortFormCreative_skips_long_message() {
        // Long messages are likely substantive even if they mention "poem"
        var longMsg = "I've been thinking a lot about my grandmother lately and "
                + "the way she used to recite long passages of Yeats in the kitchen "
                + "while she cooked, and I want to write something that captures that, "
                + "a poem that has the weight of her voice and the rhythm of her hands "
                + "moving in the early morning light";
        assertThat(longMsg.length()).isGreaterThan(200);
        assertThat(CompanionActor.isShortFormCreative(longMsg)).isFalse();
    }

    @Test
    void isShortFormCreative_handles_null_and_blank() {
        assertThat(CompanionActor.isShortFormCreative(null)).isFalse();
        assertThat(CompanionActor.isShortFormCreative("")).isFalse();
    }

    // --- extractUserTellContent — strips MCP/relay wrappings for triage ---

    @Test
    void extractUserTellContent_unwraps_message_from_bracket() {
        assertThat(CompanionActor.extractUserTellContent("[message from claude: hey]"))
                .isEqualTo("hey");
    }

    @Test
    void extractUserTellContent_strips_trailing_system_hint_line() {
        // This is the live bug — MCP tell appended a mid-conversation gate that
        // pushed "hey" (ROUTINE) into COMPLEX triage.
        var wrapped = "[message from claude: hey]\n"
                + "[You are mid-conversation with claude — only greet if 30+s of silence]";
        assertThat(CompanionActor.extractUserTellContent(wrapped)).isEqualTo("hey");
    }

    @Test
    void extractUserTellContent_unwraps_from_prefix() {
        assertThat(CompanionActor.extractUserTellContent("[from operator] what's up"))
                .isEqualTo("what's up");
    }

    @Test
    void extractUserTellContent_returns_input_when_no_wrapping() {
        assertThat(CompanionActor.extractUserTellContent("hey"))
                .isEqualTo("hey");
        assertThat(CompanionActor.extractUserTellContent("research quantum computing"))
                .isEqualTo("research quantum computing");
    }

    @Test
    void extractUserTellContent_handles_null_and_blank() {
        assertThat(CompanionActor.extractUserTellContent(null)).isEqualTo("");
        assertThat(CompanionActor.extractUserTellContent("")).isEqualTo("");
        assertThat(CompanionActor.extractUserTellContent("   ")).isEqualTo("");
    }

    // --- #425 voice-pass entity extraction ---

    @Test
    void extractRequiredEntities_captures_proper_nouns_numbers_urls() {
        var entities = CompanionActor.extractRequiredEntities(
                "I told Masumi about the 14 templates at https://wyrdsekai.local/list");
        assertThat(entities).contains("Masumi", "14", "https://wyrdsekai.local/list");
    }

    @Test
    void extractRequiredEntities_skips_common_sentence_starters() {
        var entities = CompanionActor.extractRequiredEntities(
                "The Masumi I know would not say that. This is just my read.");
        assertThat(entities).contains("Masumi");
        assertThat(entities).doesNotContain("The", "This");
    }

    @Test
    void extractRequiredEntities_skips_reformattable_acronyms() {
        // Live-probe regression — `URL` as a 3-char all-caps acronym was
        // false-tripping the entity guard, dropping legitimate paraphrases.
        // Tightened 2026-04-24 to a denylist of common technical acronyms.
        var entities = CompanionActor.extractRequiredEntities(
                "Fetch the URL and parse the JSON. The API returns CSV.");
        assertThat(entities).doesNotContain("URL", "JSON", "API", "CSV");
    }

    @Test
    void extractRequiredEntities_preserves_panksepp_drive_names() {
        // Drive names are 4-7 chars all-caps but they ARE meaningful tokens
        // — paraphrases shouldn't drop them. The denylist approach (rather
        // than length cutoff) keeps these intact.
        var entities = CompanionActor.extractRequiredEntities(
                "PLAY is high. SEEKING is moderate. CARE drives the response.");
        assertThat(entities).contains("PLAY", "SEEKING", "CARE");
    }

    @Test
    void extractRequiredEntities_handles_null_and_blank() {
        assertThat(CompanionActor.extractRequiredEntities(null)).isEmpty();
        assertThat(CompanionActor.extractRequiredEntities("")).isEmpty();
        assertThat(CompanionActor.extractRequiredEntities("   ")).isEmpty();
    }

    // --- #35 number-with-unit preservation (weather temps etc.) ---

    @Test
    void extractRequiredNumbers_captures_unit_suffixed_temperatures() {
        // The bare-number regex in extractRequiredEntities cannot see "58F"/"85F"
        // (no word boundary between digit and unit), which is exactly the shape a
        // weather reply lives or dies on. extractRequiredNumbers must catch them.
        var nums = CompanionActor.extractRequiredNumbers("low 58F high 85F");
        assertThat(nums).contains("58F", "85F");
        // And entity extraction alone would MISS them — proves the gap this closes.
        assertThat(CompanionActor.extractRequiredEntities("low 58F high 85F"))
                .doesNotContain("58F", "85F");
    }

    @Test
    void extractRequiredNumbers_captures_percent_currency_and_bare_multidigit() {
        var nums = CompanionActor.extractRequiredNumbers(
                "battery at 12% cost $58 across 2026 units 3.5GB each");
        assertThat(nums).contains("12%", "$58", "2026", "3.5GB");
    }

    @Test
    void extractRequiredNumbers_skips_bare_single_digits() {
        // A lone single digit is rarely load-bearing and would force noisy raw
        // fallbacks; only 2+ digit bare numbers (or unit-suffixed) are required.
        var nums = CompanionActor.extractRequiredNumbers("a 3 day trip");
        assertThat(nums).doesNotContain("3");
    }

    @Test
    void extractRequiredNumbers_handles_null_and_blank() {
        assertThat(CompanionActor.extractRequiredNumbers(null)).isEmpty();
        assertThat(CompanionActor.extractRequiredNumbers("")).isEmpty();
        assertThat(CompanionActor.extractRequiredNumbers("no digits here")).isEmpty();
    }

    // --- #35 strict voice-guard decision (chooseVoicedLine) ---

    @Test
    void chooseVoicedLine_falls_back_to_raw_when_polish_drops_a_temperature() {
        // The canonical #35 case: a weather draft whose polish silently drops
        // "85F" must NOT be spoken — the raw draft is delivered so the fact
        // survives. Required facts are built exactly as the runtime builds them.
        var draft = "The forecast: low 58F, high 85F, clear skies.";
        var required = new java.util.LinkedHashSet<String>(
                CompanionActor.extractRequiredEntities(draft));
        required.addAll(CompanionActor.extractRequiredNumbers(draft));
        var polish = "Looks like a low around 58F today with clear skies.";  // 85F dropped
        assertThat(required).contains("85F");
        assertThat(CompanionActor.chooseVoicedLine(draft, polish, required))
                .isEqualTo(draft);
    }

    @Test
    void chooseVoicedLine_keeps_polish_when_every_fact_survives() {
        var draft = "The forecast: low 58F, high 85F, clear skies.";
        var required = new java.util.LinkedHashSet<String>(
                CompanionActor.extractRequiredEntities(draft));
        required.addAll(CompanionActor.extractRequiredNumbers(draft));
        var polish = "It'll dip to 58F overnight and climb to 85F under clear skies.";
        assertThat(CompanionActor.chooseVoicedLine(draft, polish, required))
                .isEqualTo(polish);
    }

    @Test
    void chooseVoicedLine_clean_line_passes_verbatim() {
        // A clean draft the 4B echoed unchanged is delivered verbatim.
        var line = "I've put the kettle on — it'll be a minute.";
        assertThat(CompanionActor.chooseVoicedLine(line, line, java.util.Set.of()))
                .isEqualTo(line);
    }

    @Test
    void chooseVoicedLine_blank_polish_falls_back_to_raw() {
        var draft = "Here is the answer you asked for.";
        assertThat(CompanionActor.chooseVoicedLine(draft, "", java.util.Set.of()))
                .isEqualTo(draft);
        assertThat(CompanionActor.chooseVoicedLine(draft, null, java.util.Set.of()))
                .isEqualTo(draft);
    }

    @Test
    void chooseVoicedLine_falls_back_when_polish_drops_a_pinned_action_verb() {
        // preserveFacts values (e.g. the action verb "crafted") are required
        // facts too — if polish paraphrases them away, the exact confirmation
        // is spoken instead.
        var draft = "I've crafted Zone Scryer. It's ready to use.";
        var required = java.util.Set.of("Zone Scryer", "crafted");
        var polish = "Your Zone Scryer is all set and ready to go!";  // "crafted" gone
        assertThat(CompanionActor.chooseVoicedLine(draft, polish, required))
                .isEqualTo(draft);
    }

    @Test
    void chooseVoicedLine_rejects_hallucinated_expansion() {
        var draft = "I crafted the Zone Scryer for you just now.";  // >=30 chars
        var polish = "I crafted the Zone Scryer for you just now, ".repeat(6)
                + "and oh how I have longed to tell you.";  // >2.5x, >120 chars
        assertThat(CompanionActor.chooseVoicedLine(draft, polish, java.util.Set.of()))
                .isEqualTo(draft);
    }

    @Test
    void cleanVoicePassReply_strips_code_fences() {
        assertThat(CompanionActor.cleanVoicePassReply("```\nSomething wise.\n```"))
                .isEqualTo("Something wise.");
    }

    @Test
    void cleanVoicePassReply_strips_wrapping_quotes() {
        assertThat(CompanionActor.cleanVoicePassReply("\"Something wise.\""))
                .isEqualTo("Something wise.");
    }

    @Test
    void cleanVoicePassReply_strips_meta_label_prefix() {
        assertThat(CompanionActor.cleanVoicePassReply("Paraphrased: It's a quiet morning."))
                .isEqualTo("It's a quiet morning.");
        assertThat(CompanionActor.cleanVoicePassReply("In your voice: I see."))
                .isEqualTo("I see.");
    }

    @Test
    void cleanVoicePassReply_preserves_multi_sentence() {
        var multi = "First sentence here. Second sentence here. Third closes it.";
        assertThat(CompanionActor.cleanVoicePassReply(multi))
                .isEqualTo(multi);
    }

    // ── explicit no-delegation guard (second-node 2026-07-11 #27) ────────────

    @Test
    void explicitlyDeclinesDelegation_catches_direct_negations() {
        assertThat(CompanionActor.explicitlyDeclinesDelegation(
                "write me a long reflective letter — no delegating, please")).isTrue();
        assertThat(CompanionActor.explicitlyDeclinesDelegation(
                "don't delegate this, I want it from you")).isTrue();
        assertThat(CompanionActor.explicitlyDeclinesDelegation(
                "do NOT delegate — handle it in your own words")).isTrue();
        assertThat(CompanionActor.explicitlyDeclinesDelegation(
                "please write this yourself, no bunshin")).isTrue();
        assertThat(CompanionActor.explicitlyDeclinesDelegation(
                "do it yourself, take your time")).isTrue();
        assertThat(CompanionActor.explicitlyDeclinesDelegation(
                "I'd rather you do this without delegating it")).isTrue();
    }

    @Test
    void explicitlyDeclinesDelegation_skips_ordinary_requests() {
        assertThat(CompanionActor.explicitlyDeclinesDelegation(
                "write me a long reflective letter, I'll wait")).isFalse();
        assertThat(CompanionActor.explicitlyDeclinesDelegation(
                "research the history of tea ceremonies")).isFalse();
        assertThat(CompanionActor.explicitlyDeclinesDelegation(
                "delegate this to whoever is fastest")).isFalse();
        assertThat(CompanionActor.explicitlyDeclinesDelegation(null)).isFalse();
        assertThat(CompanionActor.explicitlyDeclinesDelegation("  ")).isFalse();
    }
}
