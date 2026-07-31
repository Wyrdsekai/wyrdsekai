package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 4B voice model translated English drafts into Spanish — measured 16/24 on second-node's
 * production v10. The fact guard could not catch it, because a translation is a
 * fact-PRESERVING corruption: "low 63F high 86F" survives intact inside "baja 63F,
 * alta 86F", so every required-fact check passes and the wrong language still reaches
 * the user. A leading, positively-phrased language pin drives the drift to 0/32, but a
 * prompt is a request; this guard is the guarantee.
 */
class VoiceLanguageGuardTest {

    @Test
    @DisplayName("the exact drift measured on second-node is rejected — raw draft wins")
    void rejectsTheRegressionCase() {
        var draft = "The forecast for San Francisco tomorrow: low 63F high 86F scattered clouds.";
        var spanish = "San Francisco mañana: baja 63F, alta 86F, nubes dispersas.";
        // Note this polish keeps BOTH required numbers — the fact guard would pass it.
        var required = Set.of("63F", "86F");
        assertSame(draft, CompanionActor.chooseVoicedLine(draft, spanish, required),
            "a fact-preserving translation must still be rejected");
    }

    @Test
    @DisplayName("a genuine English polish is accepted")
    void acceptsCleanEnglishPolish() {
        var draft = "Let me check. I have examined the journal and it is on the chair's arm.";
        var polished = "The journal is on the chair's arm.";
        assertSame(polished, CompanionActor.chooseVoicedLine(draft, polished, Set.of()),
            "the voice must still be allowed to do its job");
    }

    @Test
    @DisplayName("a Japanese household is not dragged into English")
    void japaneseStaysJapanese() {
        var draft = "明日のサンフランシスコの予報：最低16度、最高30度、雲が多いです。";
        var english = "Tomorrow in San Francisco: low 16, high 30, mostly cloudy.";
        assertSame(draft, CompanionActor.chooseVoicedLine(draft, english, Set.of()),
            "translating a JA draft to EN is the same bug in the other direction");

        var jaPolish = "明日のサンフランシスコは最低16度、最高30度、雲が多いです。";
        assertSame(jaPolish, CompanionActor.chooseVoicedLine(draft, jaPolish, Set.of()));
    }

    @Test
    @DisplayName("language detection separates the three shipped locales")
    void detectsShippedLocales() {
        assertEquals("en", CompanionActor.detectLanguage(
            "The kettle is warm and the light is low this evening."));
        assertEquals("es", CompanionActor.detectLanguage(
            "Mon fue un poco más frío de lo habitual para esta época del año."));
        assertEquals("ja", CompanionActor.detectLanguage("明日の予報は雲が多いです"));
        // Too short to judge → null, so we do NOT reject on a coin-flip.
        assertEquals(null, CompanionActor.detectLanguage("ok"));
        assertEquals(null, CompanionActor.detectLanguage(""));
    }

    @Test
    @DisplayName("ambiguity does not reject — a false reject only costs polish, so we bias correctly")
    void ambiguousDoesNotReject() {
        // Short/unclassifiable text yields null on one side → no confident disagreement.
        assertFalse(CompanionActor.languageChanged("ok", "Understood, I'm on it."));
        assertFalse(CompanionActor.languageChanged(null, "anything"));
    }

    @Test
    @DisplayName("the pin names the household's language, it does not force English")
    void pinNamesTheLocale() {
        assertEquals("English", CompanionActor.languageName("en"));
        assertEquals("Japanese", CompanionActor.languageName("ja"));
        assertEquals("Spanish", CompanionActor.languageName("es"));
        assertEquals("Japanese", CompanionActor.languageName("ja-JP"));
        assertEquals("English", CompanionActor.languageName(null));
    }

    @Test
    @DisplayName("language drift is caught even when the polish keeps every required fact")
    void languageBeatsFactCheck() {
        assertTrue(CompanionActor.languageChanged(
            "I will keep an eye on how that settles over the next few days.",
            "Voy a seguir de cerca cómo se va asentando durante los próximos días."));
    }
}
