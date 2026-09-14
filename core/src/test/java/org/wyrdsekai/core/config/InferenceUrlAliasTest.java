package org.wyrdsekai.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code WYRDSEKAI_LLAMA_URL} is the key the Windows CLI writes, the tray and desktop
 * settings describe, and the install docs tell a steward to point at a remote node. Until
 * 2026-09-13 no Java read it: the server resolved {@code WYRDSEKAI_INFERENCE_URL} only, so a
 * Windows node pointed at a household GPU kept the 127.0.0.1:8200 default and its companion
 * could not think, with nothing in the log to say why.
 */
class InferenceUrlAliasTest {

    private static void noAmbientUrls() {
        assumeTrue(System.getenv("WYRDSEKAI_INFERENCE_URL") == null, "ambient WYRDSEKAI_INFERENCE_URL");
        assumeTrue(System.getenv("WYRDSEKAI_LLAMA_URL") == null, "ambient WYRDSEKAI_LLAMA_URL");
    }

    @Test
    @DisplayName("a llama URL that names another host is the inference URL")
    void remoteLlamaUrlIsHonoured() {
        noAmbientUrls();
        var cfg = WyrdConfig.forProfile(Map.of("inference.llama_url", "http://198.51.100.20:8200"));
        assertEquals("http://198.51.100.20:8200", cfg.configuredInferenceUrl());
        assertEquals("http://198.51.100.20:8200", cfg.inferenceUrl());
    }

    @Test
    @DisplayName("the canonical key wins when both are set")
    void inferenceUrlWins() {
        noAmbientUrls();
        var cfg = WyrdConfig.forProfile(Map.of(
            "inference.url", "http://198.51.100.30:8200",
            "inference.llama_url", "http://198.51.100.20:8200"));
        assertEquals("http://198.51.100.30:8200", cfg.configuredInferenceUrl());
    }

    @Test
    @DisplayName("a loopback llama URL is the local layout — left to auto-detection, default kept")
    void loopbackLlamaUrlIsLocalLayout() {
        noAmbientUrls();
        var cfg = WyrdConfig.forProfile(Map.of("inference.llama_url", "http://127.0.0.1:8200"));
        assertNull(cfg.configuredInferenceUrl(), "auto-detection probes :8200 and :8201 itself");
        assertEquals("http://127.0.0.1:8200", cfg.inferenceUrl());
    }

    @Test
    @DisplayName("nothing set: no configured URL, the default stands")
    void unset() {
        noAmbientUrls();
        var cfg = WyrdConfig.forProfile(Map.of());
        assertNull(cfg.configuredInferenceUrl());
        assertEquals("http://127.0.0.1:8200", cfg.inferenceUrl());
    }

    @Test
    void loopbackRecognition() {
        assertTrue(WyrdConfig.isLoopback("http://127.0.0.1:8200"));
        assertTrue(WyrdConfig.isLoopback("http://localhost:8200/v1"));
        assertTrue(WyrdConfig.isLoopback("http://[::1]:8200"));
        assertTrue(WyrdConfig.isLoopback("not a url"));
        assertFalse(WyrdConfig.isLoopback("http://198.51.100.20:8200"));
        assertFalse(WyrdConfig.isLoopback("https://inference.example.org"));
    }
}
