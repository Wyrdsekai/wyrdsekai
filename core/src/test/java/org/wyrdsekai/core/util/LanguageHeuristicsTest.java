package org.wyrdsekai.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The conversation-path language guard (2026-07-31): live-observed, a
 * companion answered English input in Spanish because en-locale users got no
 * language instruction at all. These tests pin the detect/matches semantics
 * that CompanionActor#buildLocaleContext and ThemedDescriptionService rely on.
 */
class LanguageHeuristicsTest {

    @Test
    void detectsJapaneseByScript() {
        assertEquals("ja", LanguageHeuristics.detect("おはようございます"));
        assertEquals("ja", LanguageHeuristics.detect("良い朝ですね"));
    }

    @Test
    void detectsSpanishByMarkersAndChars() {
        assertEquals("es", LanguageHeuristics.detect("¿Cómo estás hoy?"));
        assertEquals("es", LanguageHeuristics.detect(
            "Los cajones de bronce están junto a la ventana para el catálogo"));
    }

    @Test
    void plainEnglishAndShortLatinAreNull() {
        // No confident signal — callers fall back to the account locale.
        assertNull(LanguageHeuristics.detect("Good morning, how did you sleep?"));
        assertNull(LanguageHeuristics.detect("hola"));   // one bare word must not flip anything
        assertNull(LanguageHeuristics.detect(""));
        assertNull(LanguageHeuristics.detect(null));
    }

    @Test
    void englishRejectsTheObservedSpanishDrift() {
        // The exact drift shape observed live.
        assertFalse(LanguageHeuristics.matches(
            "Desks yegües llenan el nodo, cada una con balanzas de bronce", "en"));
        assertTrue(LanguageHeuristics.matches("A worn leather chair faces the hearth.", "en"));
    }

    @Test
    void matchesErrsTowardAcceptingForEsAndJa() {
        assertTrue(LanguageHeuristics.matches("A quiet room.", "es"));  // only ja drift rejected for es
        assertFalse(LanguageHeuristics.matches("静かな部屋です。", "en"));
        assertTrue(LanguageHeuristics.matches("静かな部屋です。", "ja"));
    }
}
