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
    @DisplayName("directional: a polish that CORRECTS an off-language draft is accepted")
    void directionalAcceptsCorrectionTowardExpected() {
        // The live 2026-07-31 loop: the 9B authored Spanish own-time musings to an
        // English household; the polish pin translated them back; the SYMMETRIC
        // guard rejected the correction and faithfully spoke the Spanish draft.
        var spanishDraft = "Estoy aquí contigo — muchas cosas me aprietan al mismo tiempo.";
        var englishPolish = "I'm here with you — a lot of things are pressing on me at once.";
        assertSame(englishPolish,
            CompanionActor.chooseVoicedLine(spanishDraft, englishPolish, Set.of(), "en"),
            "moving TOWARD the user's language is the floor working, not corruption");
    }

    @Test
    @DisplayName("directional: a polish that drifts AWAY from the expected language is rejected")
    void directionalRejectsDriftAwayFromExpected() {
        var draft = "The forecast for San Francisco tomorrow: low 63F high 86F scattered clouds.";
        var spanish = "San Francisco mañana: baja 63F, alta 86F, nubes dispersas.";
        assertSame(draft,
            CompanionActor.chooseVoicedLine(draft, spanish, Set.of("63F", "86F"), "en"),
            "the original protection still holds when the expected language is known");
    }

    @Test
    @DisplayName("directional: mirror turns pass — Spanish stays Spanish when expected is Spanish")
    void directionalHonoursTheMirror() {
        var draft = "Claro, puedo ayudarte con eso ahora mismo si quieres empezar.";
        var polish = "Claro — puedo ayudarte con eso ahora mismo, ¿empezamos?";
        assertSame(polish,
            CompanionActor.chooseVoicedLine(draft, polish, Set.of(), "es"),
            "a bondholder who writes Spanish is answered in Spanish, floor silent");
    }

    @Test
    @DisplayName("correction: ja→en expansion is translation density, not hallucination")
    void correctionToleratesCrossScriptExpansion() {
        // 44c of kanji rendered faithfully in English is a few hundred latin
        // chars — the live 2026-07-31 failure rejected every ja→en correction
        // on the expansion cap (44c→124c, 50c→454c) and spoke raw Japanese.
        var jaDraft = "ただ、図書館にいるのが好き——何も言わないでよ。あなたがいるからここに留まっているのね。";
        var enPolish = "I just like being in the library — you don't have to say anything. "
            + "I'm staying here because you're here, that's all it is.";
        assertSame(enPolish,
            CompanionActor.chooseVoicedLine(jaDraft, enPolish, Set.of(), "en"));
    }

    @Test
    @DisplayName("correction: numbers survive as digit runs, not verbatim formatted tokens")
    void correctionChecksDigitRuns() {
        var jaDraft = "2024-25年のarXivからトレンドを拾いながら探せてるよ。";
        var enPolish = "I've been tracking trends from arXiv across 2024-25.";
        // "2025" as a formatted token is absent but the digit runs 2024/25 survive.
        assertSame(enPolish,
            CompanionActor.chooseVoicedLine(jaDraft, enPolish, Set.of("2024", "25"), "en"));
        // Digits actually dropped → still rejected.
        var lossy = "I've been tracking recent trends from arXiv.";
        assertSame(jaDraft,
            CompanionActor.chooseVoicedLine(jaDraft, lossy, Set.of("2024", "25"), "en"));
    }

    @Test
    @DisplayName("language drift is caught even when the polish keeps every required fact")
    void languageBeatsFactCheck() {
        assertTrue(CompanionActor.languageChanged(
            "I will keep an eye on how that settles over the next few days.",
            "Voy a seguir de cerca cómo se va asentando durante los próximos días."));
    }
}
