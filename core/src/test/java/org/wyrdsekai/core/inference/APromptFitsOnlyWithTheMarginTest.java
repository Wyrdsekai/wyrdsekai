package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Our token count is four characters a token and undercounts. A prompt it put under 8,192 was
 * 8,271 to the server, was refused, and the turn timed out on the retry (household node,
 * 2026-10-19). A prompt goes out whole only when the margin is to spare.
 */
class APromptFitsOnlyWithTheMarginTest {

    @Test
    @DisplayName("just under the window is not a fit; under the window by the margin is")
    void theMargin() {
        int window = 8192;
        int margin = InferenceRouter.ctxSafetyMargin(window);   // 1024 on 8192
        assertThat(InferenceRouter.fitsWindow(8100, window)).as("8,100 of 8,192: shrunk first").isFalse();
        assertThat(InferenceRouter.fitsWindow(window - margin, window)).isTrue();
        assertThat(InferenceRouter.fitsWindow(window - margin + 1, window)).isFalse();
        assertThat(InferenceRouter.fitsWindow(7000, window)).isTrue();
    }

    @Test
    @DisplayName("an unknown window never shrinks anything here")
    void unknownWindow() {
        assertThat(InferenceRouter.fitsWindow(50_000, 0)).isTrue();
        assertThat(InferenceRouter.fitsWindow(50_000, -1)).isTrue();
    }
}
