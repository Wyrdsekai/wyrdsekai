package org.wyrdsekai.core.room;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the pure pieces of {@link ThemedDescriptionService} — the
 * prompt builder, output sanitizer, and content hash. The bake path itself is
 * inference-backed (covered live), not exercised here.
 */
class ThemedDescriptionServiceTest {

    @Test
    void sanitize_stripsPreambleLine() {
        var raw = "Here is the rewritten description:\nA dim chamber hums with old magic.";
        assertThat(ThemedDescriptionService.sanitize(raw))
            .isEqualTo("A dim chamber hums with old magic.");
    }

    @Test
    void sanitize_stripsWrappingQuotes() {
        assertThat(ThemedDescriptionService.sanitize("\"A quiet grove at the path's end.\""))
            .isEqualTo("A quiet grove at the path's end.");
        assertThat(ThemedDescriptionService.sanitize("“A quiet grove at the path's end.”"))
            .isEqualTo("A quiet grove at the path's end.");
    }

    @Test
    void sanitize_rejectsTooShortOrBlank() {
        assertThat(ThemedDescriptionService.sanitize("")).isNull();
        assertThat(ThemedDescriptionService.sanitize("   ")).isNull();
        assertThat(ThemedDescriptionService.sanitize("nope")).isNull();   // < MIN_CHARS
    }

    @Test
    void sanitize_rejectsRunaway() {
        var huge = "x".repeat(2000);
        assertThat(ThemedDescriptionService.sanitize(huge)).isNull();
    }

    // --- Language guard (2026-07-07): reject the voice-4B's code-switch drift ---

    /** The actual Spanglish drift a live English study look produced. */
    private static final String SPANGLISH_DRIFT =
        "Tu room es un nodo cerrado — viejo leather chair frente al hearth, "
        + "heavy desk contra la pared con su agenda y bandeja. Crystal sphere en "
        + "el centro bombea telemetría; shelves contra la opuesta, pinboard al lado.";

    @Test
    void matchesLanguage_rejects_spanish_drift_when_english_requested() {
        assertThat(ThemedDescriptionService.matchesLanguage(SPANGLISH_DRIFT, "en"))
            .as("Spanglish drift must be rejected for an English room").isFalse();
    }

    @Test
    void matchesLanguage_accepts_real_english_for_english() {
        var english = "A worn leather chair faces a low hearth; a heavy desk stands "
            + "against one wall with a schedule board and correspondence tray.";
        assertThat(ThemedDescriptionService.matchesLanguage(english, "en")).isTrue();
    }

    @Test
    void matchesLanguage_rejects_japanese_when_english_requested() {
        assertThat(ThemedDescriptionService.matchesLanguage(
            "薄暗い部屋に古い魔法の低いうなりが満ちている。", "en")).isFalse();
    }

    @Test
    void matchesLanguage_accepts_target_when_it_matches() {
        assertThat(ThemedDescriptionService.matchesLanguage(SPANGLISH_DRIFT, "es")).isTrue();
        assertThat(ThemedDescriptionService.matchesLanguage(
            "薄暗い部屋に古い魔法の低いうなりが満ちている。", "ja")).isTrue();
        // Spanish requested but Japanese produced → still rejected
        assertThat(ThemedDescriptionService.matchesLanguage(
            "薄暗い部屋に古い魔法。", "es")).isFalse();
    }

    @Test
    void matchesLanguage_null_or_blank_is_permissive() {
        assertThat(ThemedDescriptionService.matchesLanguage(null, "en")).isTrue();
        assertThat(ThemedDescriptionService.matchesLanguage("  ", "en")).isTrue();
    }

    @Test
    void sanitize_keepsCleanProse() {
        var clean = "Brass fittings catch the lamplight, and a clockwork heart ticks beneath the floor.";
        assertThat(ThemedDescriptionService.sanitize(clean)).isEqualTo(clean);
    }

    @Test
    void hash_isStableAndContentSensitive() {
        var a = ThemedDescriptionService.hash("A small room.");
        var b = ThemedDescriptionService.hash("A small room.");
        var c = ThemedDescriptionService.hash("A small room. ");  // trailing space differs
        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a).hasSize(12);  // 6 bytes → 12 hex chars
    }

    @Test
    void systemPrompt_embedsStyleAndPreservationContract() {
        var sys = ThemedDescriptionService.buildSystemPrompt(ZoneAesthetic.arcane(), "en");
        assertThat(sys).contains("arcane scholar");          // the theme's stylePrompt
        assertThat(sys).contains("Preserve every physical fact");
        assertThat(sys).contains("do not invent new objects or exits");
        assertThat(sys).contains("Write in English.");      // language always anchored
    }

    @Test
    void systemPrompt_anchorsTargetLanguage() {
        assertThat(ThemedDescriptionService.buildSystemPrompt(ZoneAesthetic.garden(), "ja"))
            .contains("Write in Japanese.");
        assertThat(ThemedDescriptionService.buildSystemPrompt(ZoneAesthetic.arcane(), "es"))
            .contains("Write in Spanish.");
        assertThat(ThemedDescriptionService.buildSystemPrompt(ZoneAesthetic.wild(), null))
            .contains("Write in English.");
    }
}
