package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * substrate-tamper banner must surface on
 * every reactive prompt when the moral-defaults verifier reports a
 * non-clean state.
 */
class TamperBannerTest {

    private static final String KEY = "wyrdsekai.protection.tampered";
    private static final String REASON_KEY = "wyrdsekai.protection.tampered.reason";

    private String savedKey;
    private String savedReason;

    @BeforeEach
    void capture() {
        savedKey = System.getProperty(KEY);
        savedReason = System.getProperty(REASON_KEY);
    }

    @AfterEach
    void restore() {
        if (savedKey == null) System.clearProperty(KEY);
        else System.setProperty(KEY, savedKey);
        if (savedReason == null) System.clearProperty(REASON_KEY);
        else System.setProperty(REASON_KEY, savedReason);
    }

    @Test
    void clean_substrate_emits_no_banner() {
        System.setProperty(KEY, "false");
        assertThat(PromptAssembler.tamperBannerForCurrentState()).isNull();
    }

    @Test
    void tampered_substrate_emits_disclosure_with_reason() {
        System.setProperty(KEY, "true");
        System.setProperty(REASON_KEY, "moral-defaults hash mismatch");
        var banner = PromptAssembler.tamperBannerForCurrentState();
        assertThat(banner).isNotNull();
        assertThat(banner).contains("TAMPERED");
        assertThat(banner).contains("moral-defaults hash mismatch");
        assertThat(banner).contains("voice register");
    }

    @Test
    void unavailable_substrate_emits_honest_absence_message() {
        System.clearProperty(KEY);
        var banner = PromptAssembler.tamperBannerForCurrentState();
        assertThat(banner).isNotNull();
        assertThat(banner).contains("UNAVAILABLE");
        assertThat(banner).contains("not attested");
    }

    @Test
    void unavailable_substrate_explicit_value_emits_banner() {
        System.setProperty(KEY, "unavailable");
        var banner = PromptAssembler.tamperBannerForCurrentState();
        assertThat(banner).isNotNull();
        assertThat(banner).contains("UNAVAILABLE");
    }
}
