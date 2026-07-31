package org.wyrdsekai.core.accessibility;

import org.junit.jupiter.api.*;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §103 — Accessibility.
 * AccessibilityPreferences, OutputAdapter, VoiceEngineConfig.
 */
class AccessibilityWaveTest {

    // ── AccessibilityPreferences ──

    @Nested
    class AccessibilityPreferencesTests {

        @Test
        void defaults_have_no_accommodations() {
            var prefs = AccessibilityPreferences.defaults();
            assertFalse(prefs.hasAccommodations());
            assertTrue(prefs.promptAddendum().isEmpty());
        }

        @Test
        void screen_reader_profile_has_accommodations() {
            var prefs = AccessibilityPreferences.screenReaderProfile();
            assertTrue(prefs.hasAccommodations());
            assertTrue(prefs.screenReader());
            assertTrue(prefs.plainLanguage());
            assertTrue(prefs.noEmoji());
            assertTrue(prefs.ttsEnabled());
        }

        @Test
        void voice_primary_profile() {
            var prefs = AccessibilityPreferences.voicePrimaryProfile();
            assertTrue(prefs.voicePrimary());
            assertTrue(prefs.ttsEnabled());
            assertTrue(prefs.sttEnabled());
            assertTrue(prefs.shortMessages());
        }

        @Test
        void cognitive_profile() {
            var prefs = AccessibilityPreferences.cognitiveProfile();
            assertTrue(prefs.plainLanguage());
            assertTrue(prefs.shortMessages());
            assertTrue(prefs.noEmoji());
            assertEquals(60, prefs.sessionTimeoutMinutes());
        }

        @Test
        void prompt_addendum_includes_plain_language() {
            var prefs = AccessibilityPreferences.cognitiveProfile();
            var addendum = prefs.promptAddendum();
            assertTrue(addendum.contains("simple"));
            assertTrue(addendum.contains("brief"));
        }

        @Test
        void prompt_addendum_includes_screen_reader_guidance() {
            var prefs = AccessibilityPreferences.screenReaderProfile();
            var addendum = prefs.promptAddendum();
            assertTrue(addendum.contains("screen reader"));
        }

        @Test
        void prompt_addendum_includes_voice_guidance() {
            var prefs = AccessibilityPreferences.voicePrimaryProfile();
            var addendum = prefs.promptAddendum();
            assertTrue(addendum.contains("speech"));
        }

        @Test
        void with_tts_creates_new_instance() {
            var original = AccessibilityPreferences.defaults();
            var withTts = original.withTts(true, 1.5, "af_bella");
            assertTrue(withTts.ttsEnabled());
            assertEquals(1.5, withTts.ttsRate());
            assertEquals("af_bella", withTts.ttsVoice());
            assertFalse(original.ttsEnabled()); // Original unchanged
        }

        @Test
        void with_stt_creates_new_instance() {
            var original = AccessibilityPreferences.defaults();
            var withStt = original.withStt(true, "ja");
            assertTrue(withStt.sttEnabled());
            assertEquals("ja", withStt.sttLanguage());
        }

        @Test
        void extra_preferences() {
            var prefs = new AccessibilityPreferences(
                false, false, false, false, false, false, false, false,
                false, 1.0, "default", false, "auto", false, 0,
                Set.of("single-switch", "scanning-input"));
            assertTrue(prefs.hasExtra("single-switch"));
            assertFalse(prefs.hasExtra("sip-and-puff"));
            assertTrue(prefs.hasAccommodations());
        }
    }

    // ── OutputAdapter ──

    @Nested
    class OutputAdapterTests {

        @Test
        void no_adaptation_with_defaults() {
            var adapter = new OutputAdapter();
            var prefs = AccessibilityPreferences.defaults();
            var input = "Hello! **Bold text** and *italic*";
            assertEquals(input, adapter.adapt(input, prefs));
        }

        @Test
        void strip_emoji() {
            var adapter = new OutputAdapter();
            var stripped = adapter.stripEmoji("Hello 😊 world 🌍!");
            assertFalse(stripped.contains("😊"));
            assertFalse(stripped.contains("🌍"));
            assertTrue(stripped.contains("Hello"));
            assertTrue(stripped.contains("world"));
        }

        @Test
        void strip_markdown_for_voice() {
            var adapter = new OutputAdapter();
            var result = adapter.stripMarkdownForVoice("**bold** and *italic*");
            assertEquals("bold and italic", result);
        }

        @Test
        void strip_markdown_headers() {
            var adapter = new OutputAdapter();
            var result = adapter.stripMarkdownForVoice("## Section Title");
            assertTrue(result.contains("Section Title"));
            assertFalse(result.contains("##"));
        }

