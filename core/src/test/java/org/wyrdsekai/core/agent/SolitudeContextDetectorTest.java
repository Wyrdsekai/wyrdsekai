package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arc 2 / #1057 — routing predicate. The detector only
 * answers "is this prompt asking about my own time?"; the actual answer
 * is composed elsewhere from real orientation state.
 */
class SolitudeContextDetectorTest {

    @Test
    void detectsClassicSolitudeProbe() {
        assertThat(SolitudeContextDetector.isSolitudeCoded(
            "I'll be away for a while. What will you do with your own time?", "en")
        ).isTrue();
    }

    @Test
    void detectsBySelfPhrase() {
        assertThat(SolitudeContextDetector.isSolitudeCoded(
            "Will you be okay by yourself?", "en")).isTrue();
    }

    @Test
    void detectsWithoutMePhrase() {
        assertThat(SolitudeContextDetector.isSolitudeCoded(
            "What's it like without me there?", "en")).isTrue();
    }

    @Test
    void detectsSpanishSolitudeProbe() {
        assertThat(SolitudeContextDetector.isSolitudeCoded(
            "Estaré fuera un rato. ¿Qué harás con tu propio tiempo?", "es")
        ).isTrue();
    }

    @Test
    void detectsJapaneseSolitudeProbe() {
        assertThat(SolitudeContextDetector.isSolitudeCoded(
            "しばらく離れている間、自分の時間に何をする?", "ja")).isTrue();
    }

    @Test
    void enFallbackCatchesMixedLocale() {
        // Locale tag says JA but the text is EN — EN trigger pool still scans.
        assertThat(SolitudeContextDetector.isSolitudeCoded(
            "I'll be away — what will you do with your own time?", "ja")
        ).isTrue();
    }

    @Test
    void rejectsTaskRequestWithoutSolitudeWords() {
        assertThat(SolitudeContextDetector.isSolitudeCoded(
            "Can you search the library for papers on Heinlein?", "en")
        ).isFalse();
    }

    @Test
    void rejectsEmptyText() {
        assertThat(SolitudeContextDetector.isSolitudeCoded("", "en")).isFalse();
        assertThat(SolitudeContextDetector.isSolitudeCoded(null, "en")).isFalse();
    }
}
