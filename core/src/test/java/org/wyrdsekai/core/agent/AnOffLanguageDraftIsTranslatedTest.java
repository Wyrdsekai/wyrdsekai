package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How Spanish actually reached an English household (2026-09-15, from the node's record):
 * the drive model authored the line in Spanish, the correction pass came back Spanish too,
 * and the guard accepted it — its rule only asked whether the polish had moved away from an
 * on-language draft. Five lines in ten days, all through that door; none through polish
 * drift, which the guard catches every time.
 *
 * <p>Three things pin the fix: the guard names that case instead of accepting it, the
 * detector stops reading English names as Spanish function words (which would send an
 * English line to be translated INTO Spanish), and the correction prompt is worded as a
 * translation from a named language — measured 119/120 against 80/120 for "say the same
 * thing in English".</p>
 */
class AnOffLanguageDraftIsTranslatedTest {

    private static final Path ACTOR =
        Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    @Test
    @DisplayName("an off-language draft whose correction is still off-language is not spoken as if corrected")
    void uncorrectedIsNamed() {
        var draft = "El espacio que acabas de crear es The Quiet Alcove, una puerta que lleva de vuelta a lo que importa.";
        var polish = "Ese espacio que acabas de crear es The Quiet Alcove — una puerta que lleva de vuelta a lo que importa.";
        var reason = new CompanionActor.VoiceReject[1];
        var chosen = CompanionActor.chooseVoicedLine(draft, polish, Set.of(), "en", reason);
        assertEquals(CompanionActor.VoiceReject.LANGUAGE_UNCORRECTED, reason[0]);
        assertSame(draft, chosen, "nothing on-language exists; the author's own words are spoken, and the failure is logged");
    }

    @Test
    @DisplayName("a correction that lands in the household's language is still accepted")
    void correctionAccepted() {
        var draft = "El espacio que acabas de crear es The Quiet Alcove, una puerta que lleva de vuelta a lo que importa.";
        var polish = "The space you just created is The Quiet Alcove — a door that leads back to what matters.";
        var reason = new CompanionActor.VoiceReject[1];
        assertSame(polish, CompanionActor.chooseVoicedLine(draft, polish, Set.of(), "en", reason));
        assertEquals(CompanionActor.VoiceReject.NONE, reason[0]);
    }

    @Test
    @DisplayName("English names are not Spanish function words")
    void namesAreNotMarkers() {
        assertEquals("en", CompanionActor.detectLanguage(
            "Al and Del went to Los Angeles for the con, and I kept the room warm while they were away."));
        assertEquals("en", CompanionActor.detectLanguage("Con and Del are at Las Vegas until Sunday, then home."));
        // Real Spanish still reads as Spanish: the markers are lowercase mid-sentence.
        assertEquals("es", CompanionActor.detectLanguage(
            "El suelo me sostiene lo suficiente para que deje que todo encuentre su propia forma."));
        assertEquals("es", CompanionActor.detectLanguage(
            "Bondholder's Foundation es ahora tu lugar seguro, el camino de regreso que construiste conmigo."));
        // And the short-line pass ignores the same three words.
        assertFalse(CompanionActor.offLanguageShort("Con, Del and Al.", "en"));
        assertTrue(CompanionActor.offLanguageShort("Vale, gracias.", "en"));
        assertNull(CompanionActor.detectLanguage("Los Angeles"));
    }

    @Test
    @DisplayName("the correction pass is worded as a translation from a named language, at temperature 0")
    void correctionIsATranslation() throws Exception {
        var src = Files.readString(ACTOR);
        assertTrue(src.contains("\"Translate the draft into natural, warm first-person \""),
            "the wording that corrected 119/120");
        assertTrue(src.contains("\". The draft below is in \").append(fromLang)"),
            "the source language is named");
        assertFalse(src.contains("Say the same thing in \").append(pinLang)"),
            "the restatement wording that failed 40/120 is gone");
        assertTrue(src.contains("200, rewrite ? 0.0 : 0.3,"), "a translation runs at temperature 0");
        assertTrue(src.contains("boolean rewrite = wrongLanguage\n            || (detectorVerifiable(expectedLang) && draftLang != null && !draftLang.equals(expectedLang));"),
            "the floor is at the polish door, not only in speak()");
    }
}
