package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an item says has to reach the person using it.
 *
 * <h2>The live failure</h2>
 * 2026-08-21, household node. The steward asked for a tool that queries the library and
 * "speaks out loud to the room a story based on what it found". The whole chain worked
 * this time — she routed it to the workshop, goose wrote a clean {@code library_keeper},
 * the bridge registered it, kept it, placed it, and she handed it over:
 *
 * <pre>
 *   mia: *hands library_keeper to operator* Queries the library for content and speaks a
 *        2-paragraph story about what it finds. Use it with — `use library_keeper` …
 *   &gt; use library_keeper details
 *   You use the library_keeper.
 * </pre>
 *
 * <p>Two separate silences, both ours:
 *
 * <ol>
 *   <li>The script returned {@code &#123; ok: true, summary: "…" &#125;} — the shape the
 *       items-as-tools preamble literally ends with — and
 *       {@link ItemScriptResponse#extractText} read only {@code response}/{@code text}/
 *       {@code error}. So a compliant item's answer was dropped and replaced with a stock
 *       acknowledgment, on every surface at once.</li>
 *   <li>The script also called {@code world.agent.speak(story)}, which for a PLAYER-held
 *       item hit {@link VisitorItemProvider}'s no-op. The room stayed quiet.</li>
 * </ol>
 */
class AToolThatSpeaksIsHeardTest {

    /** Verbatim from the `details` branch of the item goose actually wrote. */
    private static final String DETAILS_SUMMARY =
        "This tool searches the library for content and speaks a story about what it"
        + " finds. Pass an empty query to search broadly, or pass a topic string to"
        + " focus the search.";

    @Test
    void the_summary_field_the_contract_teaches_is_what_the_person_sees() {
        var result = Map.<String, Object>of("ok", true, "summary", DETAILS_SUMMARY);
        assertThat(ItemScriptResponse.extractText(result, "library_keeper"))
            .isEqualTo(DETAILS_SUMMARY);
    }

    @Test
    void response_still_wins_over_summary() {
        var result = new LinkedHashMap<String, Object>();
        result.put("summary", "second");
        result.put("response", "first");
        assertThat(ItemScriptResponse.extractText(result, "x")).isEqualTo("first");
    }

    @Test
    void message_is_read_too() {
        assertThat(ItemScriptResponse.extractText(
            Map.of("message", "spoken"), "x")).isEqualTo("spoken");
    }

    /** A blank field is not an answer — keep looking rather than printing emptiness. */
    @Test
    void a_blank_field_does_not_count_as_the_answer() {
        var result = new LinkedHashMap<String, Object>();
        result.put("response", "   ");
        result.put("summary", "the real answer");
        assertThat(ItemScriptResponse.extractText(result, "x"))
            .isEqualTo("the real answer");
    }

    @Test
    void a_script_that_says_nothing_still_gets_an_acknowledgment() {
        assertThat(ItemScriptResponse.extractText(Map.of(), "lens"))
            .isEqualTo("You use the lens.");
        assertThat(ItemScriptResponse.extractText(null, "lens"))
            .isEqualTo("You use the lens.");
    }

    @Test
    void an_error_is_surfaced_when_there_is_nothing_else() {
        assertThat(ItemScriptResponse.extractText(
            Map.of("error", "Script error: boom"), "lens"))
            .isEqualTo("Script error: boom");
    }

    /**
     * {@code world.agent.speak} from a player-held item used to vanish. It is the first
     * thing the preamble's Tier 3 list offers, and it was the entire point of the tool
     * he asked for.
     */
    @Test
    void a_player_held_item_can_speak_into_the_room() {
        var heard = new AtomicReference<String>();
        var provider = new VisitorItemProvider("home", "home")
            .withRoomVoice(heard::set);
        provider.agentSpeak("The library remembers a story about snow.");
        assertThat(heard.get()).isEqualTo("The library remembers a story about snow.");
    }

    /** With nowhere to speak it stays silent rather than throwing — a foreign zone. */
    @Test
    void with_no_room_wired_it_is_silent_not_broken() {
        var provider = new VisitorItemProvider("far", "far");
        provider.agentSpeak("into the void");
        assertThat(provider).isNotNull();
    }

    /** Blank speech is not speech. */
    @Test
    void blank_speech_is_not_broadcast() {
        var heard = new AtomicReference<String>();
        new VisitorItemProvider("home", "home")
            .withRoomVoice(heard::set)
            .agentSpeak("   ");
        assertThat(heard.get()).isNull();
    }
}