        @Test
        void truncate_to_short() {
            var adapter = new OutputAdapter();
            var longText = "First sentence. Second sentence. Third sentence. Fourth sentence. Fifth sentence.";
            var truncated = adapter.truncateToShort(longText);
            assertTrue(truncated.contains("First"));
            assertTrue(truncated.contains("Third"));
            assertFalse(truncated.contains("Fourth"));
        }

        @Test
        void short_text_not_truncated() {
            var adapter = new OutputAdapter();
            var shortText = "Just one sentence.";
            assertEquals(shortText, adapter.truncateToShort(shortText));
        }

        @Test
        void full_adaptation_chain() {
            var adapter = new OutputAdapter();
            var prefs = new AccessibilityPreferences(
                false, false, false, false, true, false, true, true,
                false, 1.0, "default", false, "auto", false, 0, Set.of());

            var input = "Hello 😊! **Important news**: here is something. Second thing. Third thing. Fourth thing. Fifth thing.";
            var result = adapter.adapt(input, prefs);

            assertFalse(result.contains("😊"));      // emoji stripped
            assertFalse(result.contains("**"));       // markdown stripped
            assertFalse(result.contains("Fifth"));    // truncated
        }

        @Test
        void null_input_passthrough() {
            var adapter = new OutputAdapter();
            assertNull(adapter.adapt(null, AccessibilityPreferences.defaults()));
        }

        @Test
        void null_prefs_passthrough() {
            var adapter = new OutputAdapter();
            assertEquals("hello", adapter.adapt("hello", null));
        }

        @Test
        void audio_cue_descriptions() {
            var adapter = new OutputAdapter();
            assertTrue(adapter.audioCue("enter").contains("entered"));
            assertTrue(adapter.audioCue("companion").contains("companion"));
            assertTrue(adapter.audioCue("error").contains("wrong"));
            assertEquals("", adapter.audioCue("unknown-event"));
        }
    }

    // ── VoiceEngineConfig ──

    @Nested
    class VoiceEngineConfigTests {

        @Test
        void kokoro_default() {
            var config = VoiceEngineConfig.kokoroDefault();
            assertEquals("kokoro-82m", config.engineType());
            assertEquals("af_heart", config.defaultVoice());
            assertFalse(config.enabled());
        }

        @Test
        void disabled_config() {
            var config = VoiceEngineConfig.disabled();
            assertEquals("none", config.engineType());
            assertFalse(config.enabled());
        }

        @Test
        void deceased_voice_cloning_always_banned() {
            var config = VoiceEngineConfig.kokoroDefault();
            assertTrue(config.isDeceasedVoiceCloningBanned());
        }

        @Test
        void voice_clone_ban_enforced() {
            var config = new VoiceEngineConfig(
                "kokoro-82m", "path", "voice", 1.0, 1.0, "en", true,
                Set.of("deceased:john-doe"));
            assertFalse(config.canCloneVoice("deceased:john-doe"));
            assertTrue(config.canCloneVoice("active:jane"));
        }

        @Test
        void available_voices_non_empty() {
            assertTrue(VoiceEngineConfig.availableVoices().size() >= 10);
        }

        @Test
        void configure_from_preferences() {
            var config = VoiceEngineConfig.kokoroDefault();
            var prefs = AccessibilityPreferences.defaults()
                .withTts(true, 1.2, "af_bella");
            var configured = config.withUserPreferences(prefs);
            assertTrue(configured.enabled());
            assertEquals("af_bella", configured.defaultVoice());
            assertEquals(1.2, configured.defaultRate());
        }

        @Test
        void configure_disabled_when_tts_off() {
            var config = VoiceEngineConfig.kokoroDefault();
            var prefs = AccessibilityPreferences.defaults(); // tts disabled
            var configured = config.withUserPreferences(prefs);
            assertFalse(configured.enabled());
        }

        @Test
        void minimal_config() {
            var config = VoiceEngineConfig.minimal();
            assertEquals("en", config.language());
        }
    }

    // ── SituationalContext ──

    @Nested
    class SituationalContextTests {

        @Test
        void normal_context_no_changes() {
            var ctx = OutputAdapter.SituationalContext.normal();
            assertFalse(ctx.oneHanded());
            assertFalse(ctx.driving());
        }

        @Test
        void driving_enables_voice() {
            var ctx = new OutputAdapter.SituationalContext(false, false, false, true, false);
            var base = AccessibilityPreferences.defaults();
            var derived = ctx.derivePreferences(base);
            assertTrue(derived.ttsEnabled());
            assertTrue(derived.sttEnabled());
        }

        @Test
        void one_handed_enables_voice() {
            var ctx = new OutputAdapter.SituationalContext(true, false, false, false, false);
            var base = AccessibilityPreferences.defaults();
            var derived = ctx.derivePreferences(base);
            assertTrue(derived.sttEnabled());
        }
    }
}
