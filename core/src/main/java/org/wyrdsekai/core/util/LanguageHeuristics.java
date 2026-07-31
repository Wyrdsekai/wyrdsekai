package org.wyrdsekai.core.util;

import java.util.Locale;

/**
 * Lightweight language heuristics for the three supported locales (en/es/ja).
 * Not a language detector in the general sense — just enough signal to catch
 * the voice 4B's code-switch drift and to notice which language a bondholder
 * is actually typing in.
 *
 * <p>Extracted from ThemedDescriptionService (2026-07-31), where it guarded
 * themed-description bakes against en→es drift, so the CONVERSATION path can
 * use the same signal: live-observed on a household node, a companion
 * answered English input in Spanish because the prompt carried no language
 * guidance at all for en-locale users (see CompanionActor#buildLocaleContext).
 */
public final class LanguageHeuristics {

    private LanguageHeuristics() {}

    /** Space-padded Spanish function words that don't collide with English words. */
    private static final String[] SPANISH_MARKERS = {
        " el ", " la ", " los ", " las ", " un ", " una ", " unos ", " unas ",
        " con ", " que ", " para ", " por ", " del ", " está ", " están ",
        " su ", " sus ", " tus ", " más ", " muy ", " en el ", " en la ",
        " de la ", " de los ", " contra ", " frente ", " junto ", " al ",
    };

    private static boolean hasCjk(String text) {
        return text.codePoints().anyMatch(c ->
            (c >= 0x3040 && c <= 0x30FF)      // hiragana + katakana
            || (c >= 0x4E00 && c <= 0x9FFF)   // CJK ideographs
            || (c >= 0xFF66 && c <= 0xFF9D)); // half-width katakana
    }

    private static boolean looksSpanish(String text) {
        boolean hasSpanishChar = text.indexOf('ñ') >= 0 || text.indexOf('Ñ') >= 0
            || text.indexOf('¿') >= 0 || text.indexOf('¡') >= 0;
        if (hasSpanishChar) return true;
        var padded = (" " + text.toLowerCase(Locale.ROOT) + " ")
            .replaceAll("[^\\p{L}]", " ");   // non-letters → spaces so markers match near punctuation
        int esHits = 0;
        for (var m : SPANISH_MARKERS) {
            if (padded.contains(m)) { esHits++; if (esHits >= 2) return true; }
        }
        return false;
    }

    /**
     * Does {@code text} read as the requested {@code lang}? Japanese is
     * unambiguous (script); Spanish is flagged by function-word density +
     * distinctive characters. Errs toward accepting for es/ja (only the
     * observed en→es drift is strict) so a legitimately-themed rewrite is
     * never rejected.
     */
    public static boolean matches(String text, String lang) {
        if (text == null || text.isBlank()) return true;
        boolean cjk = hasCjk(text);
        boolean spanish = looksSpanish(text);
        return switch (lang == null ? "en" : lang.toLowerCase(Locale.ROOT)) {
            case "ja" -> cjk;                 // Japanese must be in Japanese script
            case "es" -> !cjk;                // Spanish: only reject a Japanese drift
            default   -> !cjk && !spanish;    // English: reject Japanese or Spanish drift
        };
    }

    /**
     * Which supported language does {@code text} confidently read as?
     * Returns {@code "ja"} or {@code "es"} on a clear signal, {@code null}
     * when there is none (short latin-script text is deliberately null, NOT
     * "en" — a bare "hola" shouldn't flip anything; callers fall back to the
     * account locale).
     */
    public static String detect(String text) {
        if (text == null || text.isBlank()) return null;
        if (hasCjk(text)) return "ja";
        if (looksSpanish(text)) return "es";
        return null;
    }
}
