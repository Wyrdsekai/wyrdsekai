package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The voice guard's blind spot: under four words the detector says "unsure" rather than guess,
 * and the guard needed BOTH sides identified before it would fire, so a short line in the
 * wrong language reached the household unchecked (2026-09-15).
 *
 * <p>The error cost is deliberately lopsided. A false positive costs the polish — she speaks
 * the correct raw draft. A false negative speaks Spanish to an English household.
 * </p>
 */
class AShortLineInTheWrongLanguageTest {

    private static final String DRAFT = "I will be there in a moment, and I will bring the book.";

    @Test
    @DisplayName("a short Spanish polish no longer passes as unsure")
    void shortSpanishIsCaught() {
        var reason = new CompanionActor.VoiceReject[1];
        for (var polish : new String[]{"Vale, gracias.", "Claro que si", "Todo listo", "Ahora voy"}) {
            var chosen = CompanionActor.chooseVoicedLine(DRAFT, polish, Set.of(), "en", reason);
            assertSame(DRAFT, chosen, "should have fallen back: " + polish);
            assertEquals(CompanionActor.VoiceReject.LANGUAGE, reason[0], polish);
        }
    }

    @Test
    @DisplayName("a short English polish is still accepted")
    void shortEnglishStillPasses() {
        var reason = new CompanionActor.VoiceReject[1];
        for (var polish : new String[]{"On my way.", "Two minutes.", "Yes — bringing it now.",
                "I'll be right there with the book."}) {
            var chosen = CompanionActor.chooseVoicedLine(DRAFT, polish, Set.of(), "en", reason);
            assertNotSame(DRAFT, chosen, "wrongly rejected: " + polish);
            assertEquals(CompanionActor.VoiceReject.NONE, reason[0], polish);
        }
    }

    @Test
    @DisplayName("the short-text rule only speaks about languages the detector can verify")
    void onlyVerifiableLanguages() {
        assertTrue(CompanionActor.offLanguageShort("Vale, gracias", "en"));
        assertTrue(CompanionActor.offLanguageShort("está", "ja"));
        assertFalse(CompanionActor.offLanguageShort("Vale, gracias", "es"),
            "a Spanish household expects Spanish");
        assertFalse(CompanionActor.offLanguageShort("Bonjour", "fr"),
            "French is pin-only: the detector must not pass judgment on it");
        assertFalse(CompanionActor.offLanguageShort("On my way.", "en"));
        assertFalse(CompanionActor.offLanguageShort("", "en"));
        assertFalse(CompanionActor.offLanguageShort(null, "en"));
    }

    @Test
    @DisplayName("a long polish is judged as before — this changes nothing above four words")
    void longTextUnchanged() {
        var reason = new CompanionActor.VoiceReject[1];
        var spanish = "Estare alli en un momento, y voy a traer el libro que me pediste.";
        assertSame(DRAFT, CompanionActor.chooseVoicedLine(DRAFT, spanish, Set.of(), "en", reason));
        assertEquals(CompanionActor.VoiceReject.LANGUAGE, reason[0]);

        var english = "I'll be there in a moment, and I'll bring the book you asked for.";
        assertNotSame(DRAFT, CompanionActor.chooseVoicedLine(DRAFT, english, Set.of(), "en", reason));
    }

    @Test
    @DisplayName("an off-language draft corrected INTO the household's language is still accepted")
    void correctionsStillPass() {
        var reason = new CompanionActor.VoiceReject[1];
        var spanishDraft = "Estare alli en un momento y traere el libro que me pediste ahora.";
        var englishPolish = "I'll be there in a moment, and I'll bring the book.";
        assertSame(englishPolish,
            CompanionActor.chooseVoicedLine(spanishDraft, englishPolish, Set.of(), "en", reason));
        assertEquals(CompanionActor.VoiceReject.NONE, reason[0]);
    }
}
