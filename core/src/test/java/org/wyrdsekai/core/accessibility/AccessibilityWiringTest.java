package org.wyrdsekai.core.accessibility;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for accessibility wiring (§N7).
 * Covers OutputAdapter screen reader tags, adaptation pipeline,
 * and integration with AccessibilityPreferences profiles.
 */
class AccessibilityWiringTest {

    private final OutputAdapter adapter = new OutputAdapter();

    // ── Screen Reader Tags ({A}...{a}) ──

    @Nested
    class ScreenReaderTagTests {

        @Test
        void screen_reader_tags_kept_for_screen_reader_users() {
            var prefs = AccessibilityPreferences.screenReaderProfile();
            var input = "A gentle hum fills the air. {A}You are in a large circular room.{a} Light pulses.";
            var result = adapter.adapt(input, prefs);
            assertTrue(result.contains("You are in a large circular room."));
            assertFalse(result.contains("{A}"));
            assertFalse(result.contains("{a}"));
        }

        @Test
        void screen_reader_tags_stripped_for_sighted_users() {
            var prefs = AccessibilityPreferences.defaults();
            var input = "A gentle hum fills the air. {A}You are in a large circular room.{a} Light pulses.";
            var result = adapter.adapt(input, prefs);
            assertFalse(result.contains("You are in a large circular room."));
            assertTrue(result.contains("A gentle hum fills the air."));
            assertTrue(result.contains("Light pulses."));
        }

        @Test
        void multiple_screen_reader_tags() {
            var prefs = AccessibilityPreferences.screenReaderProfile();
            var input = "Prose. {A}SR text 1.{a} More prose. {A}SR text 2.{a} End.";
            var result = adapter.adapt(input, prefs);
            assertTrue(result.contains("SR text 1."));
            assertTrue(result.contains("SR text 2."));
        }

        @Test
        void multiline_screen_reader_tags() {
            var prefs = AccessibilityPreferences.defaults();
            var input = "Before. {A}Line one.\nLine two.{a} After.";
            var result = adapter.adapt(input, prefs);
            assertFalse(result.contains("Line one."));
            assertFalse(result.contains("Line two."));
            assertTrue(result.contains("Before."));
            assertTrue(result.contains("After."));
        }

        @Test
        void no_tags_unchanged() {
            var prefs = AccessibilityPreferences.defaults();
            var input = "Just normal text with no tags.";
            assertEquals(input, adapter.adapt(input, prefs));
        }

        @Test
        void null_prefs_strips_tags() {
            var input = "Text {A}hidden{a} visible.";
            var result = adapter.adapt(input, null);
            assertFalse(result.contains("hidden"));
            assertTrue(result.contains("Text"));
            assertTrue(result.contains("visible."));
        }
    }

    // ── OutputAdapter Pipeline ──

    @Nested
    class AdaptationPipelineTests {

        @Test
        void null_input_returns_null() {
            assertNull(adapter.adapt(null, AccessibilityPreferences.defaults()));
        }

        @Test
        void empty_input_returns_empty() {
            assertEquals("", adapter.adapt("", AccessibilityPreferences.defaults()));
        }

        @Test
        void emoji_stripped_when_noEmoji() {
            var prefs = new AccessibilityPreferences(
                false, false, false, false, false, false, false, true,
                false, 1.0, "default", false, "auto", false, 0, Set.of());
            var result = adapter.adapt("Hello 😊 world", prefs);
            assertFalse(result.contains("😊"));
            assertTrue(result.contains("Hello"));
            assertTrue(result.contains("world"));
        }

        @Test
        void markdown_stripped_for_voice() {
            var prefs = AccessibilityPreferences.voicePrimaryProfile();
            var result = adapter.adapt("**bold** and *italic* text", prefs);
            assertTrue(result.contains("bold"));
            assertTrue(result.contains("italic"));
            assertFalse(result.contains("**"));
            assertFalse(result.contains("*italic*"));
        }

        @Test
        void short_messages_truncated() {
            var prefs = AccessibilityPreferences.cognitiveProfile();
            var input = "First sentence. Second sentence. Third sentence. Fourth sentence. Fifth sentence.";
            var result = adapter.adapt(input, prefs);
            assertTrue(result.contains("First sentence."));
            assertTrue(result.contains("Second sentence."));
            assertTrue(result.contains("Third sentence."));
            assertFalse(result.contains("Fourth sentence."));
        }

        @Test
        void combined_adaptations() {
            // Screen reader + no emoji + short messages
            var prefs = new AccessibilityPreferences(
                true, true, false, false, false, false, true, true,
                false, 1.0, "default", false, "auto", false, 0, Set.of());
            var input = "😊 {A}Accessible text.{a} **Bold** statement. Second. Third. Fourth.";
            var result = adapter.adapt(input, prefs);
            assertTrue(result.contains("Accessible text."));
            assertFalse(result.contains("😊"));
            assertFalse(result.contains("Fourth."));
        }
    }

    // ── Audio Cues ──

    @Nested
    class AudioCueTests {

        @Test
        void topology_cue_exists() {
            var cue = adapter.audioCue("topology");
            assertFalse(cue.isEmpty());
            assertTrue(cue.contains("changed"));
        }

        @Test
        void standard_cues_not_empty() {
            for (var type : List.of("enter", "leave", "message", "alert", "error", "companion")) {
                assertFalse(adapter.audioCue(type).isEmpty(), "Missing cue for: " + type);
            }
        }

        @Test
        void unknown_cue_empty() {
            assertEquals("", adapter.audioCue("nonexistent"));
        }
    }

    // ── Preferences ──

    @Nested
    class PreferenceTests {

        @Test
        void prompt_addendum_screen_reader() {
            var prefs = AccessibilityPreferences.screenReaderProfile();
            var addendum = prefs.promptAddendum();
            assertTrue(addendum.contains("screen reader"));
            assertTrue(addendum.contains("simple, clear language"));
        }

        @Test
        void prompt_addendum_voice() {
            var prefs = AccessibilityPreferences.voicePrimaryProfile();
            var addendum = prefs.promptAddendum();
            assertTrue(addendum.contains("speech"));
        }

        @Test
        void prompt_addendum_cognitive() {
            var prefs = AccessibilityPreferences.cognitiveProfile();
            var addendum = prefs.promptAddendum();
            assertTrue(addendum.contains("brief"));
        }

        @Test
        void prompt_addendum_default_empty() {
            var prefs = AccessibilityPreferences.defaults();
            assertEquals("", prefs.promptAddendum());
        }

        @Test
        void has_accommodations() {
            assertFalse(AccessibilityPreferences.defaults().hasAccommodations());
            assertTrue(AccessibilityPreferences.screenReaderProfile().hasAccommodations());
            assertTrue(AccessibilityPreferences.voicePrimaryProfile().hasAccommodations());
            assertTrue(AccessibilityPreferences.cognitiveProfile().hasAccommodations());
        }
    }
}
