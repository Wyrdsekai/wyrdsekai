package org.wyrdsekai.server.ws;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.server.ws.InputParser.ParsedInput;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for MUD-style input parsing (emote, tell, whisper, say shorthands).
 * Validates the prefix parsing rules extracted from WyrdWebSocket's Say handler.
 */
class InputParsingTest {

    // ── Emote shorthand: : and ; prefixes ──

    @Test
    void colon_prefix_is_emote() {
        var result = InputParser.parse(":smiles");
        assertThat(result).isInstanceOf(ParsedInput.Emote.class);
        assertThat(((ParsedInput.Emote) result).text()).isEqualTo("smiles");
    }

    @Test
    void semicolon_prefix_is_emote() {
        var result = InputParser.parse(";waves");
        assertThat(result).isInstanceOf(ParsedInput.Emote.class);
        assertThat(((ParsedInput.Emote) result).text()).isEqualTo("waves");
    }

    @Test
    void colon_emote_trims_whitespace() {
        var result = InputParser.parse(":  grins widely  ");
        assertThat(result).isInstanceOf(ParsedInput.Emote.class);
        assertThat(((ParsedInput.Emote) result).text()).isEqualTo("grins widely");
    }

    // ── Tell shorthand: > prefix ──

    @Test
    void greater_than_prefix_is_tell() {
        var result = InputParser.parse(">Ma hello");
        assertThat(result).isInstanceOf(ParsedInput.Tell.class);
        var tell = (ParsedInput.Tell) result;
        assertThat(tell.target()).isEqualTo("Ma");
        assertThat(tell.text()).isEqualTo("hello");
    }

    @Test
    void greater_than_with_multi_word_message() {
        var result = InputParser.parse(">Bob how are you today?");
        assertThat(result).isInstanceOf(ParsedInput.Tell.class);
        var tell = (ParsedInput.Tell) result;
        assertThat(tell.target()).isEqualTo("Bob");
        assertThat(tell.text()).isEqualTo("how are you today?");
    }

    // ── Full word: emote ──

    @Test
    void emote_word_is_emote() {
        var result = InputParser.parse("emote dances");
        assertThat(result).isInstanceOf(ParsedInput.Emote.class);
        assertThat(((ParsedInput.Emote) result).text()).isEqualTo("dances");
    }

    @Test
    void emote_word_case_insensitive() {
        var result = InputParser.parse("EMOTE laughs");
        assertThat(result).isInstanceOf(ParsedInput.Emote.class);
        assertThat(((ParsedInput.Emote) result).text()).isEqualTo("laughs");
    }

    @Test
    void emote_word_multi_word_text() {
        var result = InputParser.parse("emote dances gracefully across the room");
        assertThat(result).isInstanceOf(ParsedInput.Emote.class);
        assertThat(((ParsedInput.Emote) result).text()).isEqualTo("dances gracefully across the room");
    }

    // ── Full word: tell ──

    @Test
    void tell_word_is_tell() {
        var result = InputParser.parse("tell Ma hi");
        assertThat(result).isInstanceOf(ParsedInput.Tell.class);
        var tell = (ParsedInput.Tell) result;
        assertThat(tell.target()).isEqualTo("Ma");
        assertThat(tell.text()).isEqualTo("hi");
    }

    @Test
    void tell_word_case_insensitive() {
        var result = InputParser.parse("TELL Alice hello there");
        assertThat(result).isInstanceOf(ParsedInput.Tell.class);
        var tell = (ParsedInput.Tell) result;
        assertThat(tell.target()).isEqualTo("Alice");
        assertThat(tell.text()).isEqualTo("hello there");
    }

    // ── Full word: whisper ──

    @Test
    void whisper_word_is_whisper() {
        var result = InputParser.parse("whisper Ma secret");
        assertThat(result).isInstanceOf(ParsedInput.Whisper.class);
        var whisper = (ParsedInput.Whisper) result;
        assertThat(whisper.target()).isEqualTo("Ma");
        assertThat(whisper.text()).isEqualTo("secret");
    }

