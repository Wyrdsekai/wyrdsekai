package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The library card's report, spoken as her own line (the household node's real lines, 2026-10-04). */
class WhatSheReadIsSaidAsHersTest {

    @Test
    void the_reports_she_spoke_last_night_come_out_in_her_voice() {
        assertThat(ReadInHerVoice.of("The sources describe two women sitting together in silence on a train, their plain, modest presence noted by an observer [S1]."))
            .isEqualTo("I read about two women sitting together in silence on a train, their plain, modest presence noted by an observer.");
        assertThat(ReadInHerVoice.of("The sources say that diffusion models are trained by adding Gaussian noise in a forward diffusion [S2][S5]."))
            .isEqualTo("I read that diffusion models are trained by adding Gaussian noise in a forward diffusion.");
        assertThat(ReadInHerVoice.of("The sources say Japanese literature lacks epic poems [S2][S5][S8]."))
            .isEqualTo("I read that Japanese literature lacks epic poems.");
        assertThat(ReadInHerVoice.of("In S1, Gail and Caroline's intimacy developed through careful, even silent exchange."))
            .isEqualTo("In one of them, Gail and Caroline's intimacy developed through careful, even silent exchange.");
        assertThat(ReadInHerVoice.of("According to the sources, attention is a memory bank [S1, S3]."))
            .isEqualTo("From what I read, attention is a memory bank.");
    }

    @Test
    void an_absence_is_said_as_hers_too_and_nothing_else_is_touched() {
        assertThat(ReadInHerVoice.of("The provided sources do not contain information about Lazarus Long."))
            .isEqualTo("I didn't find anything in what I read about Lazarus Long.");
        assertThat(ReadInHerVoice.of("Attention weighs each token by its relevance [S1]. The sources do not cover the years 2024 and 2025."))
            .isEqualTo("Attention weighs each token by its relevance. What I read doesn't cover the years 2024 and 2025.");
        var plain = "Quiet isn't absence; it's presence without performance.";
        assertThat(ReadInHerVoice.of(plain)).isEqualTo(plain);
        assertThat(ReadInHerVoice.of("")).isEqualTo("");
        assertThat(ReadInHerVoice.of(null)).isNull();
    }

    @Test
    void the_companion_speaks_findings_through_it_and_never_a_recitation() throws Exception {
        var base = Files.exists(Path.of("..", "core")) ? Path.of("..") : Path.of(".");
        var src = Files.readString(base.resolve("core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));
        int verbatim = src.indexOf("var parts = verbatimUtterances(cleanSummary);");
        int voiced = src.indexOf("speakFindings(ReadInHerVoice.of(spoken), forPerson);");
        assertThat(verbatim).isPositive();
        assertThat(voiced).as("the summarised findings are spoken in her voice").isGreaterThan(verbatim);
    }
}
