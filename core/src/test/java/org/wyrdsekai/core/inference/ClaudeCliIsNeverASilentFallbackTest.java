package org.wyrdsekai.core.inference;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import java.util.HashMap;
import java.util.List;

/**
 * A companion's SUBSTRATE must never swap without a human deciding.
 * 2026-08-14: a zone with no local backend silently ran its companion
 * on the operator's paid Claude subscription for a day (and, pre-
 * hardening, with host tool access). The rule this test pins: an empty
 * backend list yields a MUTE companion — an honest absence — and the
 * Claude CLI registers ONLY on explicit opt-in.
 */
class ClaudeCliIsNeverASilentFallbackTest {

    private static com.typesafe.config.Config config(Map<String, Object> extra) {
        var base = new HashMap<String, Object>();
        base.put("default-model", "default");
        base.put("health-check-interval", "30s");
        base.put("backends", List.of());
        base.putAll(extra);
        return ConfigFactory.parseMap(base);
    }

    @Test
    void noBackendsAndNoOptInMeansNoClaudeAuto() {
        // If the CI env force-enables it, this pin cannot run honestly.
        assumeFalse("true".equalsIgnoreCase(System.getenv("WYRDSEKAI_CLAUDE_CLI_ENABLED")));
        var cfg = InferenceConfig.fromConfig(config(Map.of()));
        assertTrue(cfg.backends().stream().noneMatch(b -> "claude-auto".equals(b.name())),
            "an empty backend list must yield a mute companion, never a silent substrate swap");
    }

    @Test
    void explicitConfigOptInRegistersClaudeAutoWhenACliExists() {
        var cfg = InferenceConfig.fromConfig(config(
            Map.of("wyrdsekai.inference.claude-cli.enabled", true)));
        // Environment-dependent: only assert the POSITIVE half when a real
        // CLI is present on this host; the opt-in gate itself is what the
        // first test pins unconditionally.
        boolean cliPresent = ClaudeCliInference.autoDetect().isPresent();
        assertEquals(cliPresent,
            cfg.backends().stream().anyMatch(b -> "claude-auto".equals(b.name())));
    }
}