    @Test
    void whisper_word_case_insensitive() {
        var result = InputParser.parse("WHISPER Bob keep this between us");
        assertThat(result).isInstanceOf(ParsedInput.Whisper.class);
        var whisper = (ParsedInput.Whisper) result;
        assertThat(whisper.target()).isEqualTo("Bob");
        assertThat(whisper.text()).isEqualTo("keep this between us");
    }

    // ── Plain say ──

    @Test
    void plain_text_is_say() {
        var result = InputParser.parse("hello");
        assertThat(result).isInstanceOf(ParsedInput.Say.class);
        assertThat(((ParsedInput.Say) result).text()).isEqualTo("hello");
    }

    @Test
    void say_prefix_is_say() {
        var result = InputParser.parse("say hello");
        assertThat(result).isInstanceOf(ParsedInput.Say.class);
        assertThat(((ParsedInput.Say) result).text()).isEqualTo("hello");
    }

    @Test
    void say_prefix_strips_keyword() {
        var result = InputParser.parse("say how are you?");
        assertThat(result).isInstanceOf(ParsedInput.Say.class);
        assertThat(((ParsedInput.Say) result).text()).isEqualTo("how are you?");
    }

    // ── Edge cases ──

    @Test
    void empty_emote_is_say() {
        // ":" alone → falls through to say
        var result = InputParser.parse(":");
        assertThat(result).isInstanceOf(ParsedInput.Say.class);
        assertThat(((ParsedInput.Say) result).text()).isEqualTo(":");
    }

    @Test
    void semicolon_alone_is_say() {
        var result = InputParser.parse(";");
        assertThat(result).isInstanceOf(ParsedInput.Say.class);
        assertThat(((ParsedInput.Say) result).text()).isEqualTo(";");
    }

    @Test
    void tell_no_text_is_say() {
        // ">Ma" without text → falls through to say
        var result = InputParser.parse(">Ma");
        assertThat(result).isInstanceOf(ParsedInput.Say.class);
        assertThat(((ParsedInput.Say) result).text()).isEqualTo(">Ma");
    }

    @Test
    void tell_word_no_text_is_say() {
        // "tell Ma" without message text → falls through to say
        var result = InputParser.parse("tell Ma");
        assertThat(result).isInstanceOf(ParsedInput.Say.class);
        assertThat(((ParsedInput.Say) result).text()).isEqualTo("tell Ma");
    }

    @Test
    void whisper_word_no_text_is_say() {
        // "whisper Ma" without message text → falls through to say
        var result = InputParser.parse("whisper Ma");
        assertThat(result).isInstanceOf(ParsedInput.Say.class);
        assertThat(((ParsedInput.Say) result).text()).isEqualTo("whisper Ma");
    }

    @Test
    void null_input_is_say() {
        var result = InputParser.parse(null);
        assertThat(result).isInstanceOf(ParsedInput.Say.class);
    }

    @Test
    void blank_input_is_say() {
        var result = InputParser.parse("   ");
        assertThat(result).isInstanceOf(ParsedInput.Say.class);
    }

    @Test
    void emote_just_keyword_no_text_is_say() {
        // "emote " with trailing space but no actual text
        var result = InputParser.parse("emote ");
        assertThat(result).isInstanceOf(ParsedInput.Say.class);
    }

    @Test
    void multiline_text_is_say() {
        var result = InputParser.parse("hello\nworld");
        assertThat(result).isInstanceOf(ParsedInput.Say.class);
        assertThat(((ParsedInput.Say) result).text()).isEqualTo("hello\nworld");
    }

    @Test
    void emote_with_special_characters() {
        var result = InputParser.parse(":grins & winks at *everyone*!");
        assertThat(result).isInstanceOf(ParsedInput.Emote.class);
        assertThat(((ParsedInput.Emote) result).text()).isEqualTo("grins & winks at *everyone*!");
    }
}
